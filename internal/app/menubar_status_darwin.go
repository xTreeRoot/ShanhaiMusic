package app

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"sync"

	"github.com/ebitengine/purego/objc"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

const (
	menuBarButtonWidth = 24
	menuBarPadding     = 8
	menuBarOuterInset  = 2
	menuBarMaxHeight   = 24
	menuBarGlyphSize   = 18
	menuBarControlKey  = 1 << 18
)

type menuBarGlyph int

const (
	menuBarGlyphPrevious menuBarGlyph = iota
	menuBarGlyphPlay
	menuBarGlyphNext
	menuBarGlyphLike
	menuBarGlyphApp
	menuBarGlyphPause
	menuBarGlyphLiked
)

var menuBarGlyphPNGs = sync.OnceValue(func() [2][menuBarGlyphLiked + 1][]byte {
	previous := fillIcon(`<path d="M4 4h2.5v16H4zM19 4.1c.7-.4 1.5.1 1.5.9v14c0 .8-.8 1.3-1.5.9L7.4 13c-.8-.5-.8-1.5 0-2z"/>`)
	next := fillIcon(`<path d="M20 4h-2.5v16H20zM5 4.1c-.7-.4-1.5.1-1.5.9v14c0 .8.8 1.3 1.5.9L16.6 13c.8-.5.8-1.5 0-2z"/>`)
	const pixels = menuBarGlyphSize * menuBarScale
	brand, _ := png.Decode(bytes.NewReader(trayTemplate))
	var images [2][menuBarGlyphLiked + 1][]byte
	for index, icon := range []*ui.SVG{previous, iconPlay, next, iconHeart, nil, iconPause, iconHeartFill} {
		mask := brand
		if icon != nil {
			mask = icon.Image(pixels, pixels, nil)
		}
		for variant, tone := range []color.Color{color.Black, color.White} {
			canvas := image.NewRGBA(image.Rect(0, 0, pixels, pixels))
			draw.DrawMask(canvas, canvas.Bounds(), image.NewUniform(tone), image.Point{}, mask, image.Point{}, draw.Src)
			var encoded bytes.Buffer
			_ = png.Encode(&encoded, canvas)
			images[variant][index] = encoded.Bytes()
		}
	}
	return images
})

type darwinMenuBar struct {
	bar, item, backing, capsule, lyric objc.ID
	buttons                            [menuBarOpen + 1]objc.ID
	click                              func(menuBarAction)
	appearanceChanged                  func()
	controls                           menuBarControls
	controlsSet, darkAppearance        bool
	width                              int
}

type menuBarButtonTarget struct {
	status *darwinMenuBar
	action menuBarAction
}

var menuBarTargets = map[objc.ID]menuBarButtonTarget{}

var menuBarNativeButton = sync.OnceValues(func() (objc.Class, error) {
	return objc.RegisterClass("MBMenuBarButton", objc.GetClass("NSButton"), nil, nil, []objc.MethodDef{
		{Cmd: objc.RegisterName("acceptsFirstMouse:"), Fn: func(objc.ID, objc.SEL, objc.ID) bool { return true }},
		{Cmd: objc.RegisterName("mouseDown:"), Fn: func(self objc.ID, selector objc.SEL, event objc.ID) {
			if objc.Send[uint](event, objc.RegisterName("modifierFlags"))&menuBarControlKey != 0 {
				menuBarTargets[self].invoke(menuBarContext)
				return
			}
			objc.SendSuper[objc.ID](self, selector, event)
		}},
		{Cmd: objc.RegisterName("rightMouseDown:"), Fn: func(self objc.ID, _ objc.SEL, _ objc.ID) {
			menuBarTargets[self].invoke(menuBarContext)
		}},
		{Cmd: objc.RegisterName("mbMenuBarAction:"), Fn: func(self objc.ID, _ objc.SEL, _ objc.ID) {
			target := menuBarTargets[self]
			target.invoke(target.action)
		}},
		{Cmd: objc.RegisterName("mbMenuBarContext:"), Fn: func(self objc.ID, _ objc.SEL, _ objc.ID) {
			menuBarTargets[self].invoke(menuBarContext)
		}},
		{Cmd: objc.RegisterName("observeValueForKeyPath:ofObject:change:context:"), Fn: func(self objc.ID, _ objc.SEL, _, _, _ objc.ID, _ uintptr) {
			if status := menuBarTargets[self].status; status != nil {
				if status.syncAppearance() {
					status.appearanceChanged()
				}
			}
		}},
	})
})

func (target menuBarButtonTarget) invoke(action menuBarAction) {
	if target.status != nil {
		target.status.click(action)
	}
}

