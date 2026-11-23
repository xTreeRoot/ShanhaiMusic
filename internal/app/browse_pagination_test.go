package app

import (
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func TestFollowingRowsLoadAtBottomRetryAndOpenCreator(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	calls := make(chan string, 8)
	attempts := 0
	fake(a).browse = func(page string) (*music.Browse, error) {
		calls <- page
		if page == "people:2" {
			attempts++
			if attempts == 1 {
				return nil, errors.New("temporary failure")
			}
		}
		start, count, next := 1, 24, "people:2"
		if page == "people:2" {
			start, count, next = 25, 2, "people:2"
		}
		links := make([]music.Link, count)
		for i := range links {
			n := start + i
			links[i] = music.Link{Title: fmt.Sprintf("Creator %d", n), Subtitle: strings.Repeat("Complete introduction ", 12), Page: fmt.Sprintf("creator:%d", n), ImageURL: fmt.Sprintf("https://example.test/%d.png", n)}
		}
		return &music.Browse{Title: "Following", Sections: []music.Section{{Links: links, MorePage: next}}}, nil
	}
	a.loadBrowse(a.library, "people", false)
	runPost(t, ch)
	if wait(t, calls, "first page") != "people" {
		t.Fatal("wrong first page")
	}
	a.route = route{page: pageBrowse, src: "fake", id: "people"}
	tt := ui.NewTester(a.view, 1000, 800)
	settled(tt)
	if _, ok := tt.Find("Creator 1"); !ok {
		t.Fatal("missing creator")
	}
	select {
	case id := <-calls:
		t.Fatalf("loaded before bottom: %s", id)
	default:
	}
	tt.Scroll(700, 400, 0, 100000)
	tt.Advance(time.Second)
	tt.Frame()
	tt.Frame()
	if wait(t, calls, "next page") != "people:2" {
		t.Fatal("wrong continuation")
	}
	runPost(t, ch)
	settled(tt)
	if err := tt.Click("Retry"); err != nil {
		t.Fatal(err)
	}
	if wait(t, calls, "retry") != "people:2" {
		t.Fatal("retry changed route")
	}
	runPost(t, ch)
	settled(tt)
	tt.Scroll(700, 400, 0, 100000)
	tt.Advance(time.Second)
	tt.Frame()
	tt.Frame()
	if err := tt.Click("Creator 26"); err != nil {
		t.Fatal(err)
	}
	if a.route.id != "creator:26" {
		t.Fatal(a.route)
	}
	if d := a.library.browses["people"]; d.next != "" || len(d.page.Sections) != 2 || d.page.Sections[1].Links[1].ImageURL != "https://example.test/26.png" {
		t.Fatal("lost avatar or repeated continuation")
	}
}

func TestPluginPlaylistTrackStartsFullQueueWithoutHeaderPlayAll(t *testing.T) {
	a := editTestApp()
	tt := ui.NewTester(a.view, 1200, 800)
	settled(tt)
	if err := tt.Click("宝船"); err != nil {
		t.Fatal(err)
	}
	if cur := a.pl.Current(); cur == nil || cur.ID != "2" || len(a.pl.Queue()) != 2 {
		t.Fatal("lost playlist queue")
	}
}

type paginatedTestSource struct {
	*fakeSource
	page func(string, int) (*music.Playlist, error)
}

func (src *paginatedTestSource) PlaylistPage(id string, offset int) (*music.Playlist, error) {
	return src.page(id, offset)
}

func TestPlaylistLoadsNextPageAtBottomAndKeepsScroll(t *testing.T) {
	a := newTestApp()
	lib := a.library
	ch := posts(a)
	calls := make(chan int, 8)
	tracks := make([]music.Track, 24)
	for index := range tracks {
		tracks[index] = song(fmt.Sprint(index+1), fmt.Sprintf("Favorite %d", index+1))
	}
	lib.src = &paginatedTestSource{fake(a), func(_ string, offset int) (*music.Playlist, error) {
		calls <- offset
		return &music.Playlist{ID: "folder", Tracks: []music.Track{song("25", "Favorite 25")}}, nil
	}}
	lib.playlists["folder"] = &music.Playlist{ID: "folder", Name: "Favorites", TrackCount: 25, More: true, NextOffset: 30, Tracks: tracks}
	lib.plAt["folder"] = time.Now()
	a.route = route{page: pagePlaylist, src: lib.id, id: "folder"}
	tt := ui.NewTester(a.view, 1000, 800)
	settled(tt)
	select {
	case offset := <-calls:
		t.Fatalf("prefetched before scroll: %d", offset)
	default:
	}
	tt.Scroll(700, 400, 0, 100000)
	tt.Advance(time.Second)
	tt.Frame()
	tt.Frame()
	if got := wait(t, calls, "playlist continuation"); got != 30 {
		t.Fatalf("lost plugin cursor: %d", got)
	}
	until(t, ch, func() bool { return len(lib.playlists["folder"].Tracks) == 25 })
	settled(tt)
	tt.Scroll(700, 400, 0, 100000)
	tt.Advance(time.Second)
	tt.Frame()
	if _, ok := tt.Find("Favorite 25"); !ok || lib.playlists["folder"].More {
		t.Fatal("final playlist row did not appear at bottom")
	}
}
