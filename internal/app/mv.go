package app

import (
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/mbvideo"
	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
)

type mvState struct {
	win            *mygo.Window
	video          *ui.VideoPlayer
	proxy          *mvProxy
	key            string
	gen            int
	loading        bool
	inline         bool
	ticking        bool
	err            string
	large          bool
	openGen        int
	openPending    bool
	detailTab      int
	hideSidebar    bool
	queueList      ui.ListState
	queueAt        int
	aboutList      ui.ListState
	author         *videoAuthorState
	commentsScroll ui.ScrollState
	pinned         bool
	lastSeek       time.Time
	refreshUsed    bool
	windowAspect   float64
	scrub          scrubState
}

func (m *mvState) release() {
	m.gen++
	if m.video != nil {
		m.video.Close()
		m.video = nil
	}
	m.proxy.Close()
	m.proxy = nil
	m.loading = false
	m.lastSeek = time.Time{}
}

func (a *app) canMV() bool {
	track := a.pl.Current()
	if track == nil {
		return false
	}
	src := a.sourceByID(track.Source)
	_, ok := src.(music.VideoSource)
	return ok && src.Can("video")
}

func (a *app) openMV() {
	if a.settings.PlaybackMode == playbackVideo {
		a.requestVideoWindow()
		return
	}
	a.post(func() {
		if !a.quitting {
			a.openMVWindow(false)
		}
	})
}

func (a *app) openMVWindow(large bool) {
	m := &a.mv
	if m.win != nil && m.large == large {
		m.win.Show()
		m.win.Focus()
		return
	}
	if a.win == nil || !large && !a.canMV() {
		return
	}
	// Transfer the decoder only after the old native view is destroyed. Its
	// OnClosed callback must not release the player the new window will own.
	if old := m.win; old != nil {
		m.win = nil
		old.Close()
	}
	m.large, m.inline, m.pinned = large, false, !large
	m.windowAspect = 0
	opts := mygo.WindowOptions{
		Title: T("MV Mini Player"), Width: 640, Height: 360,
		MinWidth: 160, MinHeight: 90, StateKey: "mv-player",
		Frameless: true, CornerRadius: 18, BackgroundColor: "#000000",
		AlwaysOnTop: true, AutoHideMenuBar: true,
		Content: ui.View(a.mvView),
	}
	if large {
		m.hideSidebar, m.detailTab, m.queueAt = false, videoTabQueue, -1
		m.queueList, m.aboutList = ui.ListState{}, ui.ListState{}
		opts = mygo.WindowOptions{
			Title: T("Video Playback"), Width: 1180, Height: 740,
			MinWidth: 160, MinHeight: 90, StateKey: "video-player",
			TitleBarStyle: mygo.TitleBarHidden, TitleBarHeight: 52,
			TrafficLightPosition: &mygo.Point{X: 18, Y: 18},
			BackgroundColor:      "#141416", AutoHideMenuBar: true,
			Content: ui.View(a.videoWindowView),
		}
	}
	win := mygo.NewWindow(opts)
	m.win = win
	win.OnClosed(func() { a.closedMV(win) })
	win.OnEnterFullScreen(a.redraw)
	win.OnLeaveFullScreen(a.redraw)
	a.syncDock()
	a.syncMV()
	a.redraw()
}

func (a *app) closedMV(win *mygo.Window) {
	m := &a.mv
	if m.win != win {
		return
	}
	m.win = nil
	m.openGen++
	m.openPending = false
	m.author = nil
	m.release()
	m.key = ""
	m.large = false
	a.pl.HoldForVideo(false)
	a.syncInlineMV()
	a.syncDock()
	a.redraw()
}

// loadMV rejects answers from a closed window or a track already skipped past.
func (a *app) loadMV(key string) {
	m := &a.mv
	if m.key != key {
		m.aboutList, m.commentsScroll = ui.ListState{}, ui.ScrollState{}
		m.refreshUsed = false
	}
	m.release()
	m.key, m.err = key, ""
	if !a.canMV() {
		if a.pl.Current() == nil {
			m.err = T("Choose a track to watch from Home or your library.")
		} else {
			m.err = T("This source does not provide video playback.")
		}
		return
	}
	track := *a.pl.Current()
	source := a.sourceByID(track.Source).(music.VideoSource)
	quality, gen := a.settings.Quality, m.gen
	m.loading = true
	go func() {
		stream, err := source.Video(track.ID, quality)
		var proxy *mvProxy
		if err == nil {
			proxy, err = newMVProxy(stream)
		}
		a.post(func() {
			if m.gen != gen || m.key != key || m.win == nil && !m.inline {
				proxy.Close()
				return
			}
			m.loading = false
			if err != nil {
				m.err = T("Couldn't load the MV. Try again.")
				return
			}
			player, err := video.OpenURL(proxy.url)
			if err != nil {
				proxy.Close()
				m.err = T("Couldn't load the MV. Try again.")
				return
			}
			m.proxy, m.video = proxy, player.SetMuted(true)
			m.video.Seek(a.pl.Position())
			a.syncMV()
		})
	}()
}

