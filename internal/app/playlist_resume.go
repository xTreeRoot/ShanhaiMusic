package app

import (
	"encoding/json"
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

func (a *app) playlistResumeRow(c *ui.Context, lib *library, id string, pl *music.Playlist) {
	key := playlistResumeKey(lib, id)
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
	row := ui.ButtonBase(c.Key("playlist-resume")).Label(T("Last played")).Disabled(!found || state.loading == key).
		Grow(1).Padding(8, 12).Gap(10).AlignItems(ui.Center).Radius(8).Background(p.hover).Margin(0, 0, 14, 0)
	row.Children(func() {
		ui.Icon(c, iconPlay).Size(14, 14).TextColor(p.accent)
		ui.Text(c, T("Last played")).FontWeight(600).Shrink(0)
		ui.Text(c, subtitle).TextColor(p.muted).MaxLines(1).Grow(1).MinWidth(0)
	})
	row.OnClick(func() { a.resumePlaylist(lib, id, pl, key, mark) })
}

func (a *app) resumePlaylist(lib *library, id string, pl *music.Playlist, key string, mark playlistResume) {
	match := func(t music.Track) bool { return t.Source == mark.Source && t.ID == mark.Track }
	start := func(list *music.Playlist, at int) {
		a.playlistResume.failed = ""
		a.playPlaylist(lib, id, list, at)
		a.pl.Seek(time.Duration(max(0, mark.Position)) * time.Millisecond)
	}
	if i := slices.IndexFunc(pl.Tracks, match); i >= 0 {
		start(pl, i)
		return
	}
	pager, ok := lib.src.(music.PlaylistPager)
	if !ok || !pl.More {
		a.playlistResume.failed = key
		return
	}
	a.playlistResume.loading = key
	from, gen := *pl, lib.accountGen
	from.Tracks = slices.Clone(pl.Tracks)
	go func() {
		known := map[[2]string]bool{}
		newCatalogTracks(known, from.Tracks)
		offset := continuationOffset(0, from.NextOffset, len(from.Tracks))
		i := -1
		for from.More && offset > 0 && i < 0 {
			page, err := answered(pager.PlaylistPage(id, offset))
			if err != nil {
				break
			}
			fresh := newCatalogTracks(known, page.Tracks)
			if at := slices.IndexFunc(fresh, match); at >= 0 {
				i = len(from.Tracks) + at
			}
			from.Tracks = append(from.Tracks, fresh...)
			from.NextOffset = continuationOffset(offset, page.NextOffset, len(page.Tracks))
			from.More = page.More && from.NextOffset > offset
			offset = from.NextOffset
		}
		a.post(func() {
			if a.playlistResume.loading != key {
				return
			}
			a.playlistResume.loading = ""
			if a.library != lib || lib.accountGen != gen || a.route.page != pagePlaylist || a.route.id != id {
				return
			}
			if i < 0 {
				a.playlistResume.failed = key
				return
			}
			start(&from, i)
		})
	}()
}
