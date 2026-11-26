package app

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

// communityAsk is a page of the community's plugins asked for.
type communityAsk struct {
	query string
	page  int
}

// fakeCommunity has the plugins on GitHub answer with answer, keeping what was
// asked, which a test reads once the answer has been posted.
func fakeCommunity(t *testing.T, answer func(query string, page int) (*plugin.CommunityPage, error)) *[]communityAsk {
	t.Helper()
	var asked []communityAsk
	was := fetchCommunity
	t.Cleanup(func() { fetchCommunity = was })
	fetchCommunity = func(query string, page int) (*plugin.CommunityPage, error) {
		asked = append(asked, communityAsk{query, page})
		return answer(query, page)
	}
	return &asked
}

// listings answers with list, a page with nothing after it.
func listings(list ...plugin.Listing) func(string, int) (*plugin.CommunityPage, error) {
	return func(string, int) (*plugin.CommunityPage, error) { return &plugin.CommunityPage{Listings: list}, nil }
}

// openCommunity clicks Plugins in the sidebar and then the Community tab.
func openCommunity(t *testing.T, tt *ui.Tester) {
	t.Helper()
	for _, label := range []string{"Plugins", "Community"} {
		if err := tt.Click(label); err != nil {
			t.Fatal(err)
		}
		tt.Frame()
	}
}

