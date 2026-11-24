package player

import (
	"testing"

	"mygo-music/internal/music"
)

func TestPagedQueueAutoAdvancesAfterPendingPage(t *testing.T) {
	p := New(func(func()) {})
	first := music.Track{ID: "one", Source: "fixture"}
	second := music.Track{ID: "two", Source: "fixture"}
	p.Cue([]music.Track{first})
	p.Hold(0)
	var complete func([]music.Track, bool, error)
	p.pageFeed = func(got func([]music.Track, bool, error)) { complete = got }
	p.refill()
	p.ended()
	if complete == nil || !p.Loading() || p.Current().ID != "one" {
		t.Fatal("did not wait on the existing prefetch")
	}
	complete([]music.Track{first, second}, false, nil)
	if p.Current().ID != "two" || len(p.Queue()) != 2 || p.hasFeed() {
		t.Fatal("did not advance to the new track or stop pagination at the last page")
	}
	p.ended()
	if p.Playing() || p.Current().ID != "two" {
		t.Fatal("repeat off must stop at the real end")
	}
}

func TestReplacementQueueIgnoresOldPage(t *testing.T) {
	p := New(func(func()) {})
	var complete func([]music.Track, bool, error)
	p.PlayPaged([]music.Track{{ID: "one"}}, 0, func(got func([]music.Track, bool, error)) { complete = got })
	p.PlayTrack([]music.Track{{ID: "replacement"}}, 0)
	complete([]music.Track{{ID: "late"}}, false, nil)
	if len(p.Queue()) != 1 || p.Current().ID != "replacement" {
		t.Fatal("late page changed a replacement queue")
	}
}

func TestQueuePageRequestAppendsWithoutChangingSelection(t *testing.T) {
	player := New(func(func()) {})
	first := music.Track{ID: "one"}
	player.Cue([]music.Track{first, {ID: "two"}, {ID: "three"}})
	requests := 0
	var complete func([]music.Track, bool, error)
	player.pageFeed = func(got func([]music.Track, bool, error)) {
		requests++
		complete = got
	}
	player.refill()
	if requests != 0 {
		t.Fatal("prefetched while far from the end of playback")
	}
	player.LoadNextQueuePage()
	player.LoadNextQueuePage()
	if requests != 1 {
		t.Fatalf("requested %d pages for the same in-flight cursor", requests)
	}
	complete([]music.Track{first, {ID: "four"}}, false, nil)
	if len(player.Queue()) != 4 || player.Queue()[3].ID != "four" || player.Current().ID != first.ID || player.Playing() {
		t.Fatalf("bad queue after loading: %+v, current %+v", player.Queue(), player.Current())
	}
}
