package app

import "github.com/egoist/mygo/ui"

// Small windows keep the picture unobstructed and overlay only the controls
// that fit. The same decoder and playhead survive resizing in either direction.
func (a *app) compactMVView(c *ui.Context) {
	m := &a.mv
	width, height := c.Size()
	root := ui.Box(c).Fill().Background(ui.RGB(0, 0, 0))
	visible := root.Hovered() || !a.pl.Playing() || m.video == nil || !m.video.Ready()
	alpha := float32(0)
	if visible {
		alpha = 1
	}
	alpha = root.AnimateTo("mv-controls", alpha, ui.Snappy)
	c.OnShortcut(0, ui.KeySpace, a.pl.TogglePlay)
	c.OnShortcut(0, ui.KeyEscape, func() {
		if m.win != nil {
			m.win.SetFullScreen(false)
		}
	})
	root.Children(func() {
		if m.video != nil {
			ui.Video(c.Key("mv"), m.video).Fill().Fit(ui.Contain).Label(T("Music Video"))
		}
		ui.Row(c.Key("compact-header")).Absolute().Top(0).Left(0).Right(0).Height(32).
			Padding(0, 4, 0, max(4, c.TitleBar().Left+4)).Gap(2).AlignItems(ui.Center).
			DragWindow().Opacity(alpha).IgnoreInput(!visible).Children(func() {
			if !m.large {
				iconButton(c.Key("close"), iconX, 13, white, T("Close MV")).Size(24, 24).OnClick(func() {
					if m.win != nil {
						m.win.Close()
					}
				})
			}
			ui.Box(c).Grow(1)
			iconButton(c.Key("main"), iconHome, 14, white, T("Show Main Window")).Size(24, 24).OnClick(a.showMain)
			iconButton(c.Key("full"), iconExpand, 14, white, T("Full Screen MV")).Size(24, 24).OnClick(func() {
				if m.win != nil {
					m.win.ToggleFullScreen()
				}
			})
		})
		ui.Column(c.Key("compact-body")).Absolute().Top(32).Bottom(18).Left(4).Right(4).Center().Gap(6).Children(func() {
			failure := m.err
			if m.video != nil && m.video.Err() != nil {
				failure = T("Couldn't load the MV. Try again.")
			}
			switch {
			case failure != "":
				if height >= 160 || !a.canMV() {
					ui.Text(c, failure).TextColor(white).FontSize(10).MaxLines(2).TextAlign(ui.Center)
				}
				if a.canMV() {
					ui.Button(c, T("Retry")).OnClick(func() { a.loadMV(m.key) })
				}
			case m.loading || m.video != nil && (!m.video.Ready() || m.video.Buffering()):
				ui.Spinner(c)
			default:
				ui.Row(c).Gap(8).AlignItems(ui.Center).Opacity(alpha).IgnoreInput(!visible).Children(func() {
					if width >= 240 {
						mvControl(c.Key("previous"), iconPrev, 18, 32, T("Previous"), a.pl.Prev)
					}
					icon, label := iconPlay, T("Play")
					if a.pl.Playing() {
						icon, label = iconPause, T("Pause")
					}
					mvControl(c.Key("play"), icon, 20, 32, label, a.pl.TogglePlay)
					if width >= 240 {
						mvControl(c.Key("next"), iconNext, 18, 32, T("Next"), a.pl.Next)
					}
				})
			}
		})
		a.scrubber(c.Key("mv-progress"), &m.scrub, white.Alpha(0.25), ui.RGB(255, 75, 105), 14, ui.Color{}).
			Absolute().Left(8).Right(8).Bottom(2).Opacity(alpha).IgnoreInput(!visible)
	})
}
