package app

import (
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

type playlistReveal struct {
	track   music.Track
	done    bool
	placed  bool
	frameAt time.Time
	animate bool
	to      float32
}

// Locate by row so distant, unmeasured items do not rely on estimated heights.
// Then glide the final two rows rather than scrolling past thousands of songs.
func (a *app) revealPlaylistTrack(c *ui.Context, tracks []music.Track) bool {
	r := &a.playlistReveal
	if a.route.page != pagePlaylist || r.done || r.track.ID == "" || r.track.Source != a.library.id {
		return false
	}
	if r.placed {
		if c.Now().Equal(r.frameAt) {
			c.After(time.Second / 60)
			return true
		}
		target := a.pageScroll.Y
		a.pageScroll.Y = max(0, target-108)
		r.animate, r.to = true, target
		r.done = true
		return false
	}
	for i := range tracks {
		if sameTrack(&tracks[i], &r.track) {
			a.trackList.ScrollTo(i+1, ui.Center)
			r.placed, r.frameAt = true, c.Now()
			c.After(time.Second / 60)
			return true
		}
	}
	lib, id := a.library, a.route.id
	if page := lib.playlists[id]; page != nil && page.More {
		if lib.plErr[id] == "" {
			a.loadPlaylistNext(lib, id, page)
		}
	} else {
		r.done = true
	}
	return false
}
