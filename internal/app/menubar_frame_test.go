package app

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/i18n"
)

func menuBarImage(t *testing.T, b []byte) image.Image {
	t.Helper()
	img, err := png.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	return img
}

// Short, long, Latin and empty lines share one exact native image width.
func TestMenuBarFixedViewport(t *testing.T) {
	var renderer menuBarRenderer
	width := 240
	expected := image.Rect(0, 0, (menuBarStyle{}).imageWidth(width)*menuBarScale, menuBarHeightPoints*menuBarScale)
	for _, text := range []string{"短句", "i.W 演出红尘一响，凡人又要再等一周！", strings.Repeat("很长的歌词Mixed i.W! ", 6), ""} {
		b, changed := renderer.frame(text, 0, width, true, menuBarStyle{})
		if !changed {
			t.Fatal("sentence change did not refresh the frame")
		}
		img := menuBarImage(t, b)
		if img.Bounds() != expected {
			t.Fatalf("%q resized the tray: %v", text, img.Bounds())
		}
		// Transparent padding must reserve width without painting a rectangle on the menu bar.
		_, _, _, alpha := img.At(img.Bounds().Max.X-1, 0).RGBA()
		if alpha != 0 {
			t.Fatal("template background is not transparent")
		}
	}
	b, changed := renderer.frame("短句", time.Second, 320, true, menuBarStyle{})
	if !changed || menuBarImage(t, b).Bounds().Dx() != expected.Dx()+(320-width)*menuBarScale {
		t.Fatal("width adjustment did not resize the viewport")
	}
	if _, changed = renderer.frame("短句", time.Hour, 320, true, menuBarStyle{}); changed {
		t.Fatal("stationary short sentence caused redundant native updates")
	}
	b, changed = renderer.frame("短句", 0, 320, false, menuBarStyle{})
	if !changed || !bytes.Equal(b, trayTemplate) {
		t.Fatal("disabling lyrics did not restore the icon")
	}
	if _, changed = renderer.frame("短句", time.Second, 320, false, menuBarStyle{}); changed {
		t.Fatal("disabled lyrics kept redrawing")
	}
}

// Pixel motion stays smooth for mixed scripts; seeking is deterministic and paused frames are reused.
func TestMenuBarPixelMarquee(t *testing.T) {
	full := strings.Repeat("这是长歌词Mixed i.W! ", 5)
	var renderer menuBarRenderer
	first, _ := renderer.frame(full, 0, 160, true, menuBarStyle{})
	frozen := bytes.Clone(first)
	next, changed := renderer.frame(full, menuBarScrollHold+menuBarFrameInterval, 160, true, menuBarStyle{})
	if !changed || bytes.Equal(next, frozen) {
		t.Fatal("long sentence did not move")
	}
	if renderer.offset < 1 || renderer.offset > 3 {
		t.Fatalf("first frame jumped %d pixels", renderer.offset)
	}
	if _, changed = renderer.frame(full, menuBarScrollHold+menuBarFrameInterval, 160, true, menuBarStyle{}); changed {
		t.Fatal("paused playhead redrew the same frame")
	}
	back, changed := renderer.frame(full, 0, 160, true, menuBarStyle{})
	if !changed || !bytes.Equal(back, frozen) {
		t.Fatal("seek did not restore the same pixels")
	}
	for i := range 150 {
		b, _ := renderer.frame(full, menuBarScrollHold+time.Duration(i+2)*menuBarFrameInterval, 160, true, menuBarStyle{})
		if len(b) > 0 && menuBarImage(t, b).Bounds() != menuBarImage(t, frozen).Bounds() {
			t.Fatal("scroll resized the tray")
		}
	}
	if menuBarOffset(20, 100, time.Hour) != 0 {
		t.Fatal("short text should stand still")
	}
}

// Invalid or legacy values use a bounded width, and the chosen width survives restart.
func TestMenuBarWidthSettings(t *testing.T) {
	for _, tc := range []struct {
		value float64
		want  int
	}{{0, 240}, {-2, 240}, {20, 80}, {1000, 600}, {321, 321}} {
		if got := menuBarWidth(tc.value); got != tc.want {
			t.Fatalf("width %v became %v", tc.value, got)
		}
	}
	s := defaultSettings()
	s.MenuBarLyricsWidth = 320
	s.path = filepath.Join(t.TempDir(), "settings.json")
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(s.path)
	if err != nil {
		t.Fatal(err)
	}
	var loaded settings
	if err = json.Unmarshal(b, &loaded); err != nil {
		t.Fatal(err)
	}
	if loaded.MenuBarLyricsWidth != 320 {
		t.Fatal("configured width was not saved")
	}
}

// The actual settings input applies once and persists through a rebuild.
func TestMenuBarWidthInput(t *testing.T) {
	a := newTestApp()
	a.settingsQuery = "Menu Bar Lyrics Width"
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	tt := ui.NewTester(a.settingsView, 860, 700)
	tt.Frame()
	if err := tt.Click("Increase"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.settings.MenuBarLyricsWidth != 250 {
		t.Fatalf("input left width at %v", a.settings.MenuBarLyricsWidth)
	}
	b, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var saved settings
	if err = json.Unmarshal(b, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.MenuBarLyricsWidth != 250 {
		t.Fatal("UI width was not saved")
	}
}

// With MM_FRAMES, renders consecutive lyric changes against a dark bar.
func TestMenuBarFixedFrames(t *testing.T) {
	dir := framesDir(t)
	texts := []string{"短句", "演出红尘一响，凡人又要再等一周！", "Mixed i.W 中英混排，状态栏的宽度保持固定直到手动修改"}
	var renderer menuBarRenderer
	sheet := image.NewRGBA(image.Rect(0, 0, 600, 300))
	draw.Draw(sheet, sheet.Bounds(), image.NewUniform(color.RGBA{R: 30, G: 35, B: 40, A: 255}), image.Point{}, draw.Src)
	for i, text := range texts {
		b, _ := renderer.frame(text, 2*time.Second, 240, true, menuBarStyle{dark: true})
		template := menuBarImage(t, b)
		tinted := image.NewRGBA(template.Bounds())
		draw.DrawMask(tinted, tinted.Bounds(), image.NewUniform(color.White), image.Point{}, template, image.Point{}, draw.Src)
		draw.Draw(sheet, image.Rect(30, 25+i*90, 30+tinted.Bounds().Dx(), 25+i*90+tinted.Bounds().Dy()), tinted, image.Point{}, draw.Over)
	}
	saveFrame(t, dir, "menubar-fixed-width.png", sheet)
}

// With MM_FRAMES, inspect the new settings controls at desktop and minimum widths.
func TestMenuBarSettingsFrames(t *testing.T) {
	dir := framesDir(t)
	before := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(before)
	for _, tc := range []struct {
		name  string
		width int
	}{{"desktop", 860}, {"narrow", 640}} {
		a := newTestApp()
		a.settingsQuery = "状态栏"
		tt := ui.NewTester(a.settingsView, tc.width, 700)
		settled(tt)
		saveFrame(t, dir, "menubar-settings-"+tc.name+".png", tt.Image())
	}
}
