package app

import (
	"math"
	"testing"

	"github.com/ebitengine/purego/objc"
)

// Configure an unordered AppKit window without App.Run, Show or activation.
// This exercises the native live-resize constraint without desktop popups.
func TestVideoWindowNativeAspectStaysHidden(t *testing.T) {
	onMainThread(func() {
		defer statusPool()()
		objc.ID(objc.GetClass("NSApplication")).Send(objc.RegisterName("sharedApplication"))
		win := objc.ID(objc.GetClass("NSWindow")).Send(objc.RegisterName("alloc"))
		win = win.Send(objc.RegisterName("initWithContentRect:styleMask:backing:defer:"),
			nsRect{Size: nsSize{W: 320, H: 180}}, uint(1|2|4|8|1<<15), uint(2), false)
		if win == 0 {
			t.Error("could not create a hidden AppKit window")
			return
		}
		win.Send(objc.RegisterName("setReleasedWhenClosed:"), false)
		defer win.Send(objc.RegisterName("release"))
		defer win.Send(objc.RegisterName("close"))
		for _, ratio := range []float64{16.0 / 9, 9.0 / 16, 1, 21.0 / 9} {
			setVideoWindowAspect(uintptr(win), ratio)
			actual := objc.Send[nsSize](win, objc.RegisterName("aspectRatio"))
			if math.Abs(actual.W/actual.H-ratio) > 0.0001 {
				t.Errorf("native aspect %v, want %v", actual, ratio)
			}
			if objc.Send[bool](win, objc.RegisterName("isVisible")) {
				t.Error("native policy test displayed a window")
			}
		}
	})
}
