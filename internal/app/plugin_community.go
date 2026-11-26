package app

import (
	"github.com/egoist/mygo/ui"
	"hash/fnv"
	"log"
	"mygo-music/internal/plugin"
	"net/url"
	"slices"
	"strings"
	"time"
)

const pluginTileSize = 34

// communityTab is the id of the Plugins page's route on its Community tab; on
// Installed, it has none. In the route, back goes to the tab it was on.
const communityTab = "community"

// fetchCommunity lists a page of the plugins on GitHub; tests replace it.
var fetchCommunity = plugin.Community

// communityFresh is how long a list stays good. GitHub's search allows few
// requests a minute without the listener's token.
const communityFresh = time.Hour

// communityState is what the Plugins page has of the plugins on GitHub: those a
// search found, or all of them, a page at a time.
type communityState struct {
	query    string    // what's written in the search field
	searched string    // what the list is of: "" for all
	at       time.Time // when its first page came, zero until it has
	list     []plugin.Listing
	pages    int // how many pages of it came
	paged    paged
	gen      int       // counts the lists asked for, for an answer about one before to be dropped
	box      ui.Handle // the search field's input
}

// loadCommunity shows the list of the Community tab, asking GitHub for it anew
// unless it did a while ago.
func (a *app) loadCommunity() {
	d := &a.community
	if d.paged.loading || !d.at.IsZero() && time.Since(d.at) < communityFresh {
		return
	}
	a.searchCommunity(d.searched)
}

// searchCommunity lists the plugins query finds, all of them for "", from their
// first page.
func (a *app) searchCommunity(query string) {
	d := &a.community
	d.searched, d.list, d.pages, d.paged, d.at = strings.TrimSpace(query), nil, 0, paged{}, time.Time{}
	d.gen++
	a.moreCommunity()
}

// moreCommunity asks GitHub for the next page of the list.
func (a *app) moreCommunity() {
	d := &a.community
	if d.paged.loading {
		return
	}
	d.paged.loading, d.paged.err = true, ""
	query, gen := d.searched, d.gen
	page := d.pages + 1
	fetch := fetchCommunity // here, for a test may change it as one before it waits
	go func() {
		got, err := fetch(query, page)
		a.post(func() {
			if d.gen != gen {
				return // the list asked for since takes its place
			}
			d.paged.loading = false
			if err != nil {
				log.Printf("listing plugins: %v", err)
				d.paged.err = T("Couldn't load the plugins")
				return
			}
			if page == 1 {
				d.at = time.Now()
			}
			// A maintained entry may occur on adjacent pages after the catalog changes.
			for _, l := range got.Listings {
				if !slices.ContainsFunc(d.list, func(m plugin.Listing) bool { return strings.EqualFold(m.Repo, l.Repo) }) {
					d.list = append(d.list, l)
				}
			}
			d.pages = page
			d.paged.loaded, d.paged.more = true, got.More && len(got.Listings) > 0
		})
	}()
}

// installedRepos are the repositories the plugins installed came from, in lower
// case, as GitHub takes owner/repo in any.
func (a *app) installedRepos() map[string]bool {
	repos := map[string]bool{}
	for _, lib := range a.allLibs {
		if p := pluginOf(lib); p != nil {
			if o := p.Origin(); o != nil {
				repos[strings.ToLower(o.Repo)] = true
			}
		}
	}
	return repos
}

// choosePluginsTab shows the Plugins page's Community tab, or else Installed, in
// place of the one showing: back goes to the page before, not the other tab.
func (a *app) choosePluginsTab(community bool) {
	a.installer.detailsID = ""
	a.installer.editingPlugins = false
	r := a.route
	r.id = ""
	if community {
		r.id = communityTab
	}
	a.route = r
	a.installer.marked = ""
	a.load(r)
}

