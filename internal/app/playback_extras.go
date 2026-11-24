package app

import (
	"time"

	"github.com/egoist/mygo"
)

func (a *app) updateTrayLyric() {
	if a.menuBar == nil || a.quitting {
		return
	}
	a.syncMenuBarVideo()
	content := a.menuBarContent()
	width := menuBarWidth(a.settings.MenuBarLyricsWidth)
	style := menuBarStyle{video: a.settings.MenuBarVideo, dark: a.menuBar.dark(), preview: a.trayVideo.picture}
	a.updateMenuBarControls()
	if frame, changed := a.trayLyrics.frame(content.text, content.elapsed, width, style); changed {
		a.menuBar.setFrame(frame, style.imageWidth(width))
	}
	tip := content.tip
	if tip != a.trayTip {
		a.menuBar.setToolTip(tip)
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
	needed := a.menuBar != nil && (a.pl.Playing() || a.trayVideo.needsFrames())
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
