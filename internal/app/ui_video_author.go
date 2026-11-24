package app

import (
	"errors"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
)

type videoAuthorKey struct {
	library *library
	gen     int
	id      string
}

type videoAuthorState struct {
	key     videoAuthorKey
	tracks  []music.Track
	known   map[[2]string]bool
	offset  int
	more    bool
	loading bool
	err     string
}

func (a *app) videoAuthor(track *music.Track) (videoAuthorKey, string) {
	if track != nil {
		lib := a.libOf(track.Source)
		if track.Source == "" {
			lib = a.library
		}
		if lib.can("artist") {
			for _, artist := range track.Artists {
				if artist.ID != "" {
					return videoAuthorKey{lib, lib.accountGen, artist.ID}, artist.Name
				}
			}
		}
	}
	return videoAuthorKey{}, ""
}

func (key videoAuthorKey) fetch(offset int) ([]music.Track, bool, int, error) {
	var page *music.ArtistPage
	var err error
	pager, paged := key.library.src.(music.ArtistPager)
	if paged {
		page, err = answered(pager.ArtistPage(key.id, offset))
	} else {
		page, err = answered(key.library.src.Artist(key.id))
	}
	if err != nil {
		return nil, false, 0, err
	}
	if len(page.Tracks) == 0 && page.Empty != "" {
		return nil, false, 0, errors.New(page.Empty)
	}
	return page.Tracks, paged && page.More, page.NextOffset, nil
}

func (a *app) loadVideoAuthor(state *videoAuthorState) {
	if state.loading {
		return
	}
	state.loading, state.err = true, ""
	offset, key := state.offset, state.key
	go func() {
		tracks, more, next, err := key.fetch(offset)
		a.post(func() {
			current, _ := a.videoAuthor(a.pl.Current())
			if a.mv.author != state {
				return
			}
			if current != key {
				a.mv.author = nil
				return
			}
			state.loading = false
			if err != nil {
				state.err = a.problem(key.library, err, T("Couldn't load this artist"))
				return
			}
			fresh := newCatalogTracks(state.known, tracks)
			state.tracks = append(state.tracks, fresh...)
			state.offset = continuationOffset(offset, next, len(tracks))
			state.more = more && state.offset > offset && (len(fresh) != 0 || next > offset)
		})
	}()
}

func (a *app) videoAbout(c *ui.Context, track *music.Track) {
	m, key := &a.mv, track.Source+"\x00"+track.ID
	author, name := a.videoAuthor(track)
	state := m.author
	if author.library == nil {
		state, m.author = nil, nil
	} else if state == nil || state.key != author {
		state = &videoAuthorState{key: author, known: make(map[[2]string]bool)}
		m.author = state
		a.loadVideoAuthor(state)
	}
	count := 1
	if state != nil {
		count += len(state.tracks)
		if state.loading || state.err != "" || state.more || len(state.tracks) == 0 {
			count++
		}
	}
	list := &m.aboutList
	list.Key = func(index int) any {
		if index == 0 {
			return key
		}
		if index <= len(state.tracks) {
			item := state.tracks[index-1]
			return [2]string{item.Source, item.ID}
		}
		return "more"
	}
	ui.List(c.Key("description"), list, count, func(index int) {
		switch {
		case index == 0:
			ui.Column(c.Key("about")).Padding(8, 4).Gap(10).Children(func() {
				ui.Text(c, track.Name).FontSize(18).FontWeight(650).TextColor(white.Alpha(0.95))
				ui.Text(c, artistNames(track.Artists)).FontSize(13).TextColor(white.Alpha(0.6))
				if track.Description != "" {
					ui.Text(c, track.Description).FontSize(13).LineHeight(1.6).TextColor(white.Alpha(0.7))
				}
				if state != nil {
					ui.Text(c, Tf("More from %s", name)).Margin(8, 0, 0, 0).FontSize(14).FontWeight(650)
				}
			})
		case index <= len(state.tracks):
			item := &state.tracks[index-1]
			a.playbackListRow(c, item, item.Source == track.Source && item.ID == track.ID, func() {
				if state.more {
					a.playCatalog(author.library, state.tracks, index-1, offsetTrackPages(state.offset, author.fetch))
				} else {
					a.pl.PlayTrack(state.tracks, index-1)
				}
			})
		default:
			ui.Column(c.Key("status")).Padding(8, 4).Gap(8).Children(func() {
				switch {
				case state.loading:
					ui.Text(c, T("Loading…")).TextColor(white.Alpha(0.6))
				case state.err != "":
					ui.Button(c, T("Retry")).OnClick(func() { a.loadVideoAuthor(state) })
					ui.Text(c, state.err).TextColor(white.Alpha(0.7))
				case state.more:
					ui.Button(c, T("Load more works")).OnClick(func() { a.loadVideoAuthor(state) })
				default:
					ui.Text(c, T("No works yet")).TextColor(white.Alpha(0.6))
				}
			})
		}
	}).Grow(1).MinHeight(0).Gap(2)
	if state != nil && state.more && state.err == "" {
		if first, last := list.Visible(); first > 0 && last == count-1 && list.AtEnd() {
			a.loadVideoAuthor(state)
		}
	}
}