func statusClass(name string) objc.ID { return objc.ID(objc.GetClass(name)) }

func statusSend(object objc.ID, name string, args ...any) objc.ID {
	return object.Send(objc.RegisterName(name), args...)
}

func statusPool() func() {
	pool := statusSend(statusClass("NSAutoreleasePool"), "new")
	return func() { statusSend(pool, "drain") }
}

func statusString(value string) objc.ID {
	return statusSend(statusClass("NSString"), "stringWithUTF8String:", value)
}

func newMenuBarStatus(click func(menuBarAction), appearanceChanged func()) (menuBarStatus, error) {
	defer statusPool()()
	buttonClass, err := menuBarNativeButton()
	if err != nil {
		return nil, err
	}
	status := &darwinMenuBar{click: click, appearanceChanged: appearanceChanged}
	status.bar = statusSend(statusClass("NSStatusBar"), "systemStatusBar")
	status.item = statusSend(status.bar, "statusItemWithLength:", float64(status.itemWidth(menuBarDefaultWidth)))
	status.backing = statusSend(status.item, "button")
	if status.backing == 0 {
		if status.item != 0 {
			statusSend(status.bar, "removeStatusItem:", status.item)
		}
		return nil, errors.New("no macOS status bar button")
	}
	statusSend(status.item, "retain")
	statusSend(status.item, "setAutosaveName:", statusString("mb"))
	status.capsule = statusSend(statusClass("NSBox"), "new")
	statusSend(status.capsule, "setBoxType:", 4)
	statusSend(status.capsule, "setTitlePosition:", 0)
	statusSend(status.capsule, "setBorderWidth:", float64(0))
	statusSend(status.capsule, "setContentViewMargins:", nsSize{})
	statusSend(status.backing, "addSubview:", status.capsule)
	statusSend(status.capsule, "release")
	content := statusSend(status.capsule, "contentView")
	status.lyric = statusSend(statusClass("NSImageView"), "new")
	statusSend(status.lyric, "setImageScaling:", 2)
	statusSend(content, "addSubview:", status.lyric)
	statusSend(status.lyric, "release")
	for index := range status.buttons {
		button := statusSend(objc.ID(buttonClass), "new")
		status.buttons[index] = button
		menuBarTargets[button] = menuBarButtonTarget{status, menuBarAction(index)}
		statusSend(button, "setBordered:", false)
		statusSend(button, "setImagePosition:", 1)
		statusSend(button, "setImageScaling:", 2)
		statusSend(button, "setTarget:", button)
		statusSend(button, "setAction:", objc.RegisterName("mbMenuBarAction:"))
		statusSend(content, "addSubview:", button)
		statusSend(button, "release")
	}
	observer := status.buttons[menuBarOpen]
	statusSend(status.backing, "setTarget:", observer)
	statusSend(status.backing, "setAction:", objc.RegisterName("mbMenuBarContext:"))
	statusSend(status.backing, "sendActionOn:", uint(1<<4))
	statusSend(status.backing, "addObserver:forKeyPath:options:context:", observer, statusString("effectiveAppearance"), uint(0), uintptr(0))
	status.layout(menuBarDefaultWidth)
	status.syncAppearance()
	status.setControls(menuBarControls{})
	status.refreshLabels()
	return status, nil
}

func (status *darwinMenuBar) itemWidth(width int) int {
	return width + len(status.buttons)*menuBarButtonWidth + 2*(menuBarPadding+menuBarOuterInset)
}

func statusRect(x, y, width, height float64) nsRect {
	return nsRect{nsPoint{x, y}, nsSize{width, height}}
}

func (status *darwinMenuBar) layout(width int) {
	itemWidth := float64(status.itemWidth(width))
	statusSend(status.item, "setLength:", itemWidth)
	bounds := objc.Send[nsRect](status.backing, objc.RegisterName("bounds"))
	barHeight := bounds.Size.H
	if barHeight <= 0 {
		barHeight = objc.Send[float64](status.bar, objc.RegisterName("thickness"))
	}
	height := min(menuBarMaxHeight, max(menuBarHeightPoints, barHeight-2))
	statusSend(status.capsule, "setFrame:", statusRect(menuBarOuterInset, (barHeight-height)/2, itemWidth-2*menuBarOuterInset, height))
	statusSend(status.capsule, "setAutoresizingMask:", uint(1<<3|1<<5))
	statusSend(status.capsule, "setCornerRadius:", height/2)
	statusSend(status.lyric, "setFrame:", statusRect(menuBarPadding, 0, float64(width), height))
	for index, button := range status.buttons {
		start := menuBarPadding + width + index*menuBarButtonWidth
		statusSend(button, "setFrame:", statusRect(float64(start), 0, menuBarButtonWidth, height))
	}
	status.width = width
}

