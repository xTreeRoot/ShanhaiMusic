package app

import (
	"log"
	"runtime"
	"time"

	"github.com/egoist/mygo"
)

func (a *app) updateTrayLyric() {
	if runtime.GOOS != "darwin" || a.tray == nil {
		return
	}
	a.syncMenuBarVideo()
	content := a.menuBarContent()
	style := menuBarStyle{video: a.settings.MenuBarVideo, dark: a.tray.Dark(), preview: a.trayVideo.picture}
	if a.settings.MenuBarLyrics {
		a.trayControls.update(a.menuBarTransport())
	}
	if frame, changed := a.trayLyrics.frame(content.text, content.elapsed, menuBarWidth(a.settings.MenuBarLyricsWidth), a.settings.MenuBarLyrics, style); changed {
		if err := a.tray.SetIcon(frame, !a.settings.MenuBarLyrics); err != nil {
			log.Println("menu bar lyrics:", err)
		}
	}
	tip := content.tip
	if tip != a.trayTip {
		a.tray.SetToolTip(tip)
		a.trayTip = tip
	}
}

// syncPlaybackExtras runs independently of visible views, so lyrics keep moving
// with the main window hidden. It polls only while there is something to update.
func (a *app) syncPlaybackExtras() {
	if a.quitting || a.pl == nil {
		return
	}
	a.syncInlineMV()
	a.updateTrayLyric()
	a.syncMV()
	needed := runtime.GOOS == "darwin" && a.tray != nil && a.settings.MenuBarLyrics && (a.pl.Playing() || a.trayVideo.needsFrames())
	if !needed || a.extrasTick {
		return
	}
	a.extrasTick = true
	time.AfterFunc(menuBarFrameInterval, func() {
		mygo.RunOnMain(func() {
			a.extrasTick = false
			a.syncPlaybackExtras()
		})
	})
}
