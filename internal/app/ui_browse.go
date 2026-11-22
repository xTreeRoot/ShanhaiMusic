package app

import (
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
)

// Pages a source makes up itself: what its Home shows, and any other, opened
// from a link, a section's "see all" or an entry it adds to the sidebar. A page
// goes in rows of a list, built only as they scroll into view: a library's
// albums can be thousands.

// browseData is what the app has of a page, kept as it loads anew.
type browseData struct {
	page    *music.Browse
	at      time.Time // when page came
	loading bool
	err     string
	next    string
	visited map[string]bool
}

// loadBrowse loads a page, unless it has, or again. One loaded longer than
// listsFresh ago is asked for anew, as it may change as the app plays, such
// as songs played lately, and shows as it was while it is.
func (a *app) loadBrowse(lib *library, page string, again bool) {
	if lib == nil {
		return
	}
	d := lib.browses[page]
	if d == nil {
		d = &browseData{}
		lib.browses[page] = d
	}
	if d.loading || d.page != nil && !again && time.Since(d.at) <= listsFresh {
		return
	}
	d.loading, d.err = true, ""
	gen := lib.accountGen
	go func() {
		b, err := answered(lib.src.Browse(page))
		a.post(func() {
			if lib.accountGen != gen {
				return
			}
			d.loading = false
			if err != nil {
				d.err = a.problem(lib, err, T("Couldn't load this page"))
				return
			}
			d.page, d.at = b, time.Now()
			d.visited = map[string]bool{page: true}
			d.next = browseContinuation(b, d.visited)
		})
	}()
}

// 详情单列表的旧式 morePage 是续页；首页多栏目仍保留“更多”入口。
func browseContinuation(page *music.Browse, visited map[string]bool) string {
	if page == nil || len(page.Sections) != 1 {
		return ""
	}
	section := &page.Sections[0]
	next := section.MorePage
	section.MorePage = ""
	count := len(section.Tracks) + len(section.Albums) + len(section.Artists) + len(section.Playlists) + len(section.Shows) + len(section.Stations) + len(section.Links)
	if count == 0 || visited[next] {
		return ""
	}
	return next
}

func (a *app) loadBrowseNext(lib *library, d *browseData) {
	if d.loading || d.next == "" {
		return
	}
	id, gen := d.next, lib.accountGen
	d.loading, d.err = true, ""
	go func() {
		page, err := answered(lib.src.Browse(id))
		a.post(func() {
			if lib.accountGen != gen {
				return
			}
			d.loading = false
			if err != nil {
				d.err = a.problem(lib, err, T("Couldn't load this page"))
				return
			}
			if d.visited == nil {
				d.visited = map[string]bool{}
			}
			d.visited[id] = true
			d.next = browseContinuation(page, d.visited)
			d.page.Sections = append(d.page.Sections, page.Sections...)
		})
	}()
}

func (a *app) browsePage(c *ui.Context) {
	lib := a.library
	id := a.route.id
	key := id
	retry := func() { a.loadBrowse(lib, id, true) }
	if a.route.page == pageDiscoveryCollection {
		key = discoveryCollectionKey(id)
		retry = func() { a.loadDiscoveryCollection(lib, id) }
	}
	d := lib.browses[key]
	if d == nil || d.page == nil {
		if d != nil && !d.loading {
			a.centered(c, d.err, retry)
			return
		}
		a.trackSkeleton(c, a.pageShimmer(c), true, func(c *ui.Context) {})
		return
	}
	a.browseRows(c, d.page, id, func(c *ui.Context) { a.browseHeader(c, d.page) }, func(c *ui.Context) {
		if d.err != "" {
			ui.Text(c, d.err).TextColor(a.pal.muted)
			ui.Button(c, T("Retry")).OnClick(func() { a.loadBrowseNext(lib, d) })
		}
	})
	if d.err == "" && d.next != "" && !d.loading && a.trackList.AtEnd() {
		a.loadBrowseNext(lib, d)
	}
}

