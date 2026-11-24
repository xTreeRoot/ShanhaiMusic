package app

import "github.com/egoist/mygo/ui"

const (
	videoSidebarMinWidth  = 1040
	videoSidebarMinHeight = 560
)

const (
	videoTabQueue = iota
	videoTabAbout
	videoTabComments
)

// videoWindowView keeps browsing in the main window and the picture, transport,
// description and comments in one independent desktop playback window.
func (a *app) videoWindowView(c *ui.Context) {
	m := &a.mv
	theme := ui.DarkTheme()
	c.SetTheme(theme)
	full := m.win != nil && m.win.IsFullScreen()
	width, height := c.Size()
	room := width >= videoSidebarMinWidth && height >= videoSidebarMinHeight
	sidebar := room && !m.hideSidebar && a.pl.Current() != nil
	ui.Column(c).Fill().Background(ui.RGB(20, 20, 22)).Children(func() {
		if !full {
			a.videoWindowHeader(c, room, sidebar)
		}
		ui.Row(c.Key("video-body")).Grow(1).MinHeight(0).AlignSelf(ui.Stretch).AlignItems(ui.Stretch).Children(func() {
			ui.Box(c.Key("picture")).Grow(1).Shrink(1).MinWidth(0).Children(func() { a.mvView(c) })
			if !full && sidebar {
				a.videoDetails(c)
			}
		})
	})
}

func (a *app) videoWindowHeader(c *ui.Context, room, sidebar bool) {
	m := &a.mv
	ui.Row(c.Key("video-header")).Height(52).AlignSelf(ui.Stretch).
		Padding(0, max(16, c.TitleBar().Right+12), 0, max(16, c.TitleBar().Left+12)).
		Gap(12).AlignItems(ui.Center).DragWindow().Children(func() {
		ui.Button(c, T("Show Main Window")).OnClick(a.showMain)
		title := T("Video Playback")
		if track := a.pl.Current(); track != nil {
			title = track.Name
		}
		ui.Text(c, title).Grow(1).Shrink(1).MinWidth(0).SingleLine().FontSize(13).TextColor(white.Alpha(0.7))
		if a.pl.Current() != nil && room {
			label, color := T("Show Sidebar"), white.Alpha(0.65)
			if sidebar {
				label, color = T("Hide Sidebar"), white
			}
			iconButton(c.Key("sidebar"), iconSidebar, 17, color, label).OnClick(func() {
				m.hideSidebar = sidebar
			})
		}
		pin := white.Alpha(0.65)
		if m.pinned {
			pin = ui.RGB(255, 100, 115)
		}
		iconButton(c.Key("pin"), iconPin, 17, pin, T("Keep MV on Top")).OnClick(func() {
			m.pinned = !m.pinned
			if m.win != nil {
				m.win.SetAlwaysOnTop(m.pinned)
			}
		})
		iconButton(c.Key("full"), iconExpand, 17, white.Alpha(0.8), T("Full Screen MV")).OnClick(func() {
			if m.win != nil {
				m.win.ToggleFullScreen()
			}
		})
	})
}

func (a *app) videoDetails(c *ui.Context) {
	m, track := &a.mv, a.pl.Current()
	lib := a.trackLib(track)
	ui.Column(c.Key("video-details")).Width(300).Shrink(0).Padding(16, 12, 0, 12).Gap(8).
		Background(ui.RGB(27, 27, 30)).Children(func() {
		labels := []string{T("Play Queue"), T("About")}
		if lib.can("comments") {
			labels = append(labels, T("Comments"))
		} else if m.detailTab == videoTabComments {
			m.detailTab = videoTabAbout
		}
		segmented(c.Key("detail-tabs"), &m.detailTab, labels...)
		switch m.detailTab {
		case videoTabQueue:
			a.queuePane(c, &m.queueList, &m.queueAt)
		case videoTabAbout:
			a.videoAbout(c, track)
		case videoTabComments:
			a.commentsPaneScroll(c, track, &m.commentsScroll)
		}
	})
}

// Slow networks keep a responsive view; there is no delay before showing data
// that is already ready. The text distinguishes initial load from buffering.
func videoLoading(c *ui.Context, buffering bool) {
	label := T("Loading…")
	if buffering {
		label = T("Buffering…")
	}
	ui.Column(c.Key("video-loading")).Absolute().Fill().Center().Gap(12).Children(func() {
		ui.Spinner(c)
		ui.Text(c, label).FontSize(13).TextColor(white.Alpha(0.8))
	})
}
