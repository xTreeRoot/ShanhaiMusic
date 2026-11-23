package app

import (
	"github.com/ebitengine/purego/objc"
	"github.com/egoist/mygo"
)

func (a *app) trayTransportAction() menuBarAction {
	application := objc.ID(objc.GetClass("NSApplication")).Send(objc.RegisterName("sharedApplication"))
	event := application.Send(objc.RegisterName("currentEvent"))
	if event != 0 && objc.Send[int](event, objc.RegisterName("type")) == 2 {
		window := event.Send(objc.RegisterName("window"))
		if button := trayStatusButton(window.Send(objc.RegisterName("contentView"))); button != 0 {
			point := objc.Send[nsPoint](event, objc.RegisterName("locationInWindow"))
			local := objc.Send[nsPoint](button, objc.RegisterName("convertPoint:fromView:"), point, objc.ID(0))
			bounds := objc.Send[nsRect](button, objc.RegisterName("bounds"))
			cell := button.Send(objc.RegisterName("cell"))
			if cell != 0 {
				image := objc.Send[nsRect](cell, objc.RegisterName("imageRectForBounds:"), bounds)
				if image.Size.W > 0 {
					return a.trayLyrics.actionAtImage(local.X-image.Origin.X, image.Size.W)
				}
			}
		}
	}
	return a.trayLyrics.actionAt(a.tray.Bounds(), mygo.Screen.CursorScreenPoint())
}

func trayStatusButton(view objc.ID) objc.ID {
	if view == 0 {
		return 0
	}
	if objc.Send[bool](view, objc.RegisterName("isKindOfClass:"), objc.GetClass("NSStatusBarButton")) {
		return view
	}
	children := view.Send(objc.RegisterName("subviews"))
	for index := range objc.Send[uint](children, objc.RegisterName("count")) {
		if button := trayStatusButton(children.Send(objc.RegisterName("objectAtIndex:"), index)); button != 0 {
			return button
		}
	}
	return 0
}
