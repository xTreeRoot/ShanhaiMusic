package app

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

// A child process runs the real AppKit event loop, not an offscreen tester.
// Its data directory is the temporary one installed by TestMain.
func TestNativeVideoWindowLifecycle(t *testing.T) {
	if os.Getenv("MB_NATIVE_WINDOW_TESTS") != "1" {
		t.Skip("native windows require explicit opt-in; normal tests stay offscreen")
	}
	if runtime.GOOS != "darwin" {
		t.Skip("AppKit lifecycle")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^$")
	cmd.Env = append(os.Environ(), "MB_NATIVE_VIDEO_TEST=1")
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("native video windows: %v\n%s", err, output)
	}
	t.Log(string(output))
}

func runNativeVideoWindowTest() int {
	data, err := os.ReadFile("testdata/mv.mp4")
	if err != nil {
		fmt.Println(err)
		return 1
	}
	gate := make(chan struct{})
	requested := make(chan struct{}, 16)
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case requested <- struct{}{}:
		default:
		}
		select {
		case <-gate:
		case <-r.Context().Done():
			return
		}
		http.ServeContent(w, r, "mv.mp4", time.Time{}, bytes.NewReader(data))
	}))
	defer remote.Close()
	var a *app
	result := make(chan error, 1)
	mygo.App.WhenReady(func() {
		a = newTestApp()
		a.pl = player.New(a.post)
		a.pl.Source = a.sourceByID
		a.pl.Picked = a.playbackPicked
		a.pl.SetVolume(0)
		src := &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) { return &music.VideoStream{URL: remote.URL}, nil }}
		src.caps["video"] = true
		src.stream = func(string, string) (*music.Stream, error) {
			file, err := filepath.Abs("../player/testdata/seek.mp3")
			return &music.Stream{URL: "file://" + filepath.ToSlash(file), Type: "mp3"}, err
		}
		a.library.src = src
		a.pl.Cue([]music.Track{videoSong("native", "Native video window")})
		a.pl.Hold(0)
		a.pl.Changed = a.syncPlaybackExtras
		a.win = mygo.NewWindow(mygo.WindowOptions{Title: "MB native regression", Width: 1180, Height: 780, Content: ui.View(a.view)})
		go func() {
			err := exerciseNativeVideoWindows(a, gate, requested)
			result <- err
			mygo.RunOnMain(func() { a.quitting = true; a.pl.ClearSession(); a.mv.release(); a.trayVideo.release(); mygo.App.Quit() })
		}()
	})
	if err = mygo.App.Run(); err != nil {
		fmt.Println(err)
		return 1
	}
	if err = <-result; err != nil {
		fmt.Println(err)
		return 1
	}
	fmt.Println("native mode switch, player buttons, loading, main-window response, close/reopen, shared decoder and next track passed")
	return 0
}