func statusImage(png []byte, width, height float64) objc.ID {
	data := statusSend(statusClass("NSData"), "dataWithBytes:length:", &png[0], uint(len(png)))
	image := statusSend(statusSend(statusClass("NSImage"), "alloc"), "initWithData:", data)
	statusSend(image, "autorelease")
	statusSend(image, "setSize:", nsSize{width, height})
	return image
}

func (status *darwinMenuBar) setFrame(frame []byte, width int) {
	defer statusPool()()
	if status.width != width {
		status.layout(width)
	}
	statusSend(status.lyric, "setImage:", statusImage(frame, float64(width), menuBarHeightPoints))
}

func (status *darwinMenuBar) dark() bool { return status.darkAppearance }

func (status *darwinMenuBar) syncAppearance() bool {
	defer statusPool()()
	lightName, darkName := statusString("NSAppearanceNameAqua"), statusString("NSAppearanceNameDarkAqua")
	names := statusSend(statusClass("NSArray"), "arrayWithObjects:count:", &[2]objc.ID{lightName, darkName}, uint(2))
	match := statusSend(statusSend(status.backing, "effectiveAppearance"), "bestMatchFromAppearancesWithNames:", names)
	dark := objc.Send[bool](match, objc.RegisterName("isEqualToString:"), darkName)
	changed := dark != status.darkAppearance
	if !changed && status.controlsSet {
		return false
	}
	status.darkAppearance = dark
	white, opacity := float64(0), 0.10
	if dark {
		white, opacity = 1, 0.16
	}
	statusSend(status.capsule, "setFillColor:", statusSend(statusClass("NSColor"), "colorWithWhite:alpha:", white, opacity))
	status.setIcon(menuBarPrevious, menuBarGlyphPrevious)
	status.setIcon(menuBarNext, menuBarGlyphNext)
	status.setIcon(menuBarOpen, menuBarGlyphApp)
	if status.controlsSet {
		status.controlsSet = false
		status.setControls(status.controls)
	}
	return changed
}

func (status *darwinMenuBar) setIcon(action menuBarAction, glyph menuBarGlyph) {
	variant := 0
	if status.dark() {
		variant = 1
	}
	png := menuBarGlyphPNGs()[variant][glyph]
	statusSend(status.buttons[action], "setImage:", statusImage(png, menuBarGlyphSize, menuBarGlyphSize))
}

func (status *darwinMenuBar) setControls(controls menuBarControls) {
	if status.controlsSet && status.controls == controls {
		return
	}
	defer statusPool()()
	if !status.controlsSet || status.controls.playing != controls.playing {
		glyph := menuBarGlyphPlay
		if controls.playing {
			glyph = menuBarGlyphPause
		}
		status.setIcon(menuBarToggle, glyph)
	}
	if !status.controlsSet || status.controls.liked != controls.liked {
		glyph := menuBarGlyphLike
		if controls.liked {
			glyph = menuBarGlyphLiked
		}
		status.setIcon(menuBarLike, glyph)
	}
	for action := menuBarPrevious; action <= menuBarNext; action++ {
		statusSend(status.buttons[action], "setEnabled:", controls.hasTrack)
	}
	statusSend(status.buttons[menuBarLike], "setEnabled:", controls.canLike)
	status.controls, status.controlsSet = controls, true
}

func (status *darwinMenuBar) refreshLabels() {
	defer statusPool()()
	labels := [...]string{T("Previous"), T("Play/Pause"), T("Next"), T("Like/Unlike"), Tf("Open %s", mygo.App.Name())}
	for index, button := range status.buttons {
		label := statusString(labels[index])
		statusSend(button, "setToolTip:", label)
		statusSend(button, "setAccessibilityLabel:", label)
	}
}

func (status *darwinMenuBar) setToolTip(tip string) {
	defer statusPool()()
	statusSend(status.lyric, "setToolTip:", statusString(tip))
}

func (status *darwinMenuBar) close() {
	if status.item == 0 {
		return
	}
	defer statusPool()()
	statusSend(status.backing, "removeObserver:forKeyPath:", status.buttons[menuBarOpen], statusString("effectiveAppearance"))
	statusSend(status.backing, "setTarget:", objc.ID(0))
	for _, button := range status.buttons {
		delete(menuBarTargets, button)
	}
	statusSend(status.bar, "removeStatusItem:", status.item)
	statusSend(status.item, "release")
	status.item = 0
}
