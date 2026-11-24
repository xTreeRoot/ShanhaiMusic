package app

import (
	"fmt"
	"os"
	"testing"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

func TestVideoWindowFooterTransport(t *testing.T) {
	a := newTestApp()
	a.mv.large = true
	a.mv.err = "Video unavailable"
	a.pl.Cue([]music.Track{videoSong("one", "First clip"), videoSong("two", "Second clip")})
	tester := ui.NewTester(a.mvView, 520, 320)
	settled(tester)
	right := float32(0)
	for _, label := range []string{"Shuffle", "Previous", "Play/Pause", "Next", "Repeat", "Like", "Volume", "Full Screen MV"} {
		bounds, ok := tester.Find(label)
		if !ok || bounds.X < right || bounds.X+bounds.W > 520 || bounds.Y < 270 || bounds.Y+bounds.H > 320 {
			t.Fatalf("%s not in the compact footer after x=%v: %v, found=%v", label, right, bounds, ok)
		}
		right = bounds.X + bounds.W
	}
	click := func(label string) {
		t.Helper()
		if err := tester.Click(label); err != nil {
			t.Fatal(err)
		}
	}
	click("Next")
	if a.pl.Current().ID != "two" || !a.pl.Playing() {
		t.Fatal("next did not start the second track")
	}
	click("Play/Pause")
	if a.pl.Playing() {
		t.Fatal("play/pause did not pause")
	}
	click("Play/Pause")
	if !a.pl.Playing() {
		t.Fatal("play/pause did not resume")
	}
	click("Previous")
	if a.pl.Current().ID != "one" {
		t.Fatal("previous did not return to the first track")
	}
	click("Shuffle")
	if !a.pl.Shuffle() || a.pl.Current().ID != "one" {
		t.Fatal("shuffle did not toggle while keeping the current track")
	}
	click("Shuffle")
	if a.pl.Shuffle() {
		t.Fatal("shuffle did not turn off")
	}
	for _, want := range []player.RepeatMode{player.RepeatAll, player.RepeatOne, player.RepeatOff} {
		click("Repeat")
		if got := a.pl.Repeat(); got != want {
			t.Fatalf("repeat mode = %v, want %v", got, want)
		}
	}
}

func TestVideoWindowSidebarResizeAndQueue(t *testing.T) {
	a := newTestApp()
	a.mv.large = true
	a.pl.Cue([]music.Track{videoSong("one", "First clip"), videoSong("two", "Queued clip")})
	tester := ui.NewTester(a.videoWindowView, 1040, 560)
	resize := func(width, height int, visible bool) {
		t.Helper()
		tester.SetSize(width, height)
		settled(tester)
		_, shown := tester.Find("Queued clip")
		if shown != visible {
			t.Fatalf("%dx%d: queue visible = %v, want %v", width, height, shown, visible)
		}
	}
	click := func(label string) {
		t.Helper()
		if err := tester.Click(label); err != nil {
			t.Fatal(err)
		}
		settled(tester)
	}
	resize(1040, 560, true)
	resize(1039, 640, false)
	resize(1120, 559, false)
	resize(520, 320, false)
	resize(1120, 640, true)
	click("Hide Sidebar")
	resize(1120, 640, false)
	resize(800, 480, false)
	resize(1120, 640, false)
	click("Show Sidebar")
	resize(1120, 640, true)
	click("Queued clip")
	if current := a.pl.Current(); current.ID != "two" || len(a.pl.Queue()) != 2 {
		t.Fatalf("queue click: current %v, queue %v", current, a.pl.Queue())
	}
}

type videoAuthorRequest struct {
	id     string
	offset int
	reply  chan *music.ArtistPage
}

type videoAuthorSource struct {
	*fakeSource
	requests chan videoAuthorRequest
}

func (source *videoAuthorSource) ArtistPage(id string, offset int) (*music.ArtistPage, error) {
	reply := make(chan *music.ArtistPage, 1)
	source.requests <- videoAuthorRequest{id, offset, reply}
	return <-reply, nil
}

func videoAuthorTester() (*app, *ui.Tester, chan func(), chan videoAuthorRequest) {
	a := newTestApp()
	callbacks := posts(a)
	requests := make(chan videoAuthorRequest, 8)
	a.library.src = &videoAuthorSource{fake(a), requests}
	a.mv.large = true
	current := videoSong("current", "Current video")
	current.Artists = []music.Artist{{ID: "author-a", Name: "Author A"}}
	current.Description = "A description above the author works."
	a.pl.Cue([]music.Track{current})
	tester := ui.NewTester(a.videoWindowView, 1180, 650)
	settled(tester)
	return a, tester, callbacks, requests
}

func TestVideoWindowAuthorPagination(t *testing.T) {
	a, tester, callbacks, requests := videoAuthorTester()
	if err := tester.Click("About"); err != nil {
		t.Fatal(err)
	}
	respond := func(offset int, page *music.ArtistPage) {
		t.Helper()
		request := wait(t, requests, "artist page")
		if request.id != "author-a" || request.offset != offset {
			t.Errorf("artist request = %s/%d, want author-a/%d", request.id, request.offset, offset)
		}
		request.reply <- page
		runPost(t, callbacks)
		settled(tester)
	}
	var first []music.Track
	for index := range 16 {
		first = append(first, videoSong(fmt.Sprint(index), fmt.Sprintf("Work %02d", index)))
	}
	respond(0, &music.ArtistPage{Tracks: first, More: true, NextOffset: 25})
	if !tester.HasText(a.pl.Current().Description) {
		t.Fatal("works replaced the description")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "video-author-works.png", tester.Image())
	}
	tester.Wheel(1040, 420, 0, 6000)
	settled(tester)
	respond(25, &music.ArtistPage{Empty: "Temporarily unavailable"})
	tester.Wheel(1040, 420, 0, 6000)
	settled(tester)
	if err := tester.Click("Retry"); err != nil {
		t.Fatal(err)
	}
	respond(25, &music.ArtistPage{Tracks: []music.Track{first[15], videoSong("last", "Final work")}})
	if state := a.mv.author; len(state.tracks) != 17 || state.more || state.tracks[0].ID != "0" {
		t.Fatalf("pagination lost or duplicated works: %+v", state.tracks)
	}
	tester.Wheel(1040, 420, 0, 6000)
	settled(tester)
	if err := tester.Click("Final work"); err != nil {
		t.Fatal(err)
	}
	if a.pl.Current().ID != "last" || len(a.pl.Queue()) != 17 {
		t.Fatalf("author work did not play in its queue: %v", a.pl.Current())
	}
}

func TestVideoWindowRejectsPreviousAuthorPage(t *testing.T) {
	a, tester, callbacks, requests := videoAuthorTester()
	if err := tester.Click("About"); err != nil {
		t.Fatal(err)
	}
	previous := wait(t, requests, "previous author")
	next := videoSong("another", "Another video")
	next.Artists = []music.Artist{{ID: "author-b", Name: "Author B"}}
	a.pl.Cue([]music.Track{next})
	settled(tester)
	current := wait(t, requests, "current author")
	if current.id != "author-b" || current.offset != 0 {
		t.Errorf("wrong author: %s/%d", current.id, current.offset)
	}
	current.reply <- &music.ArtistPage{Tracks: []music.Track{videoSong("new", "New author work")}}
	runPost(t, callbacks)
	previous.reply <- &music.ArtistPage{Tracks: []music.Track{videoSong("old", "Old author work")}}
	runPost(t, callbacks)
	settled(tester)
	if !tester.HasText("New author work") || tester.HasText("Old author work") {
		t.Fatal("the previous author overwrote the current one")
	}
}