// mvSync chooses a seek only for real drift, avoiding an endless seek while
// the video is buffering or a seek is still completing.
func mvSync(audioPos, videoPos time.Duration, playing, buffering bool) (seek bool, rate float64) {
	drift := audioPos - videoPos
	tolerance := 600 * time.Millisecond
	if !playing {
		// A paused picture cannot converge by changing its playback rate.
		tolerance = 80 * time.Millisecond
	}
	if drift > tolerance || drift < -tolerance {
		return !buffering, 1
	}
	if playing && !buffering {
		if drift > 80*time.Millisecond {
			return false, 1.03
		}
		if drift < -80*time.Millisecond {
			return false, 0.97
		}
	}
	return false, 1
}

func (a *app) syncMV() {
	m := &a.mv
	if a.quitting || m.win == nil && !m.inline {
		a.pl.HoldForVideo(false)
		return
	}
	key := ""
	if track := a.pl.Current(); track != nil {
		key = track.Source + "\x00" + track.ID
	}
	if key != m.key || m.video == nil && !m.loading && m.err == "" {
		a.loadMV(key)
	}
	if m.video != nil && m.video.Err() != nil && m.proxy != nil && m.proxy.refreshable.Load() && !m.refreshUsed {
		m.refreshUsed = true
		a.loadMV(key)
	}
	resync := false
	hold := (m.large || m.inline) && (m.loading || m.video != nil && m.video.Err() == nil && (!m.video.Ready() || m.video.Buffering()))
	if m.video != nil && m.video.Ready() {
		playing := a.pl.Playing() && !a.pl.Loading()
		seek, rate := mvSync(a.pl.Position(), m.video.Position(), playing, m.video.Buffering())
		resync = seek
		if seek && time.Since(m.lastSeek) >= time.Second {
			m.video.Seek(a.pl.Position())
			m.lastSeek = time.Now()
		}
		if m.large || m.inline {
			hold = hold || seek
		}
		if m.video.Rate() != rate {
			m.video.SetRate(rate)
		}
		if playing != m.video.Playing() {
			if playing {
				m.video.Play()
			} else {
				m.video.Pause()
			}
		}
	}
	a.pl.HoldForVideo(hold)
	if m.win != nil {
		a.syncVideoWindowAspect()
		m.win.Invalidate()
	}
	if m.inline && a.win != nil {
		a.win.Invalidate()
	}
	if !m.ticking && (m.loading || m.video != nil && m.video.Err() == nil && (a.pl.Playing() || resync || !m.video.Ready() || m.video.Buffering())) {
		m.ticking = true
		time.AfterFunc(200*time.Millisecond, func() {
			mygo.RunOnMain(func() {
				m.ticking = false
				a.syncMV()
			})
		})
	}
}

