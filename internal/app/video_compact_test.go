package app

import (
	"fmt"
	"os"
	"testing"

	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func TestVideoWindowShrinksAndKeepsTransport(t *testing.T) {
	for _, large := range []bool{true, false} {
		t.Run(fmt.Sprint(large), func(t *testing.T) {
			a := newTestApp()
			a.mv.large = large
			a.pl.Cue([]music.Track{videoSong("one", "First clip"), videoSong("two", "Next clip")})
			out := audio.NewPlayer(&silence{})
			out.SetVolume(0)
			a.pl.Attach(out)
			defer out.Close()
			view := a.mvView
			if large {
				view = a.videoWindowView
			}
			tt := ui.NewTester(view, 320, 180)
			for _, size := range [][2]int{{320, 180}, {240, 135}, {160, 90}, {180, 320}, {1180, 740}, {160, 90}} {
				tt.SetSize(size[0], size[1])
				settled(tt)
				if !compactVideoWindow(float32(size[0]), float32(size[1])) {
					continue
				}
				for _, label := range []string{"Pause", "Show Main Window", "Full Screen MV", "Progress"} {
					bounds, ok := tt.Find(label)
					if !ok || bounds.X < 0 || bounds.Y < 0 || bounds.X+bounds.W > float32(size[0]) || bounds.Y+bounds.H > float32(size[1]) {
						t.Fatalf("%dx%d: %s outside the window: %+v, found %v", size[0], size[1], label, bounds, ok)
					}
				}
				if err := tt.Click("Pause"); err != nil {
					t.Fatal(err)
				}
				if a.pl.Playing() {
					t.Fatal("compact transport did not pause")
				}
				if err := tt.Click("Play"); err != nil {
					t.Fatal(err)
				}
				if !a.pl.Playing() || a.pl.Current().ID != "one" {
					t.Fatal("resizing changed playback")
				}
				if dir := os.Getenv("MM_FRAMES"); dir != "" {
					saveFrame(t, dir, fmt.Sprintf("compact-%t-%dx%d.png", large, size[0], size[1]), tt.Image())
				}
			}
		})
	}
}
