package app

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

type playlistResume struct {
	Source   string
	Track    string
	Title    string
	Position int64
}

type playlistResumeState struct {
	items           map[string]playlistResume
	active          string
	loading, failed string
	dirty           bool
}

func playlistResumeKey(lib *library, id string) string {
	account := ""
	if lib.account != nil {
		account = lib.account.ID
	}
	return lib.id + "\x00" + account + "\x00" + id
}

func browseResumeKey(lib *library, id string) string {
	return playlistResumeKey(lib, "\x00browse\x00"+id)
}

func homeTrackListKey(lib *library, section music.Section, index int) string {
	if section.MorePage != "" {
		return browseResumeKey(lib, section.MorePage)
	}
	if section.MoreBuiltin != "" {
		return browseResumeKey(lib, discoveryCollectionKey(section.MoreBuiltin))
	}
	return trackListResumeKey(lib, route{page: pageSection, query: fmt.Sprint(index)})
}

// A standalone plugin page can expose a song list without a playlist entity.
func resumableBrowseTracks(data *browseData) []music.Track {
	if data == nil || data.page == nil {
		return nil
	}
	if len(data.page.Sections) == 1 {
		return data.page.Sections[0].Tracks
	}
	var tracks []music.Track
	for _, section := range data.page.Sections {
		tracks = append(tracks, section.Tracks...)
	}
	return tracks
}

func trackListResumeKey(lib *library, r route) string {
	switch r.page {
	case pagePlaylist:
		return playlistResumeKey(lib, r.id)
	case pageAlbum, pageArtist, pageLiked, pageSection, pageSearch, pageShow:
		id, _ := json.Marshal([3]string{r.id, r.query, fmt.Sprint(r.kind)})
		return playlistResumeKey(lib, fmt.Sprintf("\x00page:%d\x00%s", r.page, id))
	}
	return ""
}

func (a *app) loadPlaylistResumes() {
	state := &a.playlistResume
	if b, err := os.ReadFile(userDataDir("playlist-resume.json")); err == nil {
		_ = json.Unmarshal(b, &state.items)
	}
}

func (a *app) savePlaylistResumes() {
	state := &a.playlistResume
	if !state.dirty {
		return
	}
	b, err := json.Marshal(state.items)
	if err != nil {
		return
	}
	path := userDataDir("playlist-resume.json")
	if os.MkdirAll(filepath.Dir(path), 0700) != nil {
		return
	}
	if os.WriteFile(path+".part", b, 0600) == nil && os.Rename(path+".part", path) == nil {
		state.dirty = false
	}
}

func (a *app) recordPlaylistResume() {
	state := &a.playlistResume
	if state.active == "" || a.pl == nil || a.pl.Current() == nil {
		return
	}
	track := a.pl.Current()
	item := playlistResume{track.Source, track.ID, track.Name, a.pl.Position().Milliseconds()}
	if state.items[state.active] != item {
		if state.items == nil {
			state.items = map[string]playlistResume{}
		}
		state.items[state.active], state.dirty = item, true
	}
}

func (a *app) playlistResumeRow(c *ui.Context, key string, resume func(playlistResume)) {
	state := &a.playlistResume
	mark, found := state.items[key]
	subtitle := T("No playback history")
	if found {
		subtitle = mark.Title + " · " + clock(time.Duration(mark.Position)*time.Millisecond)
	}
	if state.loading == key {
		subtitle = T("Finding the last played track…")
	} else if state.failed == key {
		subtitle = T("Last played track unavailable · Retry")
	}
	p := a.pal
	row := ui.ButtonBase(c.Key("playlist-resume:"+key)).Label(T("Last played")).Disabled(!found || state.loading == key).
		Grow(1).Padding(8, 12).Gap(10).AlignItems(ui.Center).Radius(8).Background(p.hover).Margin(0, 0, 14, 0)
	row.Children(func() {
		ui.Icon(c, iconPlay).Size(14, 14).TextColor(p.accent)
		ui.Text(c, T("Last played")).FontWeight(600).Shrink(0)
		ui.Text(c, subtitle).TextColor(p.muted).MaxLines(1).Grow(1).MinWidth(0)
	})
	row.OnClick(func() { resume(mark) })
}

func (a *app) playResumableList(lib *library, key string, tracks []music.Track, index int, next catalogNext) {
	if len(tracks) == 0 {
		return
	}
	if next != nil {
		a.playCatalog(lib, tracks, index, next)
	} else if index < 0 {
		a.pl.PlayShuffled(tracks)
	} else {
		a.pl.PlayTrack(tracks, index)
	}
	a.rememberTrackList(key)
}

func (a *app) rememberTrackList(key string) {
	if key != "" {
		a.playlistResume.active = key
		a.recordPlaylistResume()
	}
}

func (a *app) resumeTrackedList(lib *library, key string, tracks []music.Track, next catalogNext, mark playlistResume) {
	match := func(t music.Track) bool { return t.Source == mark.Source && t.ID == mark.Track }
	start := func(queue []music.Track, at int, continuation catalogNext) {
		a.playlistResume.failed = ""
		a.playResumableList(lib, key, queue, at, continuation)
		a.pl.Seek(time.Duration(max(0, mark.Position)) * time.Millisecond)
		a.recordPlaylistResume()
	}
	if i := slices.IndexFunc(tracks, match); i >= 0 {
		start(tracks, i, next)
		return
	}
	if next == nil {
		a.playlistResume.failed = key
		return
	}
	a.playlistResume.loading = key
	queue, gen, currentRoute := slices.Clone(tracks), lib.accountGen, a.route
	go func() {
		known := map[[2]string]bool{}
		newCatalogTracks(known, queue)
		i, empty := -1, 0
		for next != nil && i < 0 && empty < 8 {
			batch, more, err := next()
			if err != nil {
				break
			}
			if !more {
				next = nil
			}
			fresh := newCatalogTracks(known, batch)
			if len(fresh) == 0 {
				empty++
			} else {
				empty = 0
			}
			if at := slices.IndexFunc(fresh, match); at >= 0 {
				i = len(queue) + at
			}
			queue = append(queue, fresh...)
		}
		a.post(func() {
			if a.playlistResume.loading != key {
				return
			}
			a.playlistResume.loading = ""
			if a.library != lib || lib.accountGen != gen || a.route != currentRoute {
				return
			}
			if i < 0 {
				a.playlistResume.failed = key
				return
			}
			start(queue, i, next)
		})
	}()
}