// browseHeader heads a page the source made up: its title, and what it says
// of it, a line to each line. With a picture, it's of someone, and headed as
// an artist's page is.
func (a *app) browseHeader(c *ui.Context, page *music.Browse) {
	p := a.pal
	var lines []string
	for line := range strings.SplitSeq(page.Description, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			lines = append(lines, line)
		}
	}
	about := func() {
		for i, line := range lines {
			t := ui.Text(c.Key(i), line).FontSize(13).TextColor(p.muted).MaxLines(2).LineHeight(1.45)
			if i == 0 {
				t.Margin(2, 0, 0, 0)
			}
		}
	}
	if page.ImageURL == "" {
		if len(lines) == 0 {
			if page.Title != "" {
				a.heading(c, page.Title, page.Subtitle)
			}
			return
		}
		ui.Column(c).Gap(4).Margin(0, 0, 18, 0).Children(func() {
			if page.Subtitle != "" {
				ui.Text(c, page.Subtitle).FontSize(12).FontWeight(600).TextColor(p.muted).LetterSpacing(0.4)
			}
			ui.Text(c, page.Title).FontSize(30).FontWeight(700).LetterSpacing(-0.6)
			about()
		})
		return
	}
	// As far below as an artist's sections are, as its first title keeps
	// none of its own.
	ui.Row(c).Gap(28).AlignItems(ui.End).Margin(0, 0, 34, 0).Children(func() {
		ui.Box(c).Size(artistPhotoSize, artistPhotoSize).Radius(artistPhotoSize/2).Shadow(0, 18, 40, -10, p.cardShade).Children(func() {
			a.coverFill(c, page.ImageURL, 600, artistPhotoSize/2)
		})
		ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(8).Children(func() {
			if page.Subtitle != "" {
				ui.Text(c, page.Subtitle).FontSize(12).FontWeight(600).TextColor(p.accent)
			}
			ui.Text(c, page.Title).FontSize(30).FontWeight(800).LetterSpacing(-0.6).MaxLines(2).LineHeight(1.2)
			about()
		})
	})
}

// A browseRow is a row of a page: a section's title, or its tracks one to a
// row, or its cards so many to a row.
type browseRow struct {
	kind     rowKind
	section  int
	from, to int
}

type rowKind int

const (
	rowFeature rowKind = iota
	rowTitle
	rowTrack
	rowVideos
	rowAlbums
	rowArtists
	rowPlaylists
	rowShows
	rowStations
	rowLinks
	rowEmpty
)

// browseLayout lays out page in rows. The first section of Home, if tracks, is
// the card that leads it.
func browseLayout(page *music.Browse, pageID string, cols, videoCols int) []browseRow {
	var rows []browseRow
	cards := func(kind rowKind, s, n int) {
		step := cols
		if kind == rowVideos {
			step = videoCols
		}
		for from := 0; from < n; from += step {
			rows = append(rows, browseRow{kind, s, from, min(from+step, n)})
		}
	}
	for s := range page.Sections {
		sec := &page.Sections[s]
		feature := s == 0 && pageID == "" && len(sec.Tracks) > 0 && sec.TrackLayout != music.TrackLayoutVideos
		if feature {
			rows = append(rows, browseRow{rowFeature, s, 0, 0})
			if len(sec.Albums)+len(sec.Artists)+len(sec.Playlists)+len(sec.Shows)+len(sec.Stations)+len(sec.Links) == 0 {
				continue
			}
		} else if sec.Title != "" || sec.MorePage != "" {
			rows = append(rows, browseRow{rowTitle, s, 0, 0})
		}
		if sec.TrackLayout == music.TrackLayoutVideos {
			cards(rowVideos, s, len(sec.Tracks))
		} else if !feature {
			for i := range sec.Tracks {
				rows = append(rows, browseRow{rowTrack, s, i, i})
			}
		}
		cards(rowAlbums, s, len(sec.Albums))
		cards(rowArtists, s, len(sec.Artists))
		cards(rowPlaylists, s, len(sec.Playlists))
		cards(rowShows, s, len(sec.Shows))
		cards(rowStations, s, len(sec.Stations))
		if pageID != "" {
			for i := range sec.Links {
				rows = append(rows, browseRow{rowLinks, s, i, i + 1})
			}
		} else {
			cards(rowLinks, s, len(sec.Links))
		}
		if sec.Empty != "" && len(sec.Tracks)+len(sec.Albums)+len(sec.Artists)+len(sec.Playlists)+len(sec.Shows)+len(sec.Stations)+len(sec.Links) == 0 {
			rows = append(rows, browseRow{rowEmpty, s, 0, 0})
		}
	}
	return rows
}

