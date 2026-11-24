package app

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"os"
	"testing"
	"unsafe"

	"github.com/ebitengine/purego/objc"
	"github.com/egoist/mygo"
	"mygo-music/internal/music"
)

func TestMenuBarNativeControls(t *testing.T) {
	a := newTestApp()
	a.account = &music.Account{ID: "listener"}
	requests := make(chan string, 1)
	fake(a).like = func(id string, on bool) error {
		if on {
			requests <- id
		}
		return nil
	}
	a.pl.Cue([]music.Track{song("first", "First"), song("last", "Last")})
	a.pl.Changed = a.updateMenuBarControls
	var status *darwinMenuBar
	var opened, contextClicks int
	var err error
	var frames [2][]byte
	onMainThread(func() {
		mygo.RunOnMain(func() {})
		defer statusPool()()
		statusSend(statusClass("NSApplication"), "sharedApplication")
		created, createErr := newMenuBarStatus(func(action menuBarAction) {
			switch action {
			case menuBarOpen:
				opened++
			case menuBarContext:
				contextClicks++
			default:
				a.runMenuBarAction(action)
			}
		}, func() {})
		if createErr != nil {
			err = createErr
			return
		}
		status = created.(*darwinMenuBar)
		a.menuBar = status
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { onMainThread(a.closeMenuBar) })
	onMainThread(func() {
		defer statusPool()()
		a.updateMenuBarControls()
		var render menuBarRenderer
		for index, width := range []int{240, 190} {
			dark := index == 0
			name := "NSAppearanceNameAqua"
			if dark {
				name = "NSAppearanceNameDarkAqua"
			}
			appearance := statusSend(statusClass("NSAppearance"), "appearanceNamed:", statusString(name))
			statusSend(status.backing, "setAppearance:", appearance)
			status.syncAppearance()
			if status.dark() != dark {
				err = fmt.Errorf("status appearance did not switch to %s", name)
				return
			}
			frame, _ := render.frame("九三 - 我大步向前", 0, width, menuBarStyle{dark: dark})
			status.setFrame(frame, width)
			if err = checkMenuBarHits(status); err != nil {
				return
			}
			if os.Getenv("MM_FRAMES") != "" {
				frames[index] = captureMenuBar(status.capsule)
			}
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	click := func(action menuBarAction) {
		onMainThread(func() { clickNativeMenuBarButton(status.buttons[action]) })
	}
	click(menuBarPrevious)
	if a.pl.Current().ID != "last" || !a.pl.Playing() {
		t.Fatal("previous button did not select the previous song")
	}
	var pauseIcon, playIcon objc.ID
	onMainThread(func() { pauseIcon = statusSend(status.buttons[menuBarToggle], "image") })
	click(menuBarToggle)
	onMainThread(func() { playIcon = statusSend(status.buttons[menuBarToggle], "image") })
	if a.pl.Playing() || pauseIcon == 0 || playIcon == 0 || pauseIcon == playIcon {
		t.Fatalf("middle button state: playing %v, controls %+v, pause image %v, play image %v", a.pl.Playing(), status.controls, pauseIcon, playIcon)
	}
	click(menuBarToggle)
	if !a.pl.Playing() {
		t.Fatal("middle button did not resume")
	}
	click(menuBarNext)
	if a.pl.Current().ID != "first" {
		t.Fatal("next button did not select the next song")
	}
	click(menuBarLike)
	if id := wait(t, requests, "like"); id != "first" || !status.controls.liked {
		t.Fatal("heart button did not like the current song")
	}
	click(menuBarOpen)
	onMainThread(func() { statusSend(status.buttons[menuBarOpen], "rightMouseDown:", objc.ID(0)) })
	if opened != 1 || contextClicks != 1 {
		t.Fatalf("app button dispatched %d open and %d context actions", opened, contextClicks)
	}
	onMainThread(func() {
		a.closeMenuBar()
		for _, button := range status.buttons {
			if _, found := menuBarTargets[button]; found {
				err = fmt.Errorf("closed menu bar still has live button targets")
			}
		}
	})
	if err != nil || a.menuBar != nil {
		t.Fatalf("status item was not removed: %v", err)
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		backgrounds := [...]color.RGBA{{121, 99, 76, 255}, {237, 230, 221, 255}}
		for index, name := range []string{"menubar-native-dark.png", "menubar-native-light.png"} {
			frame := menuBarImage(t, frames[index])
			canvas := image.NewRGBA(frame.Bounds().Inset(-8))
			draw.Draw(canvas, canvas.Bounds(), image.NewUniform(backgrounds[index]), image.Point{}, draw.Src)
			draw.Draw(canvas, frame.Bounds(), frame, image.Point{}, draw.Over)
			saveFrame(t, dir, name, canvas)
		}
	}
}

func clickNativeMenuBarButton(button objc.ID) {
	defer statusPool()()
	application := statusSend(statusClass("NSApplication"), "sharedApplication")
	window := statusSend(button, "window")
	number := objc.Send[int](window, objc.RegisterName("windowNumber"))
	frame := objc.Send[nsRect](button, objc.RegisterName("bounds"))
	point := objc.Send[nsPoint](button, objc.RegisterName("convertPoint:toView:"), nsPoint{frame.Size.W / 2, frame.Size.H / 2}, objc.ID(0))
	event := func(typ uint) objc.ID {
		return statusSend(statusClass("NSEvent"), "mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:eventNumber:clickCount:pressure:",
			typ, point, uint(0), float64(0), number, objc.ID(0), 0, 1, float32(1))
	}
	statusSend(application, "postEvent:atStart:", event(2), true)
	statusSend(button, "mouseDown:", event(1))
}

func checkMenuBarHits(status *darwinMenuBar) error {
	root := status.backing
	parent := statusSend(root, "superview")
	position := func(view objc.ID, point nsPoint) nsPoint {
		return objc.Send[nsPoint](view, objc.RegisterName("convertPoint:toView:"), point, parent)
	}
	lyricFrame := objc.Send[nsRect](status.lyric, objc.RegisterName("bounds"))
	end := position(status.lyric, nsPoint{X: lyricFrame.Size.W}).X
	for action := menuBarPrevious; action <= menuBarOpen; action++ {
		button := status.buttons[action]
		frame := objc.Send[nsRect](button, objc.RegisterName("bounds"))
		start := position(button, nsPoint{}).X
		if start < end || frame.Size.W <= 0 {
			return fmt.Errorf("button %d overlaps the preceding lyric or button: x=%v, previous end=%v", action, start, end)
		}
		point := position(button, nsPoint{frame.Size.W / 2, frame.Size.H / 2})
		if hit := statusSend(root, "hitTest:", point); hit != button {
			bounds := objc.RegisterName("bounds")
			return fmt.Errorf("button %d not hit at %v: got %v, want %v, root %v, status %v, capsule %v, button %v", action, point, hit, button,
				objc.Send[nsRect](root, bounds), objc.Send[nsRect](status.backing, bounds), objc.Send[nsRect](status.capsule, bounds), frame)
		}
		if statusSend(button, "image") == 0 {
			return fmt.Errorf("button %d has no icon", action)
		}
		end = start + frame.Size.W
	}
	return nil
}

func captureMenuBar(view objc.ID) []byte {
	bounds := objc.Send[nsRect](view, objc.RegisterName("bounds"))
	bitmap := statusSend(view, "bitmapImageRepForCachingDisplayInRect:", bounds)
	statusSend(view, "cacheDisplayInRect:toBitmapImageRep:", bounds, bitmap)
	data := statusSend(bitmap, "representationUsingType:properties:", uint(4), statusSend(statusClass("NSDictionary"), "dictionary"))
	length := objc.Send[int](data, objc.RegisterName("length"))
	bytes := objc.Send[*byte](data, objc.RegisterName("bytes"))
	return append([]byte(nil), unsafe.Slice(bytes, length)...)
}
