package app

import "github.com/ebitengine/purego/objc"

// AppKit constrains live edge/corner dragging before committing each resize;
// correcting the size in OnResize would make the window jump while dragging.
func setVideoWindowAspect(handle uintptr, ratio float64) {
	if handle != 0 {
		objc.ID(handle).Send(objc.RegisterName("setAspectRatio:"), nsSize{W: ratio, H: 1})
	}
}
