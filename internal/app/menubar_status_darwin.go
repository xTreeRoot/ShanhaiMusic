package app

import (
	"errors"
	"sync"

	"github.com/ebitengine/purego/objc"
)

const (
	menuBarLeftDown       = 1
	menuBarLeftUp         = 2
	menuBarRightUp        = 4
	menuBarControlKey     = 1 << 18
	menuBarCommandKey     = 1 << 20
	menuBarImageOnly      = 1
	menuBarImageScaleNone = 2
)

type darwinMenuBar struct {
	bar, item, button, target objc.ID
	click                     func(menuBarAction)
	appearanceChanged         func()
	controls                  menuBarControls
	layout                    menuBarLayout
	darkAppearance            bool
	tip                       string
}

var menuBarTargets = map[objc.ID]*darwinMenuBar{}

var menuBarNativeTarget = sync.OnceValues(func() (objc.Class, error) {
	return objc.RegisterClass("MBMenuBarTarget", objc.GetClass("NSObject"), nil, nil, []objc.MethodDef{
		{Cmd: objc.RegisterName("mbStatusButtonClicked:"), Fn: func(self objc.ID, _ objc.SEL, sender objc.ID) {
			status := menuBarTargets[self]
			if status == nil || sender != status.button {
				return
			}
			application := statusSend(statusClass("NSApplication"), "sharedApplication")
			event := statusSend(application, "currentEvent")
			eventType := objc.Send[uint](event, objc.RegisterName("type"))
			switch eventType {
			case menuBarLeftDown, menuBarLeftUp, menuBarRightUp:
				point := objc.Send[nsPoint](statusClass("NSEvent"), objc.RegisterName("mouseLocation"))
				status.dispatchClick(eventType, objc.Send[uint](event, objc.RegisterName("modifierFlags")), point)
			default:
				status.click(menuBarContext)
			}
		}},
		{Cmd: objc.RegisterName("observeValueForKeyPath:ofObject:change:context:"), Fn: func(self objc.ID, _ objc.SEL, _ objc.ID, observed objc.ID, _ objc.ID, _ uintptr) {
			if status := menuBarTargets[self]; status != nil && observed == status.button && status.syncAppearance() {
				status.appearanceChanged()
			}
		}},
	})
})

func (status *darwinMenuBar) dispatchClick(eventType, flags uint, screenPoint nsPoint) {
	if eventType == menuBarRightUp || eventType == menuBarLeftUp && flags&menuBarControlKey != 0 {
		status.click(menuBarContext)
		return
	}
	if eventType != menuBarLeftDown || flags&(menuBarCommandKey|menuBarControlKey) != 0 {
		return
	}
	window := statusSend(status.button, "window")
	if window == 0 {
		return
	}
	windowPoint := objc.Send[nsPoint](window, objc.RegisterName("convertPointFromScreen:"), screenPoint)
	point := objc.Send[nsPoint](status.button, objc.RegisterName("convertPoint:fromView:"), windowPoint, objc.ID(0))
	artwork := status.artworkRect()
	if point.Y < artwork.Origin.Y || point.Y >= artwork.Origin.Y+artwork.Size.H {
		return
	}
	for action := menuBarPrevious; action < menuBarContext; action++ {
		bounds := status.layout.controlBounds(action)
		imageX := point.X - artwork.Origin.X
		if imageX >= float64(bounds.Min.X) && imageX < float64(bounds.Max.X) && status.controls.enabled(action) {
			status.click(action)
			return
		}
	}
}

