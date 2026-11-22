package app

import (
	"context"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/discoveryprefs"
	"mygo-music/internal/music"
	"slices"
	"testing"
)

func TestDiscoverySelectionControlsRequestsAndOrder(t *testing.T) {
	a := newTestApp()
	f := fake(a)
	f.info.Discovery = []music.DiscoverySection{{ID: "one", Title: "One", Page: "one"}, {ID: "two", Title: "Two", Page: "two"}, {ID: "three", Title: "Three", Page: "three"}}
	calls := []string{}
	f.browse = func(id string) (*music.Browse, error) {
		calls = append(calls, id)
		return &music.Browse{Sections: []music.Section{{Tracks: []music.Track{song(id, id)}}}}, nil
	}
	selection := discoveryprefs.Selection{Order: []string{"three", "two", "one"}, Enabled: map[string]bool{"one": true, "two": false, "three": true}}
	page, err := loadDiscovery(context.Background(), f, selection)
	if err != nil || !slices.Equal(calls, []string{"three", "one"}) || len(page.Sections) != 2 || page.Sections[0].Title != "Three" {
		t.Fatal(calls, page, err)
	}
	selection.Enabled = map[string]bool{}
	page, err = loadDiscovery(context.Background(), f, selection)
	if err != nil || len(page.Sections) != 0 || len(calls) != 2 {
		t.Fatal("disabled sections were requested")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	selection.Enabled["one"] = true
	if _, err = loadDiscovery(ctx, f, selection); err == nil || len(calls) != 2 {
		t.Fatal("canceled loading continued", err)
	}
}

func TestDiscoveryEditorCancelAndSaveControlsLoading(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	f := fake(a)
	f.info.Discovery = []music.DiscoverySection{{ID: "one", Title: "One", Page: "one"}, {ID: "two", Title: "Two", Page: "two"}}
	a.discoveryPrefs = discoveryprefs.NewStore(t.TempDir())
	a.discoveryDocument = discoveryprefs.Empty()
	a.allLibs = []*library{a.library}
	calls := make(chan string, 4)
	f.browse = func(id string) (*music.Browse, error) {
		calls <- id
		return &music.Browse{Sections: []music.Section{{Tracks: []music.Track{song(id, id)}}}}, nil
	}
	a.settingsPage = "plugins"
	tt := ui.NewTester(a.settingsView, 940, 780)
	settled(tt)
	if err := tt.Click(T("Display Settings")); err != nil {
		t.Fatal(err)
	}
	settled(tt)
	if err := tt.Click("One"); err != nil {
		t.Fatal(err)
	}
	if a.discoveryEditor.draft.Enabled["one"] {
		t.Fatal("toggle did not edit draft")
	}
	if err := tt.Click(T("Cancel")); err != nil {
		t.Fatal(err)
	}
	if len(calls) != 0 {
		t.Fatal("cancel requested content")
	}
	a.openDiscoveryEditor(a.library)
	settled(tt)
	if err := tt.Click("One"); err != nil {
		t.Fatal(err)
	}
	from, found := tt.Find("Reorder Two")
	if !found {
		t.Fatal("missing reorder handle")
	}
	to, found := tt.Find("Reorder One")
	if !found {
		t.Fatal("missing reorder target")
	}
	tt.Press(from.X+from.W-8, from.Y+8)
	tt.Move(from.X+from.W-8, from.Y-10)
	tt.Move(to.X+to.W-8, to.Y+8)
	tt.Release(to.X+to.W-8, to.Y+8)
	if a.discoveryEditor.draft.Order[0] != "two" {
		t.Fatal("drag did not reorder draft", a.discoveryEditor.draft.Order)
	}
	if err := tt.Click(T("Save")); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	if got := wait(t, calls, "enabled section"); got != "two" {
		t.Fatal(got)
	}
	runPost(t, ch)
	if len(calls) != 0 || len(a.home.Sections) != 1 || a.home.Sections[0].Title != "Two" {
		t.Fatal("saved selection not applied")
	}
	loaded, err := a.discoveryPrefs.Load()
	if err != nil || loaded.Resolve(a.library.id, discoveryDeclarations(f)).Enabled["one"] {
		t.Fatal("not persisted", err)
	}
}

func TestDiscoverySectionsFrames(t *testing.T) {
	dir := framesDir(t)
	a := newTestApp()
	f := fake(a)
	f.info.Discovery = []music.DiscoverySection{{ID: "one", Title: "第一栏目", Page: "one"}, {ID: "two", Title: "第二栏目", Page: "two"}}
	a.home = discoveryHome(a)
	a.home.Sections[0].TrackLayout = ""
	a.home.Sections[0].Title = "第一栏目"
	a.home.Sections[1].Title = "第二栏目"
	tt := ui.NewTester(a.view, 1180, 900)
	settled(tt)
	saveFrame(t, dir, "desktop-discovery-sections.png", tt.Image())
	a.openDiscoveryEditor(a.library)
	a.allLibs = []*library{a.library}
	a.settingsPage = "plugins"
	settings := ui.NewTester(a.settingsView, 1180, 900)
	settled(settings)
	saveFrame(t, dir, "desktop-discovery-editor.png", settings.Image())
}

func TestDiscoveryLegacyRoutesKeepStableIDsAndSkipDisabledRequests(t *testing.T) {
	a := newTestApp()
	f := fake(a)
	f.info.Navigation = []music.NavItem{{Title: "Public", Page: "public"}, {Title: "Personal", Page: "account", Placement: "personal"}, {Title: "Duplicate", Page: "public"}}
	definitions := discoveryDefinitions(f)
	if len(definitions) != 2 || definitions[1].Page != "public" {
		t.Fatal(definitions)
	}
	before := discoveryprefs.Empty().Resolve(f.Info().ID, discoveryDeclarations(f))
	f.info.Navigation[0].Title = "Renamed"
	after := discoveryprefs.Empty().Resolve(f.Info().ID, discoveryDeclarations(f))
	if !slices.Equal(before.Order, after.Order) {
		t.Fatal("display rename changed preference identity")
	}
	after.Enabled["home"] = false
	var calls []string
	f.browse = func(id string) (*music.Browse, error) {
		calls = append(calls, id)
		return &music.Browse{Sections: []music.Section{{Title: "Content"}}}, nil
	}
	page, err := loadDiscovery(context.Background(), f, after)
	if err != nil || !slices.Equal(calls, []string{"public"}) || len(page.Sections) != 1 {
		t.Fatal(calls, page, err)
	}
}
