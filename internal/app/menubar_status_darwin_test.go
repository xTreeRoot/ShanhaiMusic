package app

import (
	"bytes"
	"fmt"
	"image"
	"os"
	"testing"
	"time"
	"unsafe"

	"github.com/ebitengine/purego/objc"
	"github.com/egoist/mygo"
	"mygo-music/internal/local"
	"mygo-music/internal/music"
)

func TestMenuBarNativeControls(t *testing.T) {
	a := newTestApp()
	a.settings.MenuBarLyricsWidth, a.settings.MenuBarVideo = 190, false
	a.library = addFake(a, local.New(t.TempDir()))
	platform := menuBarTestPlugin(t, "fixture-platform", `
let liked = true;
export function isLiked({ ids }) {
  return Object.fromEntries(ids.map(id => [id, id === "first" && liked]));
}
export function like({ id, on }) {
  if (id !== "first" || typeof on !== "boolean") throw new Error("wrong track or state");
  liked = on;
}`)
	lib := addFake(a, platform)
	lib.liked = map[string]bool{"last": false}
	callbacks := posts(a)
	a.pl.Changed = a.updateTrayLyric
	var status *darwinMenuBar
	var contextClicks int
	var err error
	onMainThread(func() {
		mygo.RunOnMain(func() {})
		created, createErr := newMenuBarStatus(menuBarLayout{mediaWidth: 190}, func(action menuBarAction) {
			if action == menuBarContext {
				contextClicks++
			} else {
				a.runMenuBarAction(action)
			}
		}, a.updateTrayLyric)
		if createErr != nil {
			err = createErr
			return
		}
		status = created.(*darwinMenuBar)
		a.menuBar = status
		a.updateTrayLyric()
	})
	t.Cleanup(func() { onMainThread(a.closeMenuBar) })
	if err != nil {
		t.Fatal(err)
	}

	click := func(imageX float64, eventType, flags uint) {
		onMainThread(func() {
			point := menuBarScreenPoint(status, imageX)
			status.dispatchClick(eventType, flags, point)
		})
	}
	controlX := func(action menuBarAction) float64 {
		return float64(status.layout.controlBounds(action).Min.X) + menuBarButtonWidth/2
	}
	activate := func(action menuBarAction) { click(controlX(action), menuBarLeftDown, 0) }
	activate(menuBarToggle)
	if a.pl.Current() != nil || contextClicks != 0 {
		t.Fatal("disabled playback button dispatched another action")
	}

	var frames [2][]byte
	var layouts [2]menuBarLayout
	onMainThread(func() {
		a.pl.Cue([]music.Track{{ID: "first", Name: "First", Source: lib.id}, {ID: "last", Name: "Last", Source: lib.id}})
		a.updateTrayLyric()
	})
	onMainThread(wait(t, callbacks, "the platform heart state"))
	if !status.controls.liked {
		t.Fatal("heart did not show the source plugin's existing like")
	}
	onMainThread(func() {
		defer statusPool()()
		for index, appearance := range []string{"NSAppearanceNameAqua", "NSAppearanceNameDarkAqua"} {
			a.settings.MenuBarVideo = index == 1
			a.updateTrayLyric()
			nativeAppearance := statusSend(statusClass("NSAppearance"), "appearanceNamed:", statusString(appearance))
			statusSend(status.button, "setAppearance:", nativeAppearance)
			if status.dark() != (index == 1) {
				err = fmt.Errorf("status button did not follow %s", appearance)
				return
			}
			waitForStatusBarButton(status.button)
			if err = checkStatusArtwork(status); err != nil {
				return
			}
			frames[index], layouts[index] = menuBarButtonArtwork(status.button), status.layout
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	for index, frame := range frames {
		img := menuBarImage(t, frame)
		assertMenuBarControlPixels(t, img, layouts[index], index == 1)
		if dir := os.Getenv("MM_FRAMES"); dir != "" {
			saveFrame(t, dir, [...]string{"menubar-native-light.png", "menubar-native-dark.png"}[index], img)
		}
	}
	click(menuBarPadding+10, menuBarLeftDown, 0)
	if contextClicks != 0 || a.pl.Current().ID != "first" || a.pl.Playing() {
		t.Fatal("clicking the video or lyrics triggered a control")
	}
	activate(menuBarPrevious)
	if a.pl.Current().ID != "last" || !a.pl.Playing() {
		t.Fatal("previous button did not select the previous song")
	}
	var paused, playing []byte
	onMainThread(func() { playing = menuBarButtonArtwork(status.button) })
	activate(menuBarToggle)
	onMainThread(func() { paused = menuBarButtonArtwork(status.button) })
	if a.pl.Playing() || bytes.Equal(playing, paused) {
		t.Fatal("play/pause did not pause and refresh the status image")
	}
	activate(menuBarToggle)
	click(controlX(menuBarToggle), menuBarLeftUp, 0)
	if !a.pl.Playing() {
		t.Fatal("middle button did not resume exactly once per click")
	}
	activate(menuBarNext)
	if a.pl.Current().ID != "first" {
		t.Fatal("next button did not select the next song")
	}
	for _, want := range []bool{false, true} {
		activate(menuBarLike)
		onMainThread(wait(t, callbacks, "the platform like result"))
		liked, err := platform.IsLiked([]string{"first"})
		if err != nil || liked["first"] != want || status.controls.liked != want {
			t.Fatalf("heart wanted liked=%v; platform=%v, menu bar=%v, err=%v", want, liked, status.controls.liked, err)
		}
		if len(a.library.liked) != 0 || len(a.library.likedList) != 0 {
			t.Fatal("platform heart also changed local likes")
		}
	}
	click(controlX(menuBarNext), menuBarLeftDown, menuBarCommandKey)
	onMainThread(func() { sendStatusBarContextAction(status) })
	click(controlX(menuBarNext), menuBarLeftDown, menuBarControlKey)
	click(controlX(menuBarNext), menuBarLeftUp, menuBarControlKey)
	if contextClicks != 2 || a.pl.Current().ID != "first" {
		t.Fatalf("right-click and control-click dispatched %d context actions", contextClicks)
	}
	onMainThread(func() {
		a.closeMenuBar()
		if _, found := menuBarTargets[status.target]; found {
			err = fmt.Errorf("closed menu bar still has a live target")
		}
	})
	if err != nil || a.menuBar != nil {
		t.Fatalf("status item was not removed: %v", err)
	}
}

func waitForStatusBarButton(button objc.ID) {
	loop := statusSend(statusClass("NSRunLoop"), "currentRunLoop")
	mode := statusString("kCFRunLoopDefaultMode")
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline) && objc.Send[nsRect](button, objc.RegisterName("bounds")).Size.H == 0; {
		date := statusSend(statusClass("NSDate"), "dateWithTimeIntervalSinceNow:", 0.02)
		statusSend(loop, "runMode:beforeDate:", mode, date)
	}
}

func menuBarScreenPoint(status *darwinMenuBar, imageX float64) nsPoint {
	imageRect := status.artworkRect()
	point := nsPoint{X: imageRect.Origin.X + imageX, Y: imageRect.Origin.Y + imageRect.Size.H/2}
	windowPoint := objc.Send[nsPoint](status.button, objc.RegisterName("convertPoint:toView:"), point, objc.ID(0))
	return objc.Send[nsPoint](statusSend(status.button, "window"), objc.RegisterName("convertPointToScreen:"), windowPoint)
}

func sendStatusBarContextAction(status *darwinMenuBar) {
	defer statusPool()()
	application := statusSend(statusClass("NSApplication"), "sharedApplication")
	window := statusSend(status.button, "window")
	number := objc.Send[int](window, objc.RegisterName("windowNumber"))
	point := objc.Send[nsPoint](window, objc.RegisterName("convertPointFromScreen:"), menuBarScreenPoint(status, 10))
	event := statusSend(statusClass("NSEvent"), "mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:pressure:",
		uint(menuBarRightUp), point, uint(0), float64(0), number, objc.ID(0), 0, 1, float32(0))
	statusSend(application, "postEvent:atStart:", event, true)
	input := statusSend(application, "nextEventMatchingMask:untilDate:inMode:dequeue:", uint(1)<<menuBarRightUp,
		statusSend(statusClass("NSDate"), "distantPast"), statusString("kCFRunLoopDefaultMode"), true)
	if input != 0 {
		statusSend(application, "sendAction:to:from:", statusSend(status.button, "action"), statusSend(status.button, "target"), status.button)
	}
}

func checkStatusArtwork(status *darwinMenuBar) error {
	if statusSend(status.item, "button") != status.button || statusSend(status.button, "window") == 0 || statusSend(status.button, "image") == 0 {
		return fmt.Errorf("the live AppKit status button does not own the composed image")
	}
	artwork := status.artworkRect()
	bounds := objc.Send[nsRect](status.button, objc.RegisterName("bounds"))
	last := status.layout.controlBounds(menuBarLike).Max.X
	if artwork.Origin.X < 0 || artwork.Origin.X+float64(last) > bounds.Size.W || artwork.Size.W != float64(status.layout.width()) || artwork.Size.H != menuBarHeightPoints || artwork.Origin.Y < 0 || artwork.Origin.Y+artwork.Size.H > bounds.Size.H {
		return fmt.Errorf("AppKit clipped the control artwork: image %v, button %v, last control ends at %d", artwork, bounds, last)
	}
	return nil
}

func menuBarButtonArtwork(button objc.ID) []byte {
	defer statusPool()()
	bitmap := statusSend(statusClass("NSBitmapImageRep"), "imageRepWithData:", statusSend(statusSend(button, "image"), "TIFFRepresentation"))
	data := statusSend(bitmap, "representationUsingType:properties:", uint(4), statusSend(statusClass("NSDictionary"), "dictionary"))
	length := objc.Send[int](data, objc.RegisterName("length"))
	pixels := objc.Send[*byte](data, objc.RegisterName("bytes"))
	return bytes.Clone(unsafe.Slice(pixels, length))
}

func assertMenuBarControlPixels(t *testing.T, img image.Image, layout menuBarLayout, dark bool) {
	t.Helper()
	if img.Bounds().Dx() != layout.width()*menuBarScale || img.Bounds().Dy() != menuBarHeightPoints*menuBarScale {
		t.Fatalf("status button image has unexpected resolution: %v", img.Bounds())
	}
	for action := menuBarPrevious; action < menuBarContext; action++ {
		bounds := layout.controlBounds(action)
		visible := 0
		for x := bounds.Min.X * menuBarScale; x < bounds.Max.X*menuBarScale; x++ {
			for y := bounds.Min.Y * menuBarScale; y < bounds.Max.Y*menuBarScale; y++ {
				red, green, blue, alpha := img.At(x, y).RGBA()
				if alpha >= 0x5000 && red == green && green == blue && (dark && red == alpha || !dark && red == 0) {
					visible++
				}
			}
		}
		if visible < 30 {
			t.Fatalf("control %d is missing in the native image in dark=%v: %d visible pixels", action, dark, visible)
		}
	}
}