// Plugins in the sidebar opens on the plugins installed, and its Community tab
// lists those on GitHub, asking it once in a while: Install on one downloads it
// to ask about, installing nothing by itself, and one installed already has no
// Install.
func TestCommunityInstallsAPlugin(t *testing.T) {
	asked := fakeCommunity(t, listings(plugin.Listing{Repo: "O/GH", Name: "gh"}, plugin.Listing{Repo: "x/radio", Name: "radio"}))
	fetched := make(chan string, 1)
	was := fetchPlugin
	defer func() { fetchPlugin = was }()
	fetchPlugin = func(repo, dir string) (*plugin.Pending, error) {
		fetched <- repo
		return &plugin.Pending{Release: plugin.Release{Repo: repo}, Manifest: plugin.Manifest{ID: "radio", Name: "Radio"}}, nil
	}
	a := newTestApp()
	addFake(a, githubPlugin(t))
	libs := len(a.allLibs)
	ch := posts(a)
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()

	if err := tt.Click("Plugins"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.route.page != pagePlugins || a.route.id != "" {
		t.Fatalf("on %+v", a.route)
	}
	if len(*asked) != 0 {
		t.Error("asked GitHub before the Community tab showed")
	}
	if err := tt.Click("Community"); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	tt.Frame()
	if _, ok := tt.Find("Install gh"); ok {
		t.Error("offers to install a plugin that is, from the same repository written otherwise")
	}
	if err := tt.Click("Install radio"); err != nil {
		t.Fatal(err)
	}
	if got := wait(t, fetched, "the download"); got != "x/radio" {
		t.Fatalf("downloaded %q", got)
	}
	if a.route.page != pagePlugins || a.route.id != communityTab {
		t.Errorf("left the tab for %+v", a.route)
	}
	runPost(t, ch)
	if !a.installer.open || a.installer.pending == nil || a.installer.pending.Manifest.ID != "radio" {
		t.Fatalf("asks about %v", a.installer.pending)
	}
	if len(a.allLibs) != libs {
		t.Error("installed it without asking")
	}
	tt.Frame()
	if err := tt.Click("Cancel"); err != nil {
		t.Fatal(err)
	}
	settled(tt)

	// Back from Home goes to the tab it was on.
	a.open(route{page: pageHome})
	tt.Frame()
	a.back()
	tt.Frame()
	if a.route.id != communityTab {
		t.Errorf("back went to %+v", a.route)
	}
	if len(*asked) != 1 {
		t.Errorf("asked GitHub %v, coming back to the page", *asked)
	}
	settled(tt)
}

// The plugins failing to load, Retry asks GitHub again and lists them.
func TestCommunityRetries(t *testing.T) {
	failed := false
	asked := fakeCommunity(t, func(q string, page int) (*plugin.CommunityPage, error) {
		if !failed {
			failed = true
			return nil, errors.New("GitHub says to slow down")
		}
		return listings(plugin.Listing{Repo: "x/radio", Name: "radio"})(q, page)
	})
	a := newTestApp()
	ch := posts(a)
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	openCommunity(t, tt)
	runPost(t, ch)
	tt.Frame()
	if err := tt.Click("Retry"); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	tt.Frame()
	if len(*asked) != 2 {
		t.Errorf("asked GitHub %v", *asked)
	}
	if _, ok := tt.Find("Install radio"); !ok {
		t.Error("doesn't list the plugins once they load")
	}
	settled(tt)
}

// Installing a plugin from the Community tab shows it on the Installed tab,
// marked, until the listener leaves the tab.
func TestInstallShowsThePlugin(t *testing.T) {
	fakeCommunity(t, listings(plugin.Listing{Repo: "x/radio", Name: "radio"}))
	src := t.TempDir()
	os.WriteFile(filepath.Join(src, "plugin.json"), []byte(`{"id":"shown","name":"Shown"}`), 0o644)
	os.WriteFile(filepath.Join(src, "index.js"), []byte(""), 0o644)
	was := fetchPlugin
	defer func() { fetchPlugin = was }()
	fetchPlugin = func(_, dir string) (*plugin.Pending, error) { return plugin.Open(src, dir) }
	a := newTestApp()
	ch := posts(a)
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	openCommunity(t, tt)
	runPost(t, ch)
	tt.Frame()
	if err := tt.Click("Install radio"); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	settled(tt)
	if err := tt.Click("Install Shown"); err != nil {
		t.Fatalf("%v: %q", err, tt.Texts())
	}
	until(t, ch, func() bool { return a.installer.marked == "shown" })
	tt.Frame()
	if a.route != (route{page: pagePlugins, src: "fake"}) {
		t.Errorf("shows %+v, not the Installed tab", a.route)
	}
	if _, ok := tt.Find("Enable Shown"); !ok {
		t.Errorf("the plugin isn't on the tab: %q", tt.Texts())
	}

	if err := tt.Click("Community"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.installer.marked != "" {
		t.Error("still marked after leaving the tab")
	}
	settled(tt)
}

// A plugin installed below the fold scrolls into view, clear of the player bar.
// Scrolled as little as showed it, it ended at the window's foot, under the bar.
func TestInstalledScrollsIntoView(t *testing.T) {
	a := newTestApp()
	for i := range 10 {
		s := newFakeSource(fmt.Sprintf("p%d", i))
		s.info.Name = fmt.Sprintf("P%d", i)
		s.info.Settings = []music.Setting{{Key: "k", Label: "Key"}, {Key: "r", Label: "Region", Help: fmt.Sprintf("Help %d", i)}}
		addFake(a, s)
	}
	a.enabledChanged()
	last := a.allLibs[len(a.allLibs)-1]
	a.route = route{page: pagePlugins, src: "fake", id: communityTab}
	const h = 800
	tt := ui.NewTester(a.view, 1200, h)
	settled(tt)
	a.showInstalled(last.id)
	settled(tt)
	head, ok1 := tt.Find("Enable " + last.name())
	foot, ok2 := tt.Find("Settings for " + last.name())
	if !ok1 || !ok2 {
		t.Fatalf("the card isn't there: %q", tt.Texts())
	}
	top := float32(topBarHeight)
	if head.Y < top || foot.Y+foot.H > h-playerBarHeight {
		t.Errorf("the card runs from %v to %v, outside %v to %v", head.Y, foot.Y+foot.H, top, h-playerBarHeight)
	}
}

// Coming to the end of the Community tab's plugins loads their next page, until
// GitHub says there are no more.
func TestCommunityPages(t *testing.T) {
	asked := fakeCommunity(t, func(_ string, page int) (*plugin.CommunityPage, error) {
		if page == 1 {
			return &plugin.CommunityPage{Listings: []plugin.Listing{{Repo: "x/a", Name: "a"}, {Repo: "x/b", Name: "b"}}, More: true}, nil
		}
		return &plugin.CommunityPage{Listings: []plugin.Listing{{Repo: "x/c", Name: "c"}}}, nil
	})
	a := newTestApp()
	ch := posts(a)
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	openCommunity(t, tt)
	runPost(t, ch)
	tt.Frame()
	runPost(t, ch)
	settled(tt)
	if want := []communityAsk{{"", 1}, {"", 2}}; !slices.Equal(*asked, want) {
		t.Errorf("asked for %v", *asked)
	}
	if _, ok := tt.Find("Install c"); !ok || len(a.community.list) != 3 {
		t.Errorf("lists %d plugins", len(a.community.list))
	}
}

// Enter in the Community tab's field searches GitHub for what's written, not each
// letter; emptied, the list is of all the plugins again. An answer to a search
// that another took the place of is dropped, as it was asked for first.
func TestCommunitySearch(t *testing.T) {
	asked := fakeCommunity(t, func(q string, _ int) (*plugin.CommunityPage, error) {
		switch q {
		case "":
			return &plugin.CommunityPage{Listings: []plugin.Listing{{Repo: "x/a", Name: "a"}, {Repo: "x/b", Name: "b"}}}, nil
		case "radio":
			return &plugin.CommunityPage{Listings: []plugin.Listing{{Repo: "x/radio", Name: "radio"}}}, nil
		}
		return &plugin.CommunityPage{}, nil
	})
	a := newTestApp()
	ch := posts(a)
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	openCommunity(t, tt)
	runPost(t, ch)
	tt.Frame()

	if err := tt.Click("Search Plugins"); err != nil {
		t.Fatal(err)
	}
	tt.Type("radio")
	tt.Frame()
	if len(*asked) != 1 {
		t.Fatalf("searched as it was typed: %v", *asked)
	}
	tt.Key(0, ui.KeyEnter)
	tt.Frame()
	runPost(t, ch)
	tt.Frame()
	if _, ok := tt.Find("Install radio"); !ok {
		t.Fatalf("doesn't list what it found: %q", tt.Texts())
	}
	if _, ok := tt.Find("Install a"); ok {
		t.Error("still lists what it didn't find")
	}

	// Emptied, it lists them all again; searching before that answers, the
	// answer about them all is dropped.
	if err := tt.Click("Clear Search"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	tt.Type("zzz")
	tt.Key(0, ui.KeyEnter)
	tt.Frame()
	runPost(t, ch)
	runPost(t, ch)
	settled(tt)
	if want := []communityAsk{{"", 1}, {"radio", 1}, {"", 1}, {"zzz", 1}}; !slices.Equal(*asked, want) {
		t.Errorf("asked for %v", *asked)
	}
	if _, ok := tt.Find("No plugins found for “zzz”"); !ok || len(a.community.list) != 0 {
		t.Errorf("lists %d plugins, saying %q", len(a.community.list), tt.Texts())
	}
}

// With MM_FRAMES set, saves the Plugins page, one of its plugins installed.
func TestCommunityFrame(t *testing.T) {
	dir := framesDir(t)
	a := newTestApp()
	addFake(a, githubPlugin(t))
	a.community.list = []plugin.Listing{
		{Repo: "o/gh", Name: "gh", Owner: "o", Avatar: "cover:0", Description: "Songs, albums and playlists from GH, with lyrics and comments.", Stars: 1280},
		{Repo: "x/radio", Name: "radio", Owner: "x", Avatar: "cover:1", Description: "Thousands of internet radio stations.", Stars: 42},
		{Repo: "y/podcasts", Name: "podcasts", Owner: "y", Avatar: "cover:2", Stars: 3},
	}
	for i, c := range [][2]ui.Color{{ui.RGB(40, 90, 170), ui.RGB(220, 120, 160)}, {ui.RGB(30, 140, 100), ui.RGB(230, 200, 90)}, {ui.RGB(120, 60, 160), ui.RGB(240, 150, 80)}} {
		a.art.art[sized(fmt.Sprintf("cover:%d", i), 108)] = &artwork{bmp: ui.NewBitmap(coverImage(c[0], c[1]))}
	}
	a.community.paged.loaded = true
	a.route = route{page: pagePlugins, src: "fake", id: communityTab}
	tt := ui.NewTester(a.view, 1180, 780)
	settled(tt)
	saveFrame(t, dir, "community.png", tt.Image())
	a.showInstalled("gh")
	settled(tt)
	saveFrame(t, dir, "installed.png", tt.Image())
	a.selectPluginSettings("gh")
	settled(tt)
	saveFrame(t, dir, "details.png", tt.Image())
}
