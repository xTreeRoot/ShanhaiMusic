package app

import (
	"time"

	"github.com/egoist/mygo"
)

func (a *app) updateTrayLyric() {
	if a.menuBar == nil || a.quitting {
		return
	}
	if a.pl != nil {
		track := a.pl.Current()
		if lib := a.menuBarLikeLibrary(track); lib != nil && lib.can("isLiked") && !lib.signedOut() {
			a.askLiked(lib, []string{track.ID})
		}
	}
	a.syncMenuBarVideo()
	content := a.menuBarContent()
	width := menuBarWidth(a.settings.MenuBarLyricsWidth)
	style := menuBarStyle{video: a.settings.MenuBarVideo, dark: a.menuBar.dark(), preview: a.trayVideo.picture, controls: a.menuBarControlState()}
	if frame, changed := a.trayLyrics.frame(content.text, content.elapsed, width, style); changed {
		a.menuBar.setFrame(frame, style.layout(width), style.controls)
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