func exerciseNativeVideoWindows(a *app, gate chan struct{}, requested <-chan struct{}) error {
	// Every native operation and model read is serialized on AppKit's main
	// thread. The timeout belongs to the parent, so a deadlocked loop is caught.
	check := func(fn func() error) error {
		var err error
		mygo.RunOnMain(func() { err = fn() })
		return err
	}
	waitFor := func(name string, fn func() bool) error {
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			ready := false
			mygo.RunOnMain(func() { ready = fn() })
			if ready {
				return nil
			}
			time.Sleep(10 * time.Millisecond)
		}
		return fmt.Errorf("timeout: %s", name)
	}
	click := func(label string) error {
		return check(func() error {
			tt := ui.NewTester(a.view, 1180, 780)
			if err := tt.Click(label); err != nil {
				return err
			}
			tt.Frame()
			return nil
		})
	}
	if err := click("Open MV Mini Player"); err != nil {
		return err
	}
	if err := waitFor("mini player button", func() bool { return a.mv.win != nil && !a.mv.large && a.mv.video != nil }); err != nil {
		return err
	}
	if err := check(func() error {
		a.mv.win.Close()
		return nil
	}); err != nil {
		return err
	}
	if err := click("Video Mode"); err != nil {
		return err
	}
	if err := waitFor("video window", func() bool { return a.mv.win != nil && a.mv.video != nil }); err != nil {
		return err
	}
	select {
	case <-requested:
	case <-time.After(5 * time.Second):
		return fmt.Errorf("no remote video request")
	}
	// With the network blocked, render another window, browse and close video.
	// Prior to the metadata fix this callback cannot run until bytes arrive.
	if err := check(func() error {
		if !a.mv.large || a.mv.inline || a.route.page != pageHome {
			return fmt.Errorf("video did not use a separate window")
		}
		tt := ui.NewTester(a.view, 1180, 780)
		if err := tt.Click("Home"); err != nil {
			return err
		}
		tt.Frame()
		a.mv.win.Close()
		if a.mv.win != nil || a.mv.video != nil || a.settings.PlaybackMode != playbackVideo {
			return fmt.Errorf("closing the loading window did not release video")
		}
		if !a.pl.Playing() {
			return fmt.Errorf("closing video interrupted audio")
		}
		return nil
	}); err != nil {
		return err
	}
	if err := check(func() error {
		a.pl.PlayTrack([]music.Track{videoSong("native", "Native video window")}, 0)
		return nil
	}); err != nil {
		return err
	}
	if err := waitFor("reopened window", func() bool { return a.mv.win != nil && a.mv.video != nil }); err != nil {
		return err
	}
	close(gate)
	if err := waitFor("decoded native picture", func() bool { return a.mv.video != nil && a.mv.video.Ready() }); err != nil {
		return err
	}
	if err := check(func() error {
		win, decoder := a.mv.win, a.mv.video
		a.choosePlaybackMode(playbackMusic)
		a.choosePlaybackMode(playbackVideo)
		a.post(func() {
			if a.mv.win != win || a.mv.video != decoder {
				a.mv.err = "rapid mode switch discarded the active player"
			}
		})
		return nil
	}); err != nil {
		return err
	}
	if err := waitFor("rapid mode switch", func() bool { return !a.mv.openPending && a.mv.err == "" }); err != nil {
		return err
	}
	var videoWindow *mygo.Window
	var decoder *ui.VideoPlayer
	if err := check(func() error {
		videoWindow = a.mv.win
		decoder = a.mv.video.SetLoop(true)
		a.nowPlaying = true
		a.syncInlineMV()
		a.redraw()
		if a.mv.inline {
			return fmt.Errorf("main window stole independent decoder")
		}
		return nil
	}); err != nil {
		return err
	}
	for _, size := range []struct {
		width, height int
		name          string
	}{{520, 320, "compact"}, {1180, 740, "expanded"}} {
		if err := check(func() error {
			if !videoWindow.IsResizable() {
				return fmt.Errorf("video window is not resizable")
			}
			videoWindow.SetContentSize(size.width, size.height)
			bounds := videoWindow.ContentBounds()
			if bounds.Width != size.width || bounds.Height != size.height || a.mv.video != decoder {
				return fmt.Errorf("resize to %s: bounds %+v or decoder changed", size.name, bounds)
			}
			return nil
		}); err != nil {
			return err
		}
		if dir := os.Getenv("MM_FRAMES"); dir != "" {
			png, err := videoWindow.CapturePage()
			if err != nil {
				return err
			}
			if err = os.WriteFile(filepath.Join(dir, "native-video-"+size.name+".png"), png, 0o644); err != nil {
				return err
			}
		}
	}
	if err := check(func() error {
		a.nowPlaying = false
		a.pl.Cue([]music.Track{videoSong("native", "Native video window"), videoSong("next", "Next video")})
		a.pl.Next()
		return nil
	}); err != nil {
		return err
	}
	if err := waitFor("next video", func() bool {
		return a.pl.Ready() && !a.pl.Loading() && a.mv.key == "fake\x00next" && a.mv.video != nil && a.mv.video.Ready()
	}); err != nil {
		return err
	}
	if err := check(func() error {
		if a.mv.win != videoWindow || a.route.page != pageHome {
			return fmt.Errorf("track change replaced the window or browsing page")
		}
		return nil
	}); err != nil {
		return err
	}
	if err := check(func() error { a.mv.win.Close(); return nil }); err != nil {
		return err
	}
	if err := click("Video Playback"); err != nil {
		return err
	}
	if err := waitFor("video mode player button", func() bool { return a.mv.win != nil && a.mv.large && a.mv.video != nil && a.mv.video.Ready() }); err != nil {
		return err
	}
	if err := click("Music Mode"); err != nil {
		return err
	}
	return waitFor("closed video mode", func() bool { return a.mv.win == nil && a.mv.video == nil && !a.mv.inline && a.pl.Playing() })
}
