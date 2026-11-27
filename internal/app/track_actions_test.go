package app

import (
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
	"os"
	"testing"
)

func TestRowActionsRemoveFromFixedPlatformFolderWithoutPlaying(t *testing.T) {
	a := editTestApp()
	ch := posts(a)
	fake(a).info.PlaylistKind = "folder"
	a.mine[0].Fixed, a.mine[0].Removable = true, true
	a.playlists["10"].Fixed, a.playlists["10"].Removable = true, true
	removed := make(chan string, 1)
	fake(a).removeFrom = func(pid string, ids []string) error { removed <- pid + ":" + ids[0]; return nil }
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	if err := tt.Click("Actions for 宝船"); err != nil {
		t.Fatal(err)
	}
	if a.pl.Current() != nil {
		t.Fatal("opening the row menu played the song")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "folder-row-menu.png", tt.Image())
	}
	if err := tt.ChooseMenuItem("Remove from This Favorite Folder"); err != nil {
		t.Fatal(err)
	}
	if got := wait(t, removed, "native folder removal"); got != "10:2" {
		t.Fatal(got)
	}
	runPost(t, ch)
	tt.Frame()
	if len(a.playlists["10"].Tracks) != 1 {
		t.Fatal("native removal did not update current folder")
	}
}

func TestDirectTrackActionsAcrossDesktopSurfacesDoNotStartPlayback(t *testing.T) {
	for _, surface := range []string{"queue", "daily", "episode", "video"} {
		t.Run(surface, func(t *testing.T) {
			a := newTestApp()
			track := song("track", "Track action fixture")
			original := copyText
			defer func() { copyText = original }()
			copied := ""
			copyText = func(value string) error { copied = value; return nil }
			view := func(c *ui.Context) {
				switch surface {
				case "queue":
					a.playbackListRow(c, &track, false, func() { t.Fatal("menu started playback") })
				case "daily":
					a.dailyPicks(c, []music.Track{track}, "fixture", false)
				case "episode":
					a.episodeRow(c, &showData{show: &music.Show{}, episodes: []music.Track{track}}, 0, nil)
				case "video":
					a.videoTrackCard(c, &track, func() { t.Fatal("menu started video") })
				}
			}
			tt := ui.NewTester(view, 600, 400)
			tt.Frame()
			if err := tt.Click("Actions for Track action fixture"); err != nil {
				t.Fatal(err)
			}
			if err := tt.ChooseMenuItem("Copy Title"); err != nil {
				t.Fatal(err)
			}
			if copied != track.Name || a.pl.Current() != nil {
				t.Fatal("wrong track or unintended playback")
			}
		})
	}
}

func TestLikedListRowRemovalAlwaysSendsUnlike(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.account = &music.Account{ID: "one"}
	a.route = route{page: pageLiked, src: "fake"}
	track := song("saved", "Saved song")
	a.likedList = []music.Track{track}
	a.likedPaged.loaded = true
	// The list may be stale while a recent platform query already says false.
	a.rememberLike(track.ID, false)
	sent := make(chan bool, 1)
	fake(a).like = func(_ string, on bool) error { sent <- on; return nil }
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	if err := tt.Click("Actions for Saved song"); err != nil {
		t.Fatal(err)
	}
	if err := tt.ChooseMenuItem("Remove from Liked Songs"); err != nil {
		t.Fatal(err)
	}
	if wait(t, sent, "explicit unlike") {
		t.Fatal("remove toggled an unliked song on")
	}
	runPost(t, ch)
	tt.Frame()
	if len(a.likedList) != 0 {
		t.Fatal("removed song stayed in liked list")
	}
}
