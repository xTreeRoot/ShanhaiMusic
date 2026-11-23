package app

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"math"
	"time"

	"github.com/egoist/mygo/ui"
)

type menuBarStyle struct {
	video, dark bool
	preview     *image.RGBA
}

func (style menuBarStyle) lyricStart() int {
	if style.video {
		return menuBarVideoPoints + menuBarIconGap
	}
	return menuBarIconPoints + menuBarIconGap
}

func (style menuBarStyle) imageWidth(width int) int { return style.lyricStart() + width }

// menuBarRenderer caches each shaped lyric and only replaces the native template
// image when its pixels change. Every enabled frame has the same canvas width.
type menuBarRenderer struct {
	text                 string
	strip                *image.RGBA
	icon                 image.Image
	width, offset        int
	enabled, initialized bool
	style                menuBarStyle
}

func (m *menuBarRenderer) frame(text string, elapsed time.Duration, width int, enabled bool, style menuBarStyle) ([]byte, bool) {
	if !enabled {
		if m.initialized && !m.enabled {
			return nil, false
		}
		*m = menuBarRenderer{initialized: true}
		return trayTemplate, true
	}
	textChanged := m.strip == nil || text != m.text
	if textChanged {
		m.text = text
		m.strip = renderMenuBarText(text)
	}
	viewport := width * menuBarScale
	offset := menuBarOffset(m.strip.Bounds().Dx(), viewport, elapsed)
	if !textChanged && m.initialized && m.enabled && m.width == width && m.offset == offset && m.style == style {
		return nil, false
	}
	if m.icon == nil {
		m.icon, _ = png.Decode(bytes.NewReader(trayTemplate))
	}
	height := menuBarHeightPoints * menuBarScale
	left := menuBarIconPoints
	if style.video {
		left = menuBarVideoPoints
	}
	start := style.lyricStart() * menuBarScale
	canvas := image.NewRGBA(image.Rect(0, 0, style.imageWidth(width)*menuBarScale, height))
	ink := image.NewUniform(color.Black)
	if style.dark {
		ink = image.NewUniform(color.White)
	}
	if style.video && style.preview != nil {
		draw.Draw(canvas, image.Rect(0, 0, left*menuBarScale, height), style.preview, image.Point{}, draw.Src)
	} else {
		x := (left - menuBarIconPoints) * menuBarScale / 2
		draw.DrawMask(canvas, image.Rect(x, 0, x+menuBarIconPoints*menuBarScale, height), ink, image.Point{}, m.icon, image.Point{}, draw.Over)
	}
	target := image.Rect(start, 0, start+viewport, height)
	draw.DrawMask(canvas, target, ink, image.Point{}, m.strip, image.Pt(offset, 0), draw.Over)
	if m.strip.Bounds().Dx() > viewport {
		repeat := m.strip.Bounds().Dx() + menuBarScrollGap*menuBarScale - offset
		if repeat < viewport {
			draw.DrawMask(canvas, image.Rect(start+repeat, 0, start+viewport, height), ink, image.Point{}, m.strip, image.Point{}, draw.Over)
		}
	}
	var encoded bytes.Buffer
	_ = png.Encode(&encoded, canvas)
	m.initialized, m.enabled, m.width, m.offset, m.style = true, true, width, offset, style
	return encoded.Bytes(), true
}

// renderMenuBarText uses MyGo's text shaping once per sentence. Cropping this
// transparent strip keeps font metrics out of NSStatusItem's size calculation.
func renderMenuBarText(text string) *image.RGBA {
	span := ui.Span{Text: text, Size: menuBarFontSize, Color: ui.RGB(0, 0, 0)}
	var width, height float32
	ui.Render(func(c *ui.Context) { width, height = c.MeasureText(0, span) }, 1, 1, 1)
	ink := ui.Render(func(c *ui.Context) {
		c.Root().Background(ui.RGB(255, 255, 255))
		ui.Box(c).Fill().Draw(func(p *ui.Painter, r ui.Rect) {
			p.RichText(0, (menuBarHeightPoints-height)/2, 0, span)
		})
	}, max(1, int(math.Ceil(float64(width)))+1), menuBarHeightPoints, menuBarScale)
	// Convert black-on-white text coverage to a transparent native template mask.
	for i := 0; i < len(ink.Pix); i += 4 {
		alpha := 255 - ink.Pix[i]
		ink.Pix[i], ink.Pix[i+1], ink.Pix[i+2], ink.Pix[i+3] = 0, 0, 0, alpha
	}
	return ink
}
