package app

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"log"
	"sync"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

type menuBarAction int

const (
	menuBarPrevious menuBarAction = iota
	menuBarToggle
	menuBarNext
)

type menuBarTransport int

const (
	menuBarIdle menuBarTransport = iota
	menuBarPaused
	menuBarPlaying
)

var menuBarButtons = [...]struct{ id, label string }{
	{"mb-previous", N("Previous")},
	{"mb-play-pause", N("Play/Pause")},
	{"mb-next", N("Next")},
}

type menuBarButton interface {
	OnClick(func()) func()
	SetIcon([]byte, bool) error
	SetToolTip(string)
	Destroy()
}

type menuBarControls struct {
	buttons   [len(menuBarButtons)]menuBarButton
	transport menuBarTransport
}

func openMenuBarButton(opts mygo.TrayOptions) (menuBarButton, error) {
	return mygo.NewTray(opts)
}

func (c *menuBarControls) show(transport menuBarTransport, open func(mygo.TrayOptions) (menuBarButton, error), click func(menuBarAction)) {
	c.update(transport)
	for action := menuBarNext; action >= menuBarPrevious; action-- {
		if c.buttons[action] != nil {
			continue
		}
		item := menuBarButtons[action]
		button, err := open(mygo.TrayOptions{ID: item.id, Icon: menuBarControlIcon(action, transport), IconIsTemplate: true, ToolTip: T(item.label)})
		if err != nil {
			log.Println("menu bar controls:", err)
			continue
		}
		target := action
		button.OnClick(func() { click(target) })
		c.buttons[action] = button
	}
}

func (c *menuBarControls) update(transport menuBarTransport) {
	if c.transport == transport {
		return
	}
	for index, button := range c.buttons {
		action := menuBarAction(index)
		if button != nil && (action == menuBarToggle || c.transport == menuBarIdle || transport == menuBarIdle) {
			if err := button.SetIcon(menuBarControlIcon(action, transport), true); err != nil {
				log.Println("menu bar controls:", err)
			}
		}
	}
	c.transport = transport
}

func (c *menuBarControls) refreshToolTips() {
	for index, button := range c.buttons {
		if button != nil {
			button.SetToolTip(T(menuBarButtons[index].label))
		}
	}
}

func (c *menuBarControls) close() {
	for index, button := range c.buttons {
		if button != nil {
			button.Destroy()
			c.buttons[index] = nil
		}
	}
	c.transport = menuBarIdle
}

var menuBarControlPNGs = sync.OnceValue(func() [4][2][]byte {
	const size = menuBarHeightPoints * menuBarScale
	const iconSize = 14 * menuBarScale
	const inset = (size - iconSize) / 2
	var images [4][2][]byte
	for index, icon := range []*ui.SVG{iconPrev, iconPlay, iconNext, iconPause} {
		mask := icon.Image(iconSize, iconSize, nil)
		for variant, opacity := range []uint8{255, 100} {
			canvas := image.NewRGBA(image.Rect(0, 0, size, size))
			ink := image.NewUniform(color.NRGBA{A: opacity})
			draw.DrawMask(canvas, image.Rect(inset, inset, inset+iconSize, inset+iconSize), ink, image.Point{}, mask, image.Point{}, draw.Over)
			var encoded bytes.Buffer
			_ = png.Encode(&encoded, canvas)
			images[index][variant] = encoded.Bytes()
		}
	}
	return images
})

func menuBarControlIcon(action menuBarAction, transport menuBarTransport) []byte {
	glyph := int(action)
	if action == menuBarToggle && transport == menuBarPlaying {
		glyph = len(menuBarButtons)
	}
	variant := 0
	if transport == menuBarIdle {
		variant = 1
	}
	return menuBarControlPNGs()[glyph][variant]
}

func (a *app) menuBarTransport() menuBarTransport {
	if a.pl == nil || a.pl.Current() == nil {
		return menuBarIdle
	}
	if a.pl.Playing() {
		return menuBarPlaying
	}
	return menuBarPaused
}

func (a *app) runMenuBarAction(action menuBarAction) {
	if a.pl == nil || a.pl.Current() == nil {
		return
	}
	switch action {
	case menuBarPrevious:
		a.pl.Prev()
	case menuBarToggle:
		a.pl.TogglePlay()
	case menuBarNext:
		a.pl.Next()
	}
	a.redraw()
}
