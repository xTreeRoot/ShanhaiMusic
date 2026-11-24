package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

// A mode switch changes only the preference. Even playback restoration and
// track/transport updates must not turn it into an open-player button.
func TestVideoModeSwitchAndBrowse(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	a.home = &music.Browse{Sections: []music.Section{{Title: "Recommended", Tracks: framesTracks(a), TrackLayout: music.TrackLayoutVideos}}}
	a.pl.Cue([]music.Track{videoSong("1", "First")})
	a.pl.Hold(42 * time.Second)
	current, position := a.pl.Current(), a.pl.Position()
	a.pl.Changed = a.syncPlaybackExtras
	tt := ui.NewTester(a.view, 1180, 780)
	settled(tt)
	for _, label := range []string{"Video Mode", "Music Mode", "Video Mode"} {
		if err := tt.Click(label); err != nil {
			t.Fatal(err)
		}
		tt.Frame()
		a.syncPlaybackExtras()
		if a.mv.openPending || a.mv.loading || a.mv.video != nil || a.mv.win != nil {
			t.Fatal("switching preference opened or loaded video")
		}
		if a.route.page != pageHome || a.pl.Current() != current || a.pl.Position() != position || !a.pl.Playing() {
			t.Fatal("switching preference navigated or restarted audio")
		}
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		settled(tt)
		saveFrame(t, dir, "mode-preference-video.png", tt.Image())
	}
	a.pl.Restore(player.Session{Queue: []music.Track{videoSong("saved", "Saved")}, Order: []int{0}, At: 0, Position: 9000})
	tt.Frame()
	if a.mv.openPending || a.mv.loading || a.pl.Playing() {
		t.Fatal("restoring a session started video")
	}
	a.pl.TogglePlay()
	a.syncPlaybackExtras()
	tt.Frame()
	if a.mv.openPending {
		t.Fatal("resuming a session opened a video window")
	}
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var saved settings
	if err = json.Unmarshal(data, &saved); err != nil || saved.PlaybackMode != playbackVideo {
		t.Fatal("mode was not saved", err)
	}
}

// Both homepage video cards and ordinary track rows use the explicit selection
// signal, including clicking the same item again. Music-mode clicks stay audio.
func TestVideoModeContentClicks(t *testing.T) {
	for _, card := range []bool{true, false} {
		t.Run(fmt.Sprintf("card-%t", card), func(t *testing.T) {
			a := newTestApp()
			ch := posts(a)
			tracks := []music.Track{videoSong("pick", "Chosen item")}
			view := func(c *ui.Context) {
				ui.Column(c).Fill().Children(func() {
					a.playbackModeSwitch(c)
					if card {
						a.videoTrackRow(c, &music.Section{Tracks: tracks}, browseRow{from: 0, to: 1}, 1)
					} else {
						a.trackRow(c, tracks, 0, false, "")
					}
				})
			}
			tt := ui.NewTester(view, 640, 580)
			settled(tt)
			if err := tt.Click("Video Mode"); err != nil {
				t.Fatal(err)
			}
			tt.Frame()
			if a.mv.openPending || a.pl.Current() != nil {
				t.Fatal("mode switch started playback")
			}
			for range 2 {
				if err := tt.Click("Chosen item"); err != nil {
					t.Fatal(err)
				}
				tt.Frame()
				if !a.mv.openPending || a.pl.Current() == nil || a.pl.Current().ID != "pick" {
					t.Fatal("content click did not select video playback")
				}
				until(t, ch, func() bool { return !a.mv.openPending }) // no native window in this offscreen test
			}
			if err := tt.Click("Music Mode"); err != nil {
				t.Fatal(err)
			}
			tt.Frame()
			if err := tt.Click("Chosen item"); err != nil {
				t.Fatal(err)
			}
			tt.Frame()
			if a.mv.openPending || !a.pl.Playing() || a.pl.Current().ID != "pick" {
				t.Fatal("music mode content click did not stay audio")
			}
		})
	}
}

// A pending user selection is cancelled by switching modes; switching back
// must not revive it without a new content click. Closing keeps the preference.
func TestVideoModeCancelsPendingAndKeepsPreference(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.choosePlaybackMode(playbackVideo)
	a.pl.PlayTrack([]music.Track{videoSong("1", "First")}, 0)
	if !a.mv.openPending {
		t.Fatal("selection did not queue a video window")
	}
	a.choosePlaybackMode(playbackMusic)
	a.choosePlaybackMode(playbackVideo)
	for len(ch) > 0 {
		runPost(t, ch)
	}
	if a.mv.openPending || a.mv.win != nil {
		t.Fatal("mode switch revived an old selection")
	}
	a.pl.Hold(42 * time.Second)
	current, position := a.pl.Current(), a.pl.Position()
	win := new(mygo.Window)
	a.mv.win, a.mv.large = win, true
	a.closedMV(win)
	if a.settings.PlaybackMode != playbackVideo || a.mv.openPending || a.mv.win != nil {
		t.Fatal("closing the player changed the preference or reopened it")
	}
	if a.pl.Current() != current || a.pl.Position() != position || !a.pl.Playing() {
		t.Fatal("closing the video restarted audio")
	}
	a.pl.PlayTrack([]music.Track{videoSong("1", "First")}, 0)
	if !a.mv.openPending {
		t.Fatal("next explicit click did not reopen video")
	}
	a.choosePlaybackMode(playbackMusic)
	for len(ch) > 0 {
		runPost(t, ch)
	}
}

