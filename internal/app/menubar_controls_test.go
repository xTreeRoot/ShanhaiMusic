package app

import (
	"image"
	"image/color"
	"image/draw"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"mygo-music/internal/music"
)

func TestMenuBarControlClicks(t *testing.T) {
	a := newTestApp()
	a.pl.Cue([]music.Track{song("first", "First"), song("last", "Last")})
	width := 164
	style := menuBarStyle{video: true, transport: menuBarPlaying}
	a.trayLyrics.frame("歌词", 0, width, true, style)
	bounds := mygo.Rectangle{X: -350, Y: 24, Width: style.imageWidth(width) + 14, Height: 24}
	left := bounds.X + 7 + style.controlsStart(width)
	click := func(offset int) bool {
		action := a.trayLyrics.actionAt(bounds, mygo.Point{X: left + offset, Y: bounds.Y + 9})
		return a.runMenuBarAction(action)
	}
	if click(-1) || click(3*menuBarControlWidth) {
		t.Fatal("click outside the controls did not fall back to the menu")
	}
	if !click(0) || a.pl.Current().ID != "last" || !a.pl.Playing() {
		t.Fatal("previous control did not select the previous song")
	}
	if !click(menuBarControlWidth) || a.pl.Playing() {
		t.Fatal("middle control did not pause")
	}
	if !click(2*menuBarControlWidth-1) || !a.pl.Playing() {
		t.Fatal("middle control did not resume")
	}
	if !click(2*menuBarControlWidth) || a.pl.Current().ID != "first" {
		t.Fatal("next control did not select the next song")
	}
	a.trayLyrics.frame("", 0, width, false, style)
	if click(menuBarControlWidth) || !a.pl.Playing() {
		t.Fatal("disabled lyrics still captured control clicks")
	}
}

func TestMenuBarUsesNativeImageWidthForControlClicks(t *testing.T) {
	width := 224
	var renderer menuBarRenderer
	style := menuBarStyle{video: true, transport: menuBarPlaying}
	renderer.frame("歌词", 0, width, true, style)
	for _, scale := range []float64{0.75, 1, 1.25, 2} {
		nativeWidth := float64(style.imageWidth(width)) * scale
		for index, want := range []menuBarAction{menuBarPrevious, menuBarToggle, menuBarNext} {
			x := float64(style.controlsStart(width)+index*menuBarControlWidth+menuBarControlWidth/2) * scale
			if got := renderer.actionAtImage(x, nativeWidth); got != want {
				t.Fatalf("scale %.2f, control %d: got %v, want %v", scale, index, got, want)
			}
		}
		if renderer.actionAtImage(float64(style.controlsStart(width)-1)*scale, nativeWidth) != menuBarOpenMenu {
			t.Fatal("lyric click intercepted by a control")
		}
	}
}

func TestMenuBarControlsStayStillAndReflectPlayback(t *testing.T) {
	const width = 164
	var renderer menuBarRenderer
	style := menuBarStyle{dark: true, transport: menuBarPlaying}
	text := strings.Repeat("我不要你走 我不想放手 ", 5)
	first, _ := renderer.frame(text, 0, width, true, style)
	scrolled, changed := renderer.frame(text, 2*time.Second, width, true, style)
	if !changed {
		t.Fatal("fixture did not scroll")
	}
	style.transport = menuBarPaused
	paused, changed := renderer.frame(text, 2*time.Second, width, true, style)
	if !changed {
		t.Fatal("pausing without a lyric change did not update the icon")
	}
	firstImage, scrolledImage, pausedImage := menuBarImage(t, first), menuBarImage(t, scrolled), menuBarImage(t, paused)
	if firstImage.Bounds() != pausedImage.Bounds() {
		t.Fatal("paused state resized the tray")
	}
	middleChanged := false
	for y := range firstImage.Bounds().Dy() {
		for x := style.controlsStart(width) * menuBarScale; x < firstImage.Bounds().Dx(); x++ {
			if firstImage.At(x, y) != scrolledImage.At(x, y) {
				t.Fatal("transport controls scrolled with the lyrics")
			}
			if pausedImage.At(x, y) != scrolledImage.At(x, y) {
				control := (x/menuBarScale - style.controlsStart(width)) / menuBarControlWidth
				if control != 1 {
					t.Fatal("pausing repainted the previous or next icon")
				}
				middleChanged = true
			}
		}
	}
	if !middleChanged {
		t.Fatal("play and pause glyphs are identical")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		sheet := image.NewRGBA(image.Rect(0, 0, firstImage.Bounds().Dx()+48, 124))
		draw.Draw(sheet, sheet.Bounds(), image.NewUniform(color.RGBA{R: 45, G: 40, B: 37, A: 255}), image.Point{}, draw.Src)
		for i, frame := range []image.Image{firstImage, pausedImage} {
			draw.Draw(sheet, frame.Bounds().Add(image.Pt(24, 14+i*58)), frame, image.Point{}, draw.Over)
		}
		saveFrame(t, dir, "menubar-playback-controls.png", sheet)
	}
}
