package app

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"os"
	"testing"

	"github.com/egoist/mygo"
	"mygo-music/internal/music"
)

type fakeMenuBarButton struct {
	click  func()
	icon   []byte
	writes int
}

func (b *fakeMenuBarButton) OnClick(click func()) func() {
	b.click = click
	return func() { b.click = nil }
}

func (b *fakeMenuBarButton) SetIcon(icon []byte, _ bool) error {
	b.icon = icon
	b.writes++
	return nil
}

func (b *fakeMenuBarButton) SetToolTip(string) {}
func (b *fakeMenuBarButton) Destroy()          { b.click = nil }

func TestMenuBarControlClicks(t *testing.T) {
	a := newTestApp()
	a.pl.Cue([]music.Track{song("first", "First"), song("last", "Last")})
	buttons := map[string]*fakeMenuBarButton{}
	newButton := func(opts mygo.TrayOptions) (menuBarButton, error) {
		if opts.Menu != nil {
			t.Fatal("a native menu would consume the transport button's left click")
		}
		if button := buttons[opts.ID]; button != nil && button.click != nil {
			t.Fatalf("duplicate live status item %s", opts.ID)
		}
		button := &fakeMenuBarButton{icon: opts.Icon}
		buttons[opts.ID] = button
		return button, nil
	}
	show := func() { a.trayControls.show(a.menuBarTransport(), newButton, a.runMenuBarAction) }
	show()
	show()
	previous, middle, next := buttons["mb-previous"], buttons["mb-play-pause"], buttons["mb-next"]
	if previous == nil || middle == nil || next == nil || previous.click == nil || middle.click == nil || next.click == nil {
		t.Fatal("transport controls are not independently clickable")
	}
	previous.click()
	if a.pl.Current().ID != "last" || !a.pl.Playing() {
		t.Fatal("previous control did not select the previous song")
	}
	a.trayControls.update(a.menuBarTransport())
	playing := middle.icon
	middle.click()
	a.trayControls.update(a.menuBarTransport())
	paused := middle.icon
	if a.pl.Playing() || bytes.Equal(playing, paused) || previous.writes != 0 || next.writes != 0 {
		t.Fatal("pausing should update only the middle button")
	}
	middle.click()
	a.trayControls.update(a.menuBarTransport())
	if !a.pl.Playing() || !bytes.Equal(playing, middle.icon) {
		t.Fatal("middle control did not resume")
	}
	next.click()
	if a.pl.Current().ID != "first" {
		t.Fatal("next control did not select the next song")
	}
	a.trayControls.close()
	if previous.click != nil || middle.click != nil || next.click != nil {
		t.Fatal("hiding the controls did not detach the status items")
	}
	show()
	buttons["mb-play-pause"].click()
	if a.pl.Playing() {
		t.Fatal("re-enabled status items did not get new callbacks")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		canvas := image.NewRGBA(image.Rect(0, 0, 180, 118))
		draw.Draw(canvas, canvas.Bounds(), image.NewUniform(color.RGBA{R: 45, G: 40, B: 37, A: 255}), image.Point{}, draw.Src)
		for row, center := range [][]byte{playing, paused} {
			for column, icon := range [][]byte{previous.icon, center, next.icon} {
				mask := menuBarImage(t, icon)
				bounds := mask.Bounds().Add(image.Pt(18+column*54, 10+row*54))
				draw.DrawMask(canvas, bounds, image.NewUniform(color.White), image.Point{}, mask, image.Point{}, draw.Over)
			}
		}
		saveFrame(t, dir, "menubar-playback-controls.png", canvas)
	}
}