// Switching to music discards an in-flight video and keeps audio/position.
func TestMusicModeRejectsLateVideo(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	asked, release := make(chan string, 1), make(chan struct{})
	src := &mvSource{fakeSource: fake(a), video: func(id, _ string) (*music.VideoStream, error) {
		asked <- id
		<-release
		return nil, errors.New("late video")
	}}
	src.caps["video"] = true
	a.library.src = src
	a.pl.Cue([]music.Track{videoSong("1", "First")})
	a.pl.Hold(42 * time.Second)
	a.nowPlaying, a.mv.inline = true, true
	a.loadMV("fake\x001")
	wait(t, asked, "video")
	current, position := a.pl.Current(), a.pl.Position()
	a.choosePlaybackMode(playbackMusic)
	close(release)
	runPost(t, ch)
	if a.mv.inline || a.mv.loading || a.mv.video != nil || a.mv.err != "" || a.settings.NowPlayingMedia != nowPlayingCover {
		t.Fatal("music mode retained video or accepted its late response")
	}
	if a.pl.Current() != current || a.pl.Position() != position || !a.pl.Playing() {
		t.Fatal("music mode interrupted audio")
	}
}

func TestVideoWindowEmptyAndUnsupported(t *testing.T) {
	a := newTestApp()
	a.mv.large, a.mv.inline = true, true
	a.loadMV("")
	tt := ui.NewTester(a.videoWindowView, 880, 580)
	settled(tt)
	if !tt.HasText("Choose a track to watch from Home or your library.") {
		t.Fatal("missing empty state")
	}
	a.pl.Cue([]music.Track{videoSong("audio", "Audio only")})
	a.loadMV("fake\x00audio")
	tt.Frame()
	if !tt.HasText("This source does not provide video playback.") || a.mv.loading {
		t.Fatal("unsupported source started loading")
	}
}

// The window's view exercises native decoding, shared audio controls, seeking,
// description/comments switching and independent narrow/wide layouts.
func TestVideoWindowNativePlayback(t *testing.T) {
	data, err := os.ReadFile("testdata/mv.mp4")
	if err != nil {
		t.Fatal(err)
	}
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "mv.mp4", time.Time{}, bytes.NewReader(data))
	}))
	defer remote.Close()
	a := newTestApp()
	ch := posts(a)
	a.pl = player.New(a.post)
	a.pl.Source = a.sourceByID
	src := &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) { return &music.VideoStream{URL: remote.URL}, nil }}
	src.caps["video"] = true
	a.library.src = src
	a.pl.Cue([]music.Track{videoSong("1", "Video playback")})
	a.pl.Current().Description = "A separate playback window keeps the main library available."
	a.pl.Changed = a.syncMV
	a.mv.large, a.mv.inline = true, true
	a.loadMV("fake\x001")
	until(t, ch, func() bool { return !a.mv.loading })
	p := a.mv.video
	if p == nil {
		t.Fatal(a.mv.err)
	}
	defer func() { a.quitting = true; a.mv.release() }()
	tt := ui.NewTester(a.videoWindowView, 1180, 740)
	deadline := time.Now().Add(8 * time.Second)
	for !p.Ready() {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatal("native picture did not decode")
		}
		time.Sleep(10 * time.Millisecond)
	}
	p.SetLoop(true) // short fixture only
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()
	a.syncMV()
	tt.Move(400, 380)
	settled(tt)
	if !p.Muted() || !p.Playing() {
		t.Fatal("picture did not follow audio")
	}
	if err = tt.Click("Play/Pause"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if p.Playing() || a.pl.Playing() {
		t.Fatal("pause did not stop both")
	}
	a.mv.lastSeek = time.Time{}
	target := p.Duration() / 2
	a.pl.Seek(target)
	until(t, ch, func() bool { return absDuration(out.Position()-target) < 100*time.Millisecond })
	deadline = time.Now().Add(4 * time.Second)
	for absDuration(p.Position()-a.pl.Position()) > 100*time.Millisecond {
		pumpMVRunLoop()
		tt.Frame()
		if time.Now().After(deadline) {
			t.Fatalf("seek: video %v audio %v", p.Position(), a.pl.Position())
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err = tt.Click("Play"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !p.Playing() || !a.pl.Playing() {
		t.Fatal("resume did not start both")
	}
	tt.Key(0, ui.KeySpace)
	tt.Frame()
	if p.Playing() || a.pl.Playing() {
		t.Fatal("Space did not pause both")
	}
	if err = tt.Click("Comments"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	until(t, ch, func() bool { return a.comments.loaded })
	tt.Frame()
	if !tt.HasText("No comments yet") {
		t.Fatal("comments did not load")
	}
	if err = tt.Click("About"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText(a.pl.Current().Description) {
		t.Fatal("description did not return")
	}
	old := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(old)
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		tt.SetSize(520, 320)
		settled(tt)
		saveFrame(t, dir, "video-window-520.png", tt.Image())
	}
	for _, width := range []int{880, 1180, 1500} {
		tt.SetSize(width, 740)
		settled(tt)
		if r, ok := tt.Find(T("Music Video")); !ok || r.W < 500 || r.H < 300 {
			t.Fatalf("missing video at %d: %+v", width, r)
		}
		if dir := os.Getenv("MM_FRAMES"); dir != "" {
			saveFrame(t, dir, fmt.Sprintf("video-window-%d.png", width), tt.Image())
		}
	}
	current, position, playing := a.pl.Current(), a.pl.Position(), a.pl.Playing()
	a.choosePlaybackMode(playbackMusic)
	if a.mv.video != nil || a.mv.inline || a.pl.Current() != current || a.pl.Position() != position || a.pl.Playing() != playing {
		t.Fatal("music mode did not release the picture while preserving audio")
	}

}
