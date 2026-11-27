package app

import "mygo-music/internal/music"

type menuBarAction int

const (
	menuBarPrevious menuBarAction = iota
	menuBarToggle
	menuBarNext
	menuBarLike
	menuBarContext
)

type menuBarControls struct {
	hasTrack, playing, canLike, liked bool
}

func (controls menuBarControls) enabled(action menuBarAction) bool {
	if action == menuBarLike {
		return controls.canLike
	}
	return controls.hasTrack && action >= menuBarPrevious && action <= menuBarNext
}

type menuBarStatus interface {
	dark() bool
	setFrame([]byte, menuBarLayout, menuBarControls)
	setToolTip(string)
	refreshLabels()
	close()
}

func (a *app) menuBarLikeLibrary(track *music.Track) *library {
	if track == nil || track.Source == "" {
		return nil
	}
	lib := a.libOf(track.Source)
	if lib == nil || pluginOf(lib) == nil || (!lib.canLike(track) && !lib.can("isLiked")) {
		return nil
	}
	return lib
}

func (a *app) menuBarControlState() menuBarControls {
	if a.pl != nil {
		if track := a.pl.Current(); track != nil {
			controls := menuBarControls{hasTrack: true, playing: a.pl.Playing()}
			if lib := a.menuBarLikeLibrary(track); lib != nil {
				controls.canLike = lib.canLike(track) && (lib.signedOut() || !lib.likedPending[track.ID])
				controls.liked = lib.liked[track.ID]
			}
			return controls
		}
	}
	return menuBarControls{}
}

func (a *app) runMenuBarAction(action menuBarAction) {
	if action == menuBarContext {
		a.trayMenu().Popup(nil)
		return
	}
	if a.pl == nil {
		return
	}
	track := a.pl.Current()
	if track == nil {
		return
	}
	switch action {
	case menuBarPrevious:
		a.pl.Prev()
	case menuBarToggle:
		a.pl.TogglePlay()
	case menuBarNext:
		a.pl.Next()
	case menuBarLike:
		lib := a.menuBarLikeLibrary(track)
		if lib == nil || lib.likedPending[track.ID] && !lib.signedOut() {
			return
		}
		a.toggleLikeIn(lib, track)
	}
	a.updateTrayLyric()
	a.redraw()
}
