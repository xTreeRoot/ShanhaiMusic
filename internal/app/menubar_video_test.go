package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"image"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/mbvideo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

// A failed startup preview can retry when Play reloads the same track's media.
func TestMenuBarVideoRetriesSameTrack(t *testing.T) {
	a := newTestApp()
	a.tray = new(mygo.Tray)
	ch := posts(a)
	requests := make(chan string, 4)
	src := &mvSource{fakeSource: fake(a), video: func(id, _ string) (*music.VideoStream, error) {
		requests <- id
		return nil, errors.New("connection failed")
	}}
	src.caps["video"] = true
	a.library.src = src
	a.pl = player.New(a.post)
	a.pl.Source = a.sourceByID
	s := player.Session{Queue: []music.Track{videoSong("1", "Saved")}, Order: []int{0}, At: 0, Position: 2000}
	a.pl.Restore(s)
	a.syncMenuBarVideo()
	if id := wait(t, requests, "startup video"); id != "1" {
		t.Fatalf("asked for %q", id)
	}
	until(t, ch, func() bool { return !a.trayVideo.loading })
	a.syncMenuBarVideo()
	select {
	case <-requests:
		t.Fatal("failed preview retried continuously")
	default:
	}
	a.pl.TogglePlay()
	a.syncMenuBarVideo()
	if id := wait(t, requests, "same-track retry"); id != "1" {
		t.Fatalf("asked for %q", id)
	}
	until(t, ch, func() bool { return !a.trayVideo.loading })
	a.quitting = true
	a.trayVideo.release()
}

// A slow platform answer cannot recreate a disabled preview or attach to a skipped track.
func TestMenuBarVideoRejectsStaleLoad(t *testing.T) {
	for _, disable := range []bool{true, false} {
		t.Run(map[bool]string{true: "disabled", false: "skipped"}[disable], func(t *testing.T) {
			a := newTestApp()
			a.tray = new(mygo.Tray)
			started, release := make(chan struct{}), make(chan struct{})
			src := &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) {
				close(started)
				<-release
				return &music.VideoStream{URL: "https://media.example/video.mp4"}, nil
			}}
			src.caps["video"] = true
			a.library.src = src
			a.pl.Cue([]music.Track{videoSong("1", "First")})
			ch := posts(a)
			a.syncMenuBarVideo()
			select {
			case <-started:
			case <-time.After(time.Second):
				t.Fatal("preview was not requested")
			}
			if disable {
				a.settings.MenuBarVideo = false
				a.syncMenuBarVideo()
			} else {
				a.pl.Cue([]music.Track{videoSong("2", "Second")})
			}
			close(release)
			runPost(t, ch)
			if a.trayVideo.video != nil || a.trayVideo.proxy != nil {
				t.Fatal("stale preview was installed")
			}
			if disable && a.trayVideo.loading {
				t.Fatal("disabled preview kept loading")
			}
			a.trayVideo.release()
		})
	}
}

