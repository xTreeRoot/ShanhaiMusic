package app

import (
	"image"
	"image/color"
	"image/draw"
	"sync"

	"github.com/egoist/mygo"
)

const (
	menuBarControlGap   = 4
	menuBarControlWidth = 19
	menuBarControlIcon  = 14 * menuBarScale
)

type menuBarAction int

const (
	menuBarOpenMenu menuBarAction = iota
	menuBarPrevious
	menuBarToggle
	menuBarNext
)

type menuBarTransport int

const (
	menuBarIdle menuBarTransport = iota
	menuBarPaused
	menuBarPlaying
)

var menuBarControlMasks = sync.OnceValue(func() [4]*image.RGBA {
	return [4]*image.RGBA{
		iconPrev.Image(menuBarControlIcon, menuBarControlIcon, nil),
		iconPlay.Image(menuBarControlIcon, menuBarControlIcon, nil),
		iconNext.Image(menuBarControlIcon, menuBarControlIcon, nil),
		iconPause.Image(menuBarControlIcon, menuBarControlIcon, nil),
	}
})

func (style menuBarStyle) lyricStart() int {
	if style.video {
		return menuBarVideoPoints + menuBarIconGap
	}
	return menuBarIconPoints + menuBarIconGap
}

func (style menuBarStyle) controlsStart(width int) int {
	return style.lyricStart() + width + menuBarControlGap
}

func (style menuBarStyle) imageWidth(width int) int {
	return style.controlsStart(width) + 3*menuBarControlWidth
}

func (style menuBarStyle) drawControls(canvas *image.RGBA, width int, ink *image.Uniform) {
	masks := menuBarControlMasks()
	if style.transport == menuBarPlaying {
		masks[1] = masks[3]
	}
	if style.transport == menuBarIdle {
		red, green, blue, _ := ink.C.RGBA()
		ink = image.NewUniform(color.NRGBA{R: uint8(red >> 8), G: uint8(green >> 8), B: uint8(blue >> 8), A: 100})
	}
	for index, mask := range masks[:3] {
		left := (style.controlsStart(width)+index*menuBarControlWidth)*menuBarScale + (menuBarControlWidth*menuBarScale-menuBarControlIcon)/2
		top := (canvas.Bounds().Dy() - menuBarControlIcon) / 2
		target := image.Rect(left, top, left+menuBarControlIcon, top+menuBarControlIcon)
		draw.DrawMask(canvas, target, ink, image.Point{}, mask, image.Point{}, draw.Over)
	}
}

func (m *menuBarRenderer) actionAt(bounds mygo.Rectangle, point mygo.Point) menuBarAction {
	width := m.style.imageWidth(m.width)
	if !m.enabled || bounds.Width < width || point.Y < bounds.Y || point.Y >= bounds.Y+bounds.Height {
		return menuBarOpenMenu
	}
	imageLeft := bounds.X + (bounds.Width-width)/2
	offset := point.X - imageLeft - m.style.controlsStart(m.width)
	if offset < 0 || offset >= 3*menuBarControlWidth {
		return menuBarOpenMenu
	}
	return menuBarPrevious + menuBarAction(offset/menuBarControlWidth)
}

func (a *app) runMenuBarAction(action menuBarAction) bool {
	if action == menuBarOpenMenu {
		return false
	}
	if a.pl == nil || a.pl.Current() == nil {
		return true
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
	return true
}
