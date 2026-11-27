package app

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

func TestQueueClickSkipsFailedSongsAndKeepsFailureReasons(t *testing.T) {
	a := newTestApp()
	posted := make(chan func(), 64)
	a.pl = player.New(func(fn func()) { posted <- fn })
	a.pl.Source = a.sourceByID
	a.pl.SetVolume(0)
	defer a.pl.ClearSession()
	file, err := filepath.Abs("../player/testdata/seek.mp3")
	if err != nil {
		t.Fatal(err)
	}
	fake(a).stream = func(id, quality string) (*music.Stream, error) {
		if id != "good" {
			return nil, music.ErrVIP
		}
		return &music.Stream{URL: "file://" + filepath.ToSlash(file)}, nil
	}
	tracks := []music.Track{{ID: "bad", Source: "fake", Name: "Failed first"},
		{ID: "bad-next", Source: "fake", Name: "Failed next"}, {ID: "good", Source: "fake", Name: "Playable following"}}
	a.pl.Restore(player.Session{Queue: tracks, Order: []int{0, 1, 2}, At: 2})
	a.nowPlaying, a.npPane = true, paneQueue
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	tt.Advance(time.Second)
	tt.Frame()
	if err := tt.Click("Failed first"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Frame()
	until(t, posted, func() bool { return !a.pl.Loading() })
	tt.Frame()
	if a.pl.Current().ID != "good" || !a.pl.Playing() || a.pl.Err() != "" {
		t.Fatalf("current=%v error=%s", a.pl.Current(), a.pl.Err())
	}
	if len(a.pl.Queue()) != 3 || a.pl.TrackProblem(&tracks[0]) != music.TrackVIP || a.pl.TrackProblem(&tracks[1]) != music.TrackVIP {
		t.Fatal("failed songs or their diagnostics disappeared")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "playback-failure-continues.png", tt.Image())
	}
}