// A real system decoder supplies moving color frames before the fixed lyric
// viewport and follows the same audio pause/resume/seek commands as the main MV.
func TestMenuBarNativeVideo(t *testing.T) {
	data, err := os.ReadFile("testdata/mv.mp4")
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "mv.mp4", time.Time{}, bytes.NewReader(data))
	}))
	defer server.Close()
	proxy, err := newMVProxy(&music.VideoStream{URL: server.URL})
	if err != nil {
		t.Fatal(err)
	}
	p, err := video.OpenURL(proxy.url)
	if err != nil {
		proxy.Close()
		t.Fatal(err)
	}
	p.SetMuted(true)
	p.Pause()
	a := newTestApp()
	a.tray = new(mygo.Tray)
	a.library.src = &mvSource{fakeSource: fake(a)}
	a.library.src.(*mvSource).caps["video"] = true
	a.pl.Cue([]music.Track{videoSong("1", "First")})
	a.trayVideo.video, a.trayVideo.proxy, a.trayVideo.key = p, proxy, "fake\x001"
	a.trayVideo.track = a.pl.Current()
	defer func() { a.quitting = true; a.trayVideo.release() }()
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	a.pl.Attach(out)
	defer out.Close()
	// Start paused so the first output can be compared against a later seek.
	a.pl.TogglePlay()
	out.Pause()
	deadline := time.Now().Add(6 * time.Second)
	for !p.Ready() {
		pumpMVRunLoop()
		a.syncMenuBarVideo()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatal("status bar video did not decode")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !p.Muted() || p.Playing() || a.trayVideo.picture == nil {
		t.Fatal("preview did not follow paused muted audio")
	}
	// A settled pause does not keep scheduling or redrawing native frames.
	a.trayVideo.refreshUntil = time.Time{}
	if a.trayVideo.needsFrames() {
		t.Fatal("settled pause kept scheduling frames")
	}
	frozen := a.trayVideo.picture
	a.syncMenuBarVideo()
	if frozen != a.trayVideo.picture {
		t.Fatal("settled pause redrew its frame")
	}
	first := bytes.Clone(a.trayVideo.picture.Pix)
	a.trayVideo.lastSeek = time.Time{}
	a.pl.Seek(min(900*time.Millisecond, p.Duration()*3/4))
	a.syncMenuBarVideo()
	deadline = time.Now().Add(4 * time.Second)
	for absDuration(p.Position()-a.pl.Position()) > 250*time.Millisecond || bytes.Equal(a.trayVideo.picture.Pix, first) {
		if !a.trayVideo.needsFrames() && a.trayVideo.picture != nil {
			t.Fatal("paused seek stopped scheduling before its new frame")
		}
		pumpMVRunLoop()
		a.syncMenuBarVideo()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatalf("preview failed to seek: video=%v audio=%v", p.Position(), a.pl.Position())
		}
		time.Sleep(10 * time.Millisecond)
	}
	a.pl.Seek(0)
	a.trayVideo.lastSeek = time.Time{}
	a.pl.TogglePlay()
	a.syncMenuBarVideo()
	if !p.Playing() {
		t.Fatal("preview did not resume with audio")
	}
	resumed := bytes.Clone(a.trayVideo.picture.Pix)
	deadline = time.Now().Add(2 * time.Second)
	for bytes.Equal(resumed, a.trayVideo.picture.Pix) {
		pumpMVRunLoop()
		a.syncMenuBarVideo()
		if time.Now().After(deadline) {
			t.Fatal("resumed preview did not produce moving frames")
		}
		time.Sleep(10 * time.Millisecond)
	}
	a.pl.TogglePlay()
	out.Pause()
	a.syncMenuBarVideo()
	if p.Playing() {
		t.Fatal("preview kept playing while audio paused")
	}
	b, changed := a.trayLyrics.frame("演出红尘一响，凡人又要再等一周！", 0, 240, true, menuBarStyle{video: true, dark: true, preview: a.trayVideo.picture})
	if !changed {
		t.Fatal("video did not reach the status bar image")
	}
	combined := menuBarImage(t, b)
	if combined.Bounds().Dx() != (menuBarStyle{video: true}).imageWidth(240)*menuBarScale {
		t.Fatal("video changed lyric viewport width")
	}
	colored := false
	for y := range menuBarHeightPoints * menuBarScale {
		for x := range menuBarVideoPoints * menuBarScale {
			r, g, b, _ := combined.At(x, y).RGBA()
			if r != g || g != b {
				colored = true
			}
		}
	}
	if !colored {
		t.Fatal("status bar preview was converted to a monochrome icon")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "menubar-video.png", combined)
	}
	// Turning it off releases both decoding and the media proxy immediately.
	oldURL := proxy.url
	a.settings.MenuBarVideo = false
	a.syncMenuBarVideo()
	if a.trayVideo.video != nil || a.trayVideo.picture != nil {
		t.Fatal("disabled video retained its decoder or frame")
	}
	res, e := http.Get(oldURL)
	if e == nil {
		io.Copy(io.Discard, res.Body)
		res.Body.Close()
		t.Fatal("disabled preview kept serving media")
	}
}

// Video loading, failures, and new frames keep the left slot and lyric viewport fixed.
func TestMenuBarVideoWidthAndAppearance(t *testing.T) {
	const width = 240
	const expected = (width + menuBarVideoPoints + menuBarIconGap) * menuBarScale
	var renderer menuBarRenderer
	preview := image.NewRGBA(image.Rect(0, 0, 64, 36))
	for _, style := range []menuBarStyle{{video: true}, {video: true, preview: preview}, {video: true, dark: true, preview: preview}} {
		b, _ := renderer.frame("short", 0, width, true, style)
		if menuBarImage(t, b).Bounds().Dx() != expected {
			t.Fatal("preview loading or appearance resized the status item")
		}
	}
}

// Settings expose the preview switch independently of the lyric viewport width.
func TestMenuBarVideoToggle(t *testing.T) {
	a := newTestApp()
	a.settingsQuery = "Menu Bar Video"
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	tt := ui.NewTester(a.settingsView, 860, 700)
	tt.Frame()
	clickSettingRole(t, tt, ui.RoleSwitch, "Menu Bar Video")
	tt.Frame()
	if a.settings.MenuBarVideo {
		t.Fatal("video switch did not disable preview")
	}
	if a.settings.MenuBarLyricsWidth != menuBarDefaultWidth {
		t.Fatal("video switch changed lyric width")
	}
	b, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var restored settings
	if err = json.Unmarshal(b, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.MenuBarVideo {
		t.Fatal("preview toggle was not persisted")
	}
}
