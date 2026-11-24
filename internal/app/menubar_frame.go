package app

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"math"
	"sync"
	"time"

	"github.com/egoist/mygo/ui"
)

const (
	menuBarButtonWidth = 24
	menuBarPadding     = 6
	menuBarGlyphSize   = 18
)

type menuBarLayout struct{ mediaWidth int }

func (layout menuBarLayout) width() int {
	return layout.mediaWidth + int(menuBarContext)*menuBarButtonWidth + 2*menuBarPadding
}

func (layout menuBarLayout) controlBounds(action menuBarAction) image.Rectangle {
	left := menuBarPadding + layout.mediaWidth + int(action)*menuBarButtonWidth
	return image.Rect(left, 0, left+menuBarButtonWidth, menuBarHeightPoints)
}

var menuBarGlyphMasks = sync.OnceValue(func() [2][menuBarContext]image.Image {
	previous := fillIcon(`<path d="M4 4h2.5v16H4zM19 4.1c.7-.4 1.5.1 1.5.9v14c0 .8-.8 1.3-1.5.9L7.4 13c-.8-.5-.8-1.5 0-2z"/>`)
	next := fillIcon(`<path d="M20 4h-2.5v16H20zM5 4.1c-.7-.4-1.5.1-1.5.9v14c0 .8.8 1.3 1.5.9L16.6 13c.8-.5.8-1.5 0-2z"/>`)
	const pixels = menuBarGlyphSize * menuBarScale
	var masks [2][menuBarContext]image.Image
	for variant, icons := range [2][menuBarContext]*ui.SVG{
		{previous, iconPlay, next, iconHeart}, {previous, iconPause, next, iconHeartFill},
	} {
		for action, icon := range icons {
			masks[variant][action] = icon.Image(pixels, pixels, nil)
		}
	}
	return masks
})

type menuBarStyle struct {
	video, dark bool
	preview     *image.RGBA
	controls    menuBarControls
}

func (style menuBarStyle) layout(width int) menuBarLayout {
	if style.video {
		width += menuBarVideoPoints + menuBarIconGap
	}
	return menuBarLayout{mediaWidth: width}
}

// menuBarRenderer caches each shaped lyric and replaces the native image only
// when its pixels change. The lyric viewport does not resize between tracks.
type menuBarRenderer struct {
	text          string
	strip         *image.RGBA
	width, offset int
	style         menuBarStyle
}

func (m *menuBarRenderer) frame(text string, elapsed time.Duration, width int, style menuBarStyle) ([]byte, bool) {
	textChanged := m.strip == nil || text != m.text
	if textChanged {
		m.text = text
		m.strip = renderMenuBarText(text)
	}
	viewport := width * menuBarScale
	offset := menuBarOffset(m.strip.Bounds().Dx(), viewport, elapsed)
	if !textChanged && m.width == width && m.offset == offset && m.style == style {
		return nil, false
	}
	height := menuBarHeightPoints * menuBarScale
	layout := style.layout(width)
	canvas := image.NewRGBA(image.Rect(0, 0, layout.width()*menuBarScale, height))
	ink := image.NewUniform(color.Black)
	if style.dark {
		ink = image.NewUniform(color.White)
	}
	lyricStart := menuBarPadding * menuBarScale
	if style.video {
		if style.preview != nil {
			draw.Draw(canvas, image.Rect(lyricStart, 0, lyricStart+menuBarVideoPoints*menuBarScale, height), style.preview, image.Point{}, draw.Src)
		}
		lyricStart += (menuBarVideoPoints + menuBarIconGap) * menuBarScale
	}
	lyricEnd := lyricStart + viewport
	start := lyricStart + max(0, (viewport-m.strip.Bounds().Dx())/2)
	target := image.Rect(start, 0, lyricEnd, height)
	draw.DrawMask(canvas, target, ink, image.Point{}, m.strip, image.Pt(offset, 0), draw.Over)
	if m.strip.Bounds().Dx() > viewport {
		repeat := lyricStart + m.strip.Bounds().Dx() + menuBarScrollGap*menuBarScale - offset
		if repeat < lyricEnd {
			draw.DrawMask(canvas, image.Rect(repeat, 0, lyricEnd, height), ink, image.Point{}, m.strip, image.Point{}, draw.Over)
		}
	}
	masks := menuBarGlyphMasks()
	for action := menuBarPrevious; action < menuBarContext; action++ {
		variant := 0
		if action == menuBarToggle && style.controls.playing || action == menuBarLike && style.controls.liked {
			variant = 1
		}
		mask := masks[variant][action]
		bounds := layout.controlBounds(action)
		start := image.Pt(bounds.Min.X*menuBarScale+(bounds.Dx()*menuBarScale-mask.Bounds().Dx())/2, (height-mask.Bounds().Dy())/2)
		glyphInk := ink
		if !style.controls.enabled(action) {
			glyphInk = image.NewUniform(color.RGBA{A: 112})
			if style.dark {
				glyphInk = image.NewUniform(color.RGBA{R: 112, G: 112, B: 112, A: 112})
			}
		}
		draw.DrawMask(canvas, mask.Bounds().Add(start), glyphInk, image.Point{}, mask, image.Point{}, draw.Over)
	}
	var encoded bytes.Buffer
	_ = png.Encode(&encoded, canvas)
	m.width, m.offset, m.style = width, offset, style
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
