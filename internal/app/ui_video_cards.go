package app

import (
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

// videoTrackRow keeps a section's videos in landscape cards. A card starts
// playback in this section's queue, just as a track row does.
func (a *app) videoTrackRow(c *ui.Context, sec *music.Section, row browseRow, cols int) {
	ui.Grid(c).Columns(cols).GapX(20).Margin(0, 12, cardRowGap, 12).Children(func() {
		for i := row.from; i < row.to; i++ {
			track := &sec.Tracks[i]
			play := func() { a.playCatalogTrack(sec.Tracks, i) }
			a.videoTrackCard(c.Key(track.ID), track, play)
		}
	})
}

func (a *app) videoTrackCard(c *ui.Context, track *music.Track, play func()) {
	p := a.pal
	card := ui.ButtonBase(c).Column().Gap(8).AlignItems(ui.Stretch).Justify(ui.Start).
		Label(track.Name).FocusRing(false).ContextMenu(a.songItems(track, ""))
	hovered := card.Hovered()
	card.Children(func() {
		art := ui.Box(c).AspectRatio(16.0 / 9).Radius(10)
		if hovered {
			art.Shadow(0, 8, 20, -6, p.cardShade)
		}
		art.Children(func() {
			a.coverFill(c, track.Album.ImageURL, 600, 10)
			if track.Duration > 0 {
				ui.Box(c).Absolute().Bottom(8).Left(8).Radius(4).Padding(2, 5).Background(ui.RGBA(0, 0, 0, 0.7)).IgnoreInput(true).Children(func() {
					ui.Text(c, clock(time.Duration(track.Duration)*time.Millisecond)).FontSize(11).TextColor(white)
				})
			}
			if !track.Unavailable {
				a.coverPlayButton(c, hovered, play)
			}
		})
		ui.Row(c).Gap(4).AlignItems(ui.Start).Children(func() {
			ui.Text(c, track.Name).Grow(1).MinWidth(0).FontSize(13).FontWeight(500).MaxLines(2).LineHeight(1.35).Height(36)
			iconButton(c.Key("track-menu"), iconMore, 15, p.muted, Tf("Actions for %s", track.Name)).Menu(a.songItems(track, ""))
		})
		ui.Text(c, artistNames(track.Artists)).FontSize(12).TextColor(p.muted).SingleLine().Margin(-4, 0, 0, 0)
	})
	if card.Clicked() && !track.Unavailable {
		play()
	}
}
