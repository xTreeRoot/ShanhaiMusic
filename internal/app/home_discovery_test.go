package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"os"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func discoveryHome(a *app) *music.Browse {
	tracks := framesTracks(a)
	return &music.Browse{Sections: []music.Section{
		{Title: "For You", TrackLayout: music.TrackLayoutVideos, Tracks: tracks[:6], MorePage: "recommended"},
		{Title: "Music Chart", TrackLayout: music.TrackLayoutVideos, Tracks: tracks[2:8], MorePage: "chart"},
	}}
}

// Video cards are direct playback actions at every supported window width.
func TestDiscoveryCardsPlaySectionQueue(t *testing.T) {
	for _, width := range []int{880, 1180, 1500} {
		t.Run(fmt.Sprint(width), func(t *testing.T) {
			a := newTestApp()
			a.home = discoveryHome(a)
			tt := ui.NewTester(a.view, width, 900)
			tt.Frame()
			if err := tt.Click("Slow Tide"); err != nil {
				t.Fatal(err)
			}
			if cur := a.pl.Current(); cur == nil || cur.ID != "2" {
				t.Fatalf("clicked card selected %+v", cur)
			}
			if queue := a.pl.Queue(); len(queue) != 6 || queue[5].ID != "6" {
				t.Fatalf("wrong section queue: %+v", queue)
			}
		})
	}
}

func TestDiscoveryPreviewsDoNotScrollHorizontally(t *testing.T) {
	for _, width := range []int{880, 1180, 1500} {
		t.Run(fmt.Sprint(width), func(t *testing.T) {
			a := newTestApp()
			tracks := make([]music.Track, 12)
			for index := range tracks {
				tracks[index] = music.Track{ID: fmt.Sprint(index + 1), Name: fmt.Sprintf("Preview track %d", index+1)}
			}
			fake(a).info.Discovery = []music.DiscoverySection{{ID: "recommended", Title: "Recommendations", Page: "recommended"}}
			a.home = &music.Browse{Sections: []music.Section{{Title: "Recommendations", Tracks: tracks, MorePage: "recommended"}}}
			tester := ui.NewTester(a.view, width, 880)
			settled(tester)
			if dir := os.Getenv("MM_FRAMES"); dir != "" && width == 1180 {
				saveFrame(t, dir, "desktop-discovery-ten-cards.png", tester.Image())
			}
			var cards [10]ui.Rect
			for index, track := range tracks {
				card, visible := tester.AccessByRole(ui.RoleButton, track.Name)
				if index >= len(cards) {
					if visible {
						t.Fatalf("showed an extra preview card: %s", track.Name)
					}
					continue
				}
				if !visible || card.Bounds.W <= 0 || card.Bounds.X+card.Bounds.W > float32(width) {
					t.Fatalf("preview card %s does not fit: %+v", track.Name, card.Bounds)
				}
				cards[index] = card.Bounds
			}
			for column := range 5 {
				if cards[column].Y != cards[0].Y || cards[column+5].Y != cards[5].Y || cards[column+5].X != cards[column].X || cards[column+5].Y <= cards[column].Y {
					t.Fatalf("preview cards are not aligned in two rows: %+v", cards)
				}
			}
			title, visible := tester.Access("Recommendations")
			if !visible || title.Bounds.X != cards[0].X {
				t.Fatalf("section title does not align with the grid: %+v, %+v", title.Bounds, cards[0])
			}
			tester.Scroll(cards[0].X+20, cards[0].Y+20, 640, 0)
			settled(tester)
			last, visible := tester.AccessByRole(ui.RoleButton, tracks[9].Name)
			if !visible || last.Bounds.X != cards[9].X {
				t.Fatalf("horizontal scrolling moved the preview cards: %+v", last.Bounds)
			}
			if err := tester.Click(tracks[9].Name); err != nil {
				t.Fatal(err)
			}
			if current := a.pl.Current(); current == nil || current.ID != tracks[9].ID || len(a.pl.Queue()) != len(tracks) {
				t.Fatal("preview limit changed the playback queue")
			}
		})
	}
}

