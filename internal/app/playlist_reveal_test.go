package app

import (
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

func TestPlaylistRevealsCurrentTrackOnce(t *testing.T) {
	for _, playing := range []bool{false, true} {
		t.Run(fmt.Sprint(playing), func(t *testing.T) {
			a := newTestApp()
			tracks := make([]music.Track, 120)
			for i := range tracks {
				tracks[i] = music.Track{Source: "fake", ID: fmt.Sprint(i), Name: fmt.Sprintf("Song %03d", i)}
			}
			a.playlists["list"] = &music.Playlist{ID: "list", Name: "Long list", Tracks: tracks}
			a.pl.Restore(player.Session{Queue: []music.Track{tracks[90]}, Order: []int{0}, At: 0, Position: 12000})
			if playing {
				a.pl.Hold(12 * time.Second)
			}
			a.route = route{page: pagePlaylist, src: "fake", id: "list"}
			tt := ui.NewTester(a.view, 1180, 780)
			tt.Frame()
			tt.Advance(20 * time.Millisecond)
			tt.Frame()
			if !a.pageScroll.Scrolling() {
				t.Fatal("current track did not start a short reveal animation")
			}
			tt.Advance(time.Second)
			tt.Frame()
			bounds, ok := tt.Find("Song 090")
			if !ok || bounds.Y < buttonsHeight || bounds.Y+bounds.H > 780-playerBarHeight {
				t.Fatalf("current track not visible: %+v, found %v", bounds, ok)
			}
			if a.pl.Playing() != playing || a.pl.Position() != 12*time.Second {
				t.Fatal("revealing the track changed playback")
			}
			if dir := os.Getenv("MM_FRAMES"); dir != "" {
				saveFrame(t, dir, fmt.Sprintf("playlist-focus-playing-%t.png", playing), tt.Image())
			}
			tt.Wheel(800, 400, 0, -5000)
			settled(tt)
			if a.pageScroll.Y > 100 || a.pageScroll.Scrolling() {
				t.Fatal("reveal fought manual scrolling")
			}
		})
	}
}

func TestPlaylistRevealRejectsOtherSourceAndLoadsContinuation(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	first := music.Track{Source: "fake", ID: "first", Name: "First"}
	target := music.Track{Source: "fake", ID: "target", Name: "Target"}
	src := &paginatedTestSource{fakeSource: fake(a), page: func(string, int) (*music.Playlist, error) {
		return &music.Playlist{Tracks: []music.Track{target}}, nil
	}}
	a.library.src = src
	a.playlists["list"] = &music.Playlist{ID: "list", Tracks: []music.Track{first}, More: true, NextOffset: 1}
	a.pl.Cue([]music.Track{target})
	a.route = route{page: pagePlaylist, src: "fake", id: "list"}
	tt := ui.NewTester(a.view, 1180, 780)
	tt.Frame()
	until(t, ch, func() bool { return !a.playlists["list"].More })
	settled(tt)
	if !a.playlistReveal.placed {
		t.Fatal("did not locate a track in the next page")
	}
	a.playlistReveal = playlistReveal{track: music.Track{Source: "other", ID: "target"}}
	tt.Frame()
	if a.playlistReveal.placed {
		t.Fatal("matched a track from another source")
	}
}