// communityPlugins is the plugins on GitHub, or those a search found, as cards
// with a button to install each, more loading as the page nears its end.
func (a *app) communityPlugins(c *ui.Context, p palette) {
	d := &a.community
	ui.Row(c).Gap(16).AlignItems(ui.Center).Margin(0, 0, 18, 0).Children(func() {
		ui.Box(c).Grow(1)
		a.communitySearch(c, p)
	})
	cols := a.columns(c, 260)
	switch {
	case !d.paged.loaded && d.paged.loading:
		s := a.pageShimmer(c)
		ui.Grid(c).Columns(cols).GapX(16).GapY(16).Children(func() {
			for i := range 6 {
				s.bone(c.Key(i), 14).Height(150)
			}
		})
	case !d.paged.loaded && d.paged.err != "":
		ui.Column(c).Padding(40, 0).Gap(12).AlignItems(ui.Center).Children(func() {
			ui.Text(c, d.paged.err).FontSize(13).TextColor(p.muted)
			if ui.Button(c.Key("retry"), T("Retry")).Clicked() {
				a.moreCommunity()
			}
		})
	case !d.paged.loaded:
	case len(d.list) == 0 && d.searched != "":
		a.empty(c, Tf("No plugins found for “%s”", d.searched))
	case len(d.list) == 0:
		a.empty(c, T("No plugins yet"))
	default:
		installed := a.installedRepos()
		ui.Grid(c.Key(d.gen)).Columns(cols).GapX(16).GapY(16).Children(func() {
			for i := range d.list {
				l := &d.list[i]
				a.listingCard(c.Key(l.Repo), l, installed[strings.ToLower(l.Repo)])
			}
		})
		a.pageFoot(c, &d.paged, a.moreCommunity)
	}
}

// communitySearch is the field that searches the plugins on GitHub, as Enter
// is pressed, its few searches a minute not going on each letter. Emptied, by
// its button or by hand, the list is of them all again.
func (a *app) communitySearch(c *ui.Context, p palette) {
	d := &a.community
	// The input fills the field, under its icon, so a click anywhere on the field
	// focuses it.
	field := ui.Row(c).Width(240).Height(30).Radius(8).AlignItems(ui.Stretch).Shrink(0)
	if c.Focused(d.box) {
		field.Background(p.bg).Shadow(0, 0, 0, 3, p.accent.Alpha(0.25)).Border(1, p.accent.Alpha(0.6))
	} else {
		field.Background(p.hover).Border(1, p.hairline)
	}
	clearable := d.query != ""
	end := float32(10)
	if clearable {
		end = 28
	}
	submitted := false
	field.Children(func() {
		in := ui.TextInputBase(c.Key("search-plugins"), &d.query).Bind(&d.box)
		submitted = in.Submitted()
		in.Grow(1).MinWidth(0).PaddingStart(29).PaddingEnd(end).Placeholder(T("Search Plugins")).FontSize(13).Label(T("Search Plugins"))
		ui.Icon(c, iconSearch).Size(13, 13).TextColor(p.muted).Absolute().Left(9).Top(8.5).IgnoreInput(true)
		if !clearable {
			return
		}
		x := ui.ButtonBase(c.Key("clear")).Size(22, 22).Center().Absolute().Right(4).Top(4).
			KeepFocus().FocusRing(false).Label(T("Clear Search"))
		fill := p.faint
		if x.Hovered() {
			fill = p.muted
		}
		x.Children(func() {
			ui.Box(c).Size(14, 14).Radius(7).Center().Background(fill).Children(func() {
				ui.Icon(c, iconX).Size(8, 8).TextColor(p.bg)
			})
		})
		if x.Clicked() {
			d.query = ""
		}
	})
	q := strings.TrimSpace(d.query)
	switch {
	case submitted && q != d.searched:
		a.searchCommunity(q)
	case q == "" && d.searched != "":
		a.searchCommunity("")
	}
}

// ownerAvatar is the picture of an account on GitHub, at the size of a plugin's
// tile; tests replace it, to reach no network.
var ownerAvatar = func(owner string) string {
	return "https://github.com/" + url.PathEscape(owner) + ".png?size=120"
}

