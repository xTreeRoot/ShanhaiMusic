package app

type menuBarAction int

const (
	menuBarPrevious menuBarAction = iota
	menuBarToggle
	menuBarNext
	menuBarLike
	menuBarOpen
	menuBarContext
)

type menuBarControls struct {
	hasTrack, playing, canLike, liked bool
}

type menuBarStatus interface {
	dark() bool
	setFrame([]byte, int)
	setToolTip(string)
	setControls(menuBarControls)
	refreshLabels()
	close()
}

func (a *app) updateMenuBarControls() {
	if a.menuBar == nil {
		return
	}
	var controls menuBarControls
	if a.pl != nil {
		if track := a.pl.Current(); track != nil {
			controls = menuBarControls{true, a.pl.Playing(), a.trackLib(track).canLike(track), a.isLiked(track)}
		}
	}
	a.menuBar.setControls(controls)
}

func (a *app) runMenuBarAction(action menuBarAction) {
	switch action {
	case menuBarOpen:
		a.showMain()
		return
	case menuBarContext:
		a.trayMenu().Popup(nil)
		return
	}
	if a.pl == nil || a.pl.Current() == nil {
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
		a.likeCurrent()
	}
	a.updateTrayLyric()
	a.redraw()
}