// browseRows shows a page under a header, which scrolls with it.
func (a *app) browseRows(c *ui.Context, page *music.Browse, pageID string, header func(c *ui.Context), footer ...func(c *ui.Context)) {
	cols := a.columns(c, 156)
	videoCols := a.columns(c, 220)
	layout := browseLayout(page, pageID, cols, videoCols)
	top, bottom := a.insets(c)
	a.underTop, a.underBottom = true, true
	rows := len(layout) + 1 + len(footer)
	now := c.Now()
	ui.List(c.Key(a.route), &a.trackList, rows, func(i int) {
		if i == 0 {
			ui.Column(c).PaddingX(12).Children(func() {
				a.startMark(c)
				header(c)
			})
			return
		}
		if i == rows-1 {
			defer a.endMark(c)
		}
		if i > len(layout) {
			ui.Column(c).Padding(12).Children(func() { footer[i-len(layout)-1](c) })
			return
		}
		r := layout[i-1]
		sec := &page.Sections[r.section]
		switch r.kind {
		case rowFeature:
			// What else its section has follows it untitled, as the card has
			// the title, so the card keeps it off as rows of cards keep apart.
			below := float32(0)
			if i < len(layout) && layout[i].section == r.section {
				below = cardRowGap
			}
			ui.Box(c).Padding(0, 12, below, 12).Children(func() { a.featureCard(c, sec, pageID, r.section, now) })
		case rowTitle:
			a.browseTitle(c, sec, r.section == 0)
		case rowTrack:
			a.trackRow(c, sec.Tracks, r.from, true, "")
		case rowVideos:
			a.videoTrackRow(c, sec, r, videoCols)
		case rowLinks:
			if pageID == "" {
				a.cardRow(c, sec, r, cols)
				break
			}
			link := sec.Links[r.from]
			button := ui.ButtonBase(c.Key(link.Page)).Label(link.Title).Padding(12).FocusRing(false)
			button.Row().Gap(12).AlignItems(ui.Start).Children(func() {
				ui.Box(c).Size(52, 52).Radius(26).Children(func() { a.coverFill(c, link.ImageURL, 104, 26) })
				ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(5).Children(func() {
					ui.Text(c, link.Title).FontSize(15).FontWeight(600)
					if link.Subtitle != "" {
						ui.Text(c, link.Subtitle).FontSize(12).TextColor(a.pal.muted).LineHeight(1.45)
					}
				})
			})
			if button.Clicked() {
				a.open(route{page: pageBrowse, id: link.Page})
			}
		case rowEmpty:
			ui.Column(c).Margin(0, 12, cardRowGap, 12).Children(func() { a.empty(c, sec.Empty) })
		default:
			a.cardRow(c, sec, r, cols)
		}
	}).Grow(1).Padding(top+pageGap, pagePad-12, bottom+32, pagePad-12).ScrollbarInsets(top, 0, bottom, 0)
}