func (status *darwinMenuBar) artworkRect() nsRect {
	bounds := objc.Send[nsRect](status.button, objc.RegisterName("bounds"))
	cell := statusSend(status.button, "cell")
	return objc.Send[nsRect](cell, objc.RegisterName("imageRectForBounds:"), bounds)
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

func newMenuBarStatus(layout menuBarLayout, click func(menuBarAction), appearanceChanged func()) (menuBarStatus, error) {
	defer statusPool()()
	targetClass, err := menuBarNativeTarget()
	if err != nil {
		return nil, err
	}
	status := &darwinMenuBar{click: click, appearanceChanged: appearanceChanged}
	status.bar = statusSend(statusClass("NSStatusBar"), "systemStatusBar")
	status.target = statusSend(objc.ID(targetClass), "new")
	if !status.attach(layout) {
		statusSend(status.target, "release")
		return nil, errors.New("no macOS status bar button")
	}
	status.syncAppearance()
	return status, nil
}

func (status *darwinMenuBar) attach(layout menuBarLayout) bool {
	item := statusSend(status.bar, "statusItemWithLength:", float64(layout.width()))
	if item == 0 {
		return false
	}
	button := statusSend(item, "button")
	if button == 0 {
		statusSend(status.bar, "removeStatusItem:", item)
		return false
	}
	statusSend(item, "retain")
	status.detach()
	status.item, status.button, status.layout = item, button, layout
	statusSend(item, "setAutosaveName:", statusString("mb"))
	statusSend(button, "setImagePosition:", uint(menuBarImageOnly))
	statusSend(button, "setImageScaling:", uint(menuBarImageScaleNone))
	statusSend(button, "setTarget:", status.target)
	statusSend(button, "setAction:", objc.RegisterName("mbStatusButtonClicked:"))
	statusSend(button, "sendActionOn:", uint(1)<<menuBarLeftDown|uint(1)<<menuBarLeftUp|uint(1)<<menuBarRightUp)
	status.setToolTip(status.tip)
	status.refreshLabels()
	menuBarTargets[status.target] = status
	statusSend(button, "addObserver:forKeyPath:options:context:", status.target, statusString("effectiveAppearance"), uint(0), uintptr(0))
	return true
}

func (status *darwinMenuBar) setFrame(frame []byte, layout menuBarLayout, controls menuBarControls) {
	defer statusPool()()
	resized := status.layout != layout
	if resized && !status.attach(layout) {
		return
	}
	data := statusSend(statusClass("NSData"), "dataWithBytes:length:", &frame[0], uint(len(frame)))
	image := statusSend(statusSend(statusClass("NSImage"), "alloc"), "initWithData:", data)
	defer statusSend(image, "release")
	statusSend(image, "setSize:", nsSize{float64(layout.width()), menuBarHeightPoints})
	status.controls = controls
	statusSend(status.button, "setImage:", image)
	if resized && status.syncAppearance() {
		status.appearanceChanged()
	}
}

func (status *darwinMenuBar) dark() bool { return status.darkAppearance }

func (status *darwinMenuBar) syncAppearance() bool {
	defer statusPool()()
	lightName, darkName := statusString("NSAppearanceNameAqua"), statusString("NSAppearanceNameDarkAqua")
	names := statusSend(statusClass("NSArray"), "arrayWithObjects:count:", &[2]objc.ID{lightName, darkName}, uint(2))
	match := statusSend(statusSend(status.button, "effectiveAppearance"), "bestMatchFromAppearancesWithNames:", names)
	dark := objc.Send[bool](match, objc.RegisterName("isEqualToString:"), darkName)
	changed := dark != status.darkAppearance
	status.darkAppearance = dark
	return changed
}

func (status *darwinMenuBar) refreshLabels() {
	defer statusPool()()
	statusSend(status.button, "setAccessibilityLabel:", statusString(T("Menu Bar Lyrics")))
}

func (status *darwinMenuBar) setToolTip(tip string) {
	defer statusPool()()
	status.tip = tip
	statusSend(status.button, "setToolTip:", statusString(tip))
}

func (status *darwinMenuBar) close() {
	if status.item == 0 {
		return
	}
	defer statusPool()()
	status.detach()
	statusSend(status.target, "release")
}

func (status *darwinMenuBar) detach() {
	if status.item == 0 {
		return
	}
	delete(menuBarTargets, status.target)
	statusSend(status.button, "removeObserver:forKeyPath:", status.target, statusString("effectiveAppearance"))
	statusSend(status.button, "setTarget:", objc.ID(0))
	statusSend(status.button, "setAction:", objc.SEL(0))
	statusSend(status.bar, "removeStatusItem:", status.item)
	statusSend(status.item, "release")
	status.item, status.button = 0, 0
}