func (a *app) mvView(c *ui.Context) {
	width, height := c.Size()
	if compactVideoWindow(width, height) {
		a.compactMVView(c)
		return
	}
	m := &a.mv
	white := ui.RGB(255, 255, 255)
	black := ui.RGBA(0, 0, 0, 115)
	accent := ui.RGB(255, 75, 105)
	volumeWidth := float32(105)
	if width, _ := c.Size(); m.large && width < 600 {
		volumeWidth = 72
	}
	root := ui.Box(c).Fill().Background(ui.RGB(0, 0, 0))
	controlsVisible := root.Hovered() || !a.pl.Playing() || m.video == nil || !m.video.Ready()
	visibility := float32(0)
	if controlsVisible {
		visibility = 1
	}
	visibility = root.AnimateTo("mv-controls", visibility, ui.Snappy)
	c.OnShortcut(0, ui.KeySpace, func() { a.pl.TogglePlay() })
	c.OnShortcut(0, ui.KeyEscape, func() {
		if m.win != nil {
			m.win.SetFullScreen(false)
		}
	})
	root.Children(func() {
		if m.video != nil {
			ui.Video(c.Key("mv"), m.video).Fill().Fit(ui.Contain).Label(T("Music Video"))
		}
		if m.loading || m.video != nil && m.video.Err() == nil && (!m.video.Ready() || m.video.Buffering()) {
			videoLoading(c, m.video != nil && m.video.Ready())
		}
		failure := m.err
		if m.video != nil && m.video.Err() != nil {
			failure = T("Couldn't load the MV. Try again.")
		}
		if failure != "" {
			ui.Column(c).Absolute().Left(24).Right(24).Top(70).Bottom(80).Center().Gap(12).Children(func() {
				ui.Text(c, failure).TextColor(white).TextAlign(ui.Center)
				if a.canMV() && ui.Button(c, T("Retry")).Clicked() {
					a.loadMV(m.key)
				}
			})
		}
		// Large windows already have a native header above their picture.
		if !m.large {
			ui.Row(c.Key("mv-header")).Absolute().Top(0).Left(0).Right(0).Height(48).PaddingX(12).Gap(8).Opacity(visibility).IgnoreInput(!controlsVisible).
				Background(black).AlignItems(ui.Center).DragWindow().Children(func() {
				if iconButton(c.Key("close"), iconX, 15, white, T("Close MV")).Clicked() && m.win != nil {
					m.win.Close()
				}
				if iconButton(c.Key("main"), iconHome, 17, white, T("Show Main Window")).Clicked() {
					a.showMain()
				}
				title := T("MV Mini Player")
				if track := a.pl.Current(); track != nil {
					title = track.Name
				}
				ui.Text(c, title).Grow(1).Shrink(1).MinWidth(0).SingleLine().FontSize(13).TextColor(white)
				color := white
				if m.pinned {
					color = ui.RGB(255, 100, 115)
				}
				if iconButton(c.Key("pin"), iconPin, 17, color, T("Keep MV on Top")).Clicked() {
					m.pinned = !m.pinned
					if m.win != nil {
						m.win.SetAlwaysOnTop(m.pinned)
					}
				}
				if iconButton(c.Key("expand"), iconExpand, 17, white, T("Full Screen MV")).Clicked() && m.win != nil {
					m.win.ToggleFullScreen()
				}
			})
		}
		if failure == "" {
			ui.Row(c.Key("mv-transport")).Absolute().Left(0).Right(0).TopPercent(40).Justify(ui.Center).Gap(22).AlignItems(ui.Center).Opacity(visibility).IgnoreInput(!controlsVisible).Children(func() {
				mvControl(c.Key("previous"), iconPrev, 25, 48, T("Previous"), func() { a.pl.Prev() })
				icon, label := iconPlay, T("Play")
				if a.pl.Playing() {
					icon, label = iconPause, T("Pause")
				}
				mvControl(c.Key("play"), icon, 32, 64, label, a.pl.TogglePlay)
				mvControl(c.Key("next"), iconNext, 25, 48, T("Next"), func() { a.pl.Next() })
			})
		}
		ui.Column(c.Key("mv-footer")).Absolute().Left(0).Right(0).Bottom(0).Padding(8, 18, 10, 18).Gap(3).Background(black).Opacity(visibility).IgnoreInput(!controlsVisible).Children(func() {
			lines, _ := a.pl.Lyrics()
			if line := currentLyric(lines, a.pl.Position()); line != "" {
				ui.Text(c, line).TextColor(white).FontSize(13).TextAlign(ui.Center).SingleLine().AlignSelf(ui.Stretch)
			}
			a.scrubber(c.Key("mv-progress"), &m.scrub, white.Alpha(0.25), accent, 14, ui.Color{}).AlignSelf(ui.Stretch)
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Text(c, clock(a.pl.Position())+" / "+clock(a.pl.Duration())).FontSize(11).FontFeatures("tnum").TextColor(white)
				ui.Box(c).Grow(1)
				if track := a.pl.Current(); track != nil {
					if m.large {
						a.transport(c.Key("mv-footer-transport"), white, white.Alpha(0.65), accent, 18)
					}
					a.likeButton(c.Key("mv-like"), track, 17, white)
				}
				a.volumeSlider(c.Key("mv-volume"), white, white.Alpha(0.25), volumeWidth)
				if m.large {
					iconButton(c.Key("mv-full"), iconExpand, 17, white, T("Full Screen MV")).OnClick(func() {
						if m.win != nil {
							m.win.ToggleFullScreen()
						}
					})
				}
			})
		})
	})
}

func mvControl(c *ui.Context, icon *ui.SVG, iconSize, size float32, label string, action func()) {
	b := ui.ButtonBase(c).Size(size, size).Radius(size / 2).Center().Background(ui.RGBA(0, 0, 0, 110)).Label(label)
	b.Children(func() { ui.Icon(c, icon).Size(iconSize, iconSize).TextColor(ui.RGB(255, 255, 255)) })
	if b.Clicked() {
		action()
	}
}