// browseTitle is a section's title, with a way to all of it if there's more.
func (a *app) browseTitle(c *ui.Context, sec *music.Section, first bool) {
	margin := float32(34)
	if first {
		margin = 0
	}
	ui.Row(c).Margin(margin, 12, 14, 12).AlignItems(ui.Baseline).Children(func() {
		ui.Text(c, sec.Title).FontSize(19).FontWeight(700).LetterSpacing(-0.3).Grow(1).Shrink(1).MinWidth(0)
		if sec.MorePage != "" || sec.MoreBuiltin != "" {
			more := ui.ButtonBase(c.Key("more")).Padding(2, 8).Radius(6).FocusRing(false).Label(T("See All"))
			if more.Hovered() {
				more.Background(a.pal.hover)
			}
			more.Children(func() { ui.Text(c, T("See All")).FontSize(13).TextColor(a.pal.muted) })
			if more.Clicked() {
				if sec.MoreBuiltin != "" {
					if builtin, ok := builtinPages[sec.MoreBuiltin]; ok {
						a.open(route{page: builtin.page})
					} else {
						a.open(route{page: pageDiscoveryCollection, id: sec.MoreBuiltin})
					}
				} else {
					a.open(route{page: pageBrowse, id: sec.MorePage})
				}
			}
		}
	})
}

// cardRowGap is the room under a row of cards.
const cardRowGap = 28

// cardRow is a row of a section's cards, which keep their columns whatever
// the number in the last row.
func (a *app) cardRow(c *ui.Context, sec *music.Section, r browseRow, cols int) {
	ui.Grid(c).Columns(cols).GapX(20).Margin(0, 12, cardRowGap, 12).Children(func() {
		for i := r.from; i < r.to; i++ {
			switch r.kind {
			case rowAlbums:
				a.albumCard(c.Key(sec.Albums[i].ID), &sec.Albums[i])
			case rowArtists:
				a.artistCard(c.Key(sec.Artists[i].ID), &sec.Artists[i])
			case rowPlaylists:
				a.playlistCard(c.Key(sec.Playlists[i].ID), &sec.Playlists[i])
			case rowShows:
				a.showCard(c.Key(sec.Shows[i].ID), &sec.Shows[i])
			case rowStations:
				a.stationCard(c.Key(sec.Stations[i].ID), &sec.Stations[i])
			case rowLinks:
				l := &sec.Links[i]
				if a.coverCard(c.Key(l.Page), l.Title, l.ImageURL, l.Subtitle, nil) {
					a.open(route{page: pageBrowse, id: l.Page})
				}
			}
		}
	})
}

// sectionOf finds the section a section's page shows.
func (a *app) sectionOf(r route) *music.Section {
	page := a.library.home
	if r.id != "" {
		if d := a.library.browses[r.id]; d != nil {
			page = d.page
		}
	}
	at, err := strconv.Atoi(r.query)
	if page == nil || err != nil || at < 0 || at >= len(page.Sections) {
		return nil
	}
	return &page.Sections[at]
}

// sectionPage lists the tracks of a section the feature card shows only a few
// of. It's headed as the card is: the day over the first cover's colors, and
// the day's date over the title.
func (a *app) sectionPage(c *ui.Context) {
	sec := a.sectionOf(a.route)
	if sec == nil {
		a.centered(c, T("This page is gone"), nil)
		return
	}
	p := a.pal
	now := c.Now()
	title := sec.PageTitle
	if title == "" {
		title = sec.Title
	}
	about := i18n.Songs(len(sec.Tracks))
	if sec.Subtitle != "" {
		about += " · " + sec.Subtitle
	}
	a.trackRows(c, sec.Tracks, true, func(c *ui.Context) {
		ui.Row(c).Gap(28).AlignItems(ui.End).Padding(0, 0, 26, 0).Children(func() {
			a.dayCover(c, sec.Tracks, now)
			ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(8).Children(func() {
				ui.Text(c, i18n.Today(now)).FontSize(12).FontWeight(600).LetterSpacing(0.3).TextColor(p.muted).MaxLines(1)
				ui.Text(c, title).FontSize(30).FontWeight(800).LetterSpacing(-0.6).MaxLines(2).LineHeight(1.2)
				ui.Text(c, about).FontSize(13).TextColor(p.muted).MaxLines(2)
				ui.Row(c).Gap(10).Margin(8, 0, 0, 0).Children(func() { a.playButtons(c, sec.Tracks) })
			})
		})
	})
}