// pluginIcon is the picture on a plugin's tile: the icon it names, or else, for
// one installed from GitHub, its owner's picture; or "" for none.
func pluginIcon(lib *library) string {
	plug := pluginOf(lib)
	if plug == nil {
		return ""
	}
	if icon := plug.Icon(); icon != "" {
		return icon
	}
	if o := plug.Origin(); o != nil {
		owner, _, _ := strings.Cut(o.Repo, "/")
		return ownerAvatar(owner)
	}
	return ""
}

// pluginTile is the square at the start of a plugin's card: its icon, fading in
// over its monogram, which stays where there's none, or it doesn't load.
func (a *app) pluginTile(c *ui.Context, lib *library) {
	var bmp *ui.Bitmap
	if icon := pluginIcon(lib); icon != "" {
		bmp = bitmap(a.art.get(sized(icon, pluginTileSize*3), false))
	}
	box := ui.Box(c).Size(pluginTileSize, pluginTileSize).Radius(10).Shrink(0)
	in := artIn(box, bmp)
	box.Children(func() {
		if in < 1 {
			monogram(c, lib.name(), lib.id)
		}
		artImage(c, bmp, 10, in)
	})
}

// monogram is a plugin's tile: the first letter of its name, on a color of its
// own, which its ID chooses, to stay the same as it's renamed.
func monogram(c *ui.Context, name, id string) {
	letter := "?"
	for _, r := range strings.TrimSpace(name) {
		letter = strings.ToUpper(string(r))
		break
	}
	h := fnv.New32a()
	h.Write([]byte(id))
	hue := float32(h.Sum32() % 360)
	top, bottom := ui.Oklch(0.7, 0.14, hue), ui.Oklch(0.56, 0.16, hue+20)
	ui.Box(c).Size(pluginTileSize, pluginTileSize).Radius(10).Shrink(0).Center().
		Gradient(top, bottom, 180).
		Children(func() {
			ui.Text(c, letter).FontSize(18).FontWeight(700).TextColor(ui.RGB(255, 255, 255))
		})
}

// listingCard is a plugin on GitHub: who made it and what it does, a link to its
// repository, and Install, or that it is.
func (a *app) listingCard(c *ui.Context, l *plugin.Listing, installed bool) {
	p := a.pal
	ui.Column(c).Padding(16).Gap(10).Radius(14).Background(p.hover).Border(0.5, p.hairline).Children(func() {
		ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
			a.avatar(c, l.Avatar, 36, p.press)
			ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(2).Children(func() {
				ui.Link(c, l.Name, l.URL).FontSize(14).FontWeight(600).TextColor(p.text).SingleLine().Tooltip(T("Open on GitHub"))
				ui.Text(c, l.Owner).FontSize(12).TextColor(p.muted).SingleLine()
			})
		})
		desc := l.Description
		if desc == "" {
			desc = l.Repo
		}
		ui.Text(c, desc).FontSize(12.5).TextColor(p.muted).LineHeight(1.4).Lines(2, 2).Grow(1)
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			if l.Stars > 0 {
				ui.Icon(c, iconStar).Size(13, 13).TextColor(p.faint)
				ui.Text(c, count(l.Stars)).FontSize(12).TextColor(p.muted)
			}
			ui.Box(c).Grow(1)
			if installed {
				ui.Row(c).Gap(4).AlignItems(ui.Center).Height(28).Children(func() {
					ui.Icon(c, iconCheck).Size(13, 13).TextColor(p.muted)
					ui.Text(c, T("Installed")).FontSize(12.5).FontWeight(500).TextColor(p.muted)
				})
				return
			}
			busy := a.installer.busy && strings.EqualFold(a.installer.repo, l.Repo)
			label := T("Install")
			if busy {
				label = T("Downloading…")
			}
			b := ui.Button(c.Key("install"), label).Label(Tf("Install %s", l.Name)).Disabled(busy)
			if b.Clicked() && !busy {
				a.installRepo(l.Repo)
			}
		})
	})
}