func TestDiscoverySeeAll(t *testing.T) {
	a, ch, asked := browseApp()
	fake(a).info.Discovery = []music.DiscoverySection{{ID: "recommended", Title: "For You", Page: "recommended"}}
	a.home = discoveryHome(a)
	tt := ui.NewTester(a.view, 1180, 780)
	tt.Frame()
	if err := tt.Click("See All"); err != nil {
		t.Fatal(err)
	}
	if got := wait(t, asked, "discovery page"); got != "recommended" {
		t.Fatalf("requested %q", got)
	}
	runPost(t, ch)
	tt.Frame()
	if a.route.page != pageBrowse || a.route.id != "recommended" {
		t.Fatalf("opened %+v", a.route)
	}
}

// Refresh does not start duplicate requests or discard the current page on a
// network failure, and another click can retry after the failed request ends.
func TestHomeRefreshKeepsPageAndRetries(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.home = discoveryHome(a)
	release := make(chan struct{})
	called := make(chan struct{}, 2)
	fake(a).home = func() (*music.Browse, error) {
		called <- struct{}{}
		<-release
		return nil, errors.New("network unavailable")
	}
	tt := ui.NewTester(a.view, 1180, 780)
	tt.Frame()
	if err := tt.Click(T("Refresh")); err != nil {
		t.Fatal(err)
	}
	wait(t, called, "refresh")
	if !a.homeLoading {
		t.Fatal("not loading")
	}
	tt.Frame()
	if n, ok := tt.AccessByRole(ui.RoleButton, T("Refresh")); !ok || !n.Disabled {
		t.Fatal("refresh is still enabled")
	}
	a.loadHome(a.library)
	if len(called) != 0 {
		t.Fatal("duplicate refresh")
	}
	close(release)
	runPost(t, ch)
	tt.Frame()
	if a.home == nil || a.homeErr == "" || a.homeLoading {
		t.Fatal("refresh discarded the page or hid the error")
	}
	fake(a).home = func() (*music.Browse, error) {
		return &music.Browse{Sections: []music.Section{{Title: "Updated", TrackLayout: music.TrackLayoutVideos, Tracks: []music.Track{song("new", "New video")}}}}, nil
	}
	if err := tt.Click(T("Refresh")); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	tt.Frame()
	if a.homeErr != "" || a.home.Sections[0].Title != "Updated" {
		t.Fatal("retry did not replace the page")
	}
}

// MM_FRAMES optionally uses the public discovery response from a live probe.
func TestDiscoveryHomeFrame(t *testing.T) {
	dir := framesDir(t)
	a := newTestApp()
	a.home = discoveryHome(a)
	if path := os.Getenv("MB_HOME_FIXTURE"); path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(data, &a.home); err != nil {
			t.Fatal(err)
		}
		// Fetch covers before rendering; the UI test never performs network IO.
		for _, sec := range a.home.Sections {
			for _, track := range sec.Tracks {
				data, err := music.Fetch(track.Album.ImageURL)
				if err != nil {
					continue
				}
				img, _, err := image.Decode(bytes.NewReader(data))
				if err == nil {
					rgba := image.NewRGBA(img.Bounds())
					draw.Draw(rgba, rgba.Bounds(), img, img.Bounds().Min, draw.Src)
					giveCover(a, track.Album.ImageURL, rgba)
				}
			}
		}
	}
	for _, width := range []int{880, 1180, 1500} {
		tt := ui.NewTester(a.view, width, 900)
		settled(tt)
		saveFrame(t, dir, fmt.Sprintf("home-discovery-%d.png", int(width)), tt.Image())
		if width == 1180 {
			tt.SetDark(true)
			settled(tt)
			saveFrame(t, dir, "home-discovery-dark.png", tt.Image())
		}
	}
}
