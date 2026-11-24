package app

import (
	"image"
	"log"
	"time"

	"github.com/egoist/mygo/mbvideo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

// The tray owns its decoder: MyGo requires a VideoPlayer to appear in only one
// view. This prevents the expanded player and tray from pausing each other.
type menuBarVideoState struct {
	video            *ui.VideoPlayer
	proxy            *mvProxy
	picture          *image.RGBA
	key              string
	track            *music.Track // a new load of the same track also refreshes its media
	gen              int
	loading          bool
	lastSeek         time.Time
	renderedAt       time.Time
	refreshUntil     time.Time
	renderedPosition time.Duration
}

func (m *menuBarVideoState) release() {
	m.gen++
	if m.video != nil {
		m.video.Close()
		m.video = nil
	}
	m.proxy.Close()
	m.proxy = nil
	m.picture = nil
	m.loading = false
	m.lastSeek = time.Time{}
	m.renderedAt = time.Time{}
	m.refreshUntil = time.Time{}
	m.renderedPosition = 0
}

// Keep sampling while a native seek or pause settles, even when audio is paused.
// Ready remains true during AVFoundation seeks, before the new pixels arrive.
func (m *menuBarVideoState) needsFrames() bool {
	return m.loading || m.video != nil && m.video.Err() == nil &&
		(!m.video.Ready() || m.video.Buffering() || time.Now().Before(m.refreshUntil))
}

func (a *app) menuBarVideoActive() bool {
	return !a.quitting && a.settings.MenuBarLyrics && a.settings.MenuBarVideo && a.canMV()
}

func (a *app) syncMenuBarVideo() {
	m := &a.trayVideo
	if !a.menuBarVideoActive() {
		if m.video != nil || m.loading || m.key != "" {
			m.release()
			m.key = ""
		}
		return
	}
	track := a.pl.Current()
	key := track.Source + "\x00" + track.ID
	if key != m.key || track != m.track {
		a.loadMenuBarVideo(key)
	}
	if m.video == nil {
		return
	}
	if err := m.video.Err(); err != nil {
		log.Println("menu bar video:", err)
		m.release()
		return
	}
	playing := a.pl.Playing() && !a.pl.Loading()
	if m.video.Ready() {
		seek, rate := mvSync(a.pl.Position(), m.video.Position(), playing, m.video.Buffering())
		if seek && time.Since(m.lastSeek) >= time.Second {
			m.video.Seek(a.pl.Position())
			m.lastSeek = time.Now()
			m.refreshUntil = m.lastSeek.Add(time.Second)
			m.renderedAt = time.Time{}
		}
		if m.video.Rate() != rate {
			m.video.SetRate(rate)
		}
		if playing != m.video.Playing() {
			m.refreshUntil = time.Now().Add(time.Second)
			if playing {
				m.video.Play()
			} else {
				m.video.Pause()
			}
		}
	}
	if !playing && !m.needsFrames() && m.video.Ready() && m.picture != nil && m.renderedPosition == m.video.Position() {
		return
	}
	if time.Since(m.renderedAt) < 100*time.Millisecond {
		return
	}
	m.picture = ui.Render(func(c *ui.Context) {
		c.Root().Background(ui.RGB(0, 0, 0))
		ui.Video(c, m.video).Fill().Fit(ui.Contain)
	}, menuBarVideoPoints, menuBarHeightPoints, menuBarScale)
	m.renderedAt = time.Now()
	m.renderedPosition = m.video.Position()
}

// Both the generation and active track are checked before accepting a load that
// may finish after a settings change, track skip, or app shutdown.
func (a *app) loadMenuBarVideo(key string) {
	m := &a.trayVideo
	m.release()
	m.key = key
	m.track = a.pl.Current()
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
			current := a.pl.Current()
			if m.gen != gen || !a.menuBarVideoActive() || current == nil || current.Source+"\x00"+current.ID != key {
				proxy.Close()
				return
			}
			m.loading = false
			if err != nil {
				log.Println("menu bar video:", err)
				return
			}
			player, err := video.OpenURL(proxy.url)
			if err != nil {
				proxy.Close()
				log.Println("menu bar video:", err)
				return
			}
			m.proxy, m.video = proxy, player.SetMuted(true)
			m.video.Pause()
			m.video.Seek(a.pl.Position())
			m.refreshUntil = time.Now().Add(time.Second)
			a.syncPlaybackExtras()
		})
	}()
}
