package app

import (
	"os"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func TestPlaylistResumeReopensSavedTrackAndPosition(t *testing.T) {
	mygo.App.SetPath(mygo.PathUserData, t.TempDir())
	t.Cleanup(func() { mygo.App.SetPath(mygo.PathUserData, "") })
	a := editTestApp()
	tt := ui.NewTester(a.view, 1200, 800)
	settled(tt)
	if err := tt.Click("宝船"); err != nil {
		t.Fatal(err)
	}
	a.pl.Seek(23 * time.Second)
	a.saveSession()

	reopened := editTestApp()
	reopened.loadPlaylistResumes()
	view := ui.NewTester(reopened.view, 1200, 800)
	settled(view)
	name := T("Last played")
	if err := view.Click(name); err != nil {
		t.Fatal(err)
	}
	if track := reopened.pl.Current(); track == nil || track.ID != "2" || reopened.pl.Position() != 23*time.Second {
		t.Fatalf("resume did not restore track and position: %+v at %v", track, reopened.pl.Position())
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "playlist-resume.png", view.Image())
	}
}

func TestBrowseTrackListResumesThroughLaterPage(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.route = route{page: pageBrowse, src: "fake", id: "daily"}
	first := music.Track{Source: "fake", ID: "1", Name: "First daily song"}
	last := music.Track{Source: "fake", ID: "2", Name: "Last daily song"}
	fake(a).browse = func(id string) (*music.Browse, error) {
		return &music.Browse{Sections: []music.Section{{Tracks: []music.Track{last}}}}, nil
	}
	a.browses["daily"] = &browseData{
		page: &music.Browse{Title: "Daily recommendations", Sections: []music.Section{{Tracks: []music.Track{first, last}}}},
	}
	view := ui.NewTester(a.view, 1200, 800)
	settled(view)
	if err := view.Click(last.Name); err != nil {
		t.Fatal(err)
	}
	a.pl.Seek(9 * time.Second)
	a.recordPlaylistResume()
	data := a.browses["daily"]
	data.page.Sections[0].Tracks = []music.Track{first}
	data.next, data.visited = "daily:2", map[string]bool{"daily": true}
	data.loading = true // Keep the view's automatic foot paging out of this action.
	settled(view)
	if err := view.Click(T("Last played")); err != nil {
		t.Fatal(err)
	}
	until(t, ch, func() bool { return a.playlistResume.loading == "" })
	if current := a.pl.Current(); current == nil || current.ID != last.ID || a.pl.Position() != 9*time.Second || len(a.pl.Queue()) != 2 {
		t.Fatalf("did not resume the saved track after paging: %+v at %s", current, a.pl.Position())
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		settled(view)
		saveFrame(t, dir, "daily-recommendations-resume.png", view.Image())
	}
}

func TestBrowseTrackListsRememberSeparatePositions(t *testing.T) {
	a := newTestApp()
	track := music.Track{Source: "fake", ID: "shared", Name: "Shared song"}
	for _, id := range []string{"staff-picks", "saved-mix"} {
		a.browses[id] = &browseData{page: &music.Browse{
			Title: "Mix", Sections: []music.Section{{Tracks: []music.Track{track}}},
		}}
	}
	a.route = route{page: pageBrowse, src: "fake", id: "staff-picks"}
	view := ui.NewTester(a.view, 1200, 800)
	play := func(id string, position time.Duration) {
		t.Helper()
		a.route.id = id
		settled(view)
		if err := view.Click(track.Name); err != nil {
			t.Fatal(err)
		}
		a.pl.Seek(position)
		a.recordPlaylistResume()
	}
	play("staff-picks", 12*time.Second)
	play("saved-mix", 39*time.Second)
	for _, list := range []struct {
		id       string
		position time.Duration
	}{{"staff-picks", 12 * time.Second}, {"saved-mix", 39 * time.Second}} {
		a.route.id = list.id
		settled(view)
		if err := view.Click(T("Last played")); err != nil {
			t.Fatal(err)
		}
		if got := a.pl.Position(); got != list.position {
			t.Fatalf("%s resumed at %s, expected %s", list.id, got, list.position)
		}
	}
}
