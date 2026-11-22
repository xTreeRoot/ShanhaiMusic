package app

import (
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/discoveryprefs"

	"slices"
)

type discoveryEditor struct {
	open, busy    bool
	plugin        string
	before, draft discoveryprefs.Selection
	titles        map[string]string
	error         string
	revision      uint64
}
type discoveryDrag string

func (a *app) openDiscoveryEditor(lib *library) {
	before := a.discoverySelection(lib)
	titles := map[string]string{"home": T("Home")}
	for _, item := range discoveryDefinitions(lib.src) {
		titles[item.ID] = item.Title
	}
	enabled := map[string]bool{}
	for id, on := range before.Enabled {
		enabled[id] = on
	}
	a.discoveryEditor = discoveryEditor{open: true, plugin: lib.id, before: before, draft: discoveryprefs.Selection{Order: slices.Clone(before.Order), Enabled: enabled}, titles: titles, revision: a.discoveryEditor.revision + 1}
}
func (a *app) discoveryEditorView(c *ui.Context) {
	d := &a.discoveryEditor
	ui.DialogBase(c.Key("discovery-editor"), &d.open, func(backdrop, panel ui.Element) {
		backdrop.Background(ui.RGBA(0, 0, 0, .35))
		panel.Width(420).MaxHeight(640).Padding(24).Radius(18).Gap(14).Background(a.pal.bg)
		ui.Text(c, T("Discovery Sections")).FontSize(18).Bold()
		ui.Scroll(c).MaxHeight(460).Children(func() {
			for _, id := range d.draft.Order {
				row := ui.Row(c.Key(id)).Label(Tf("Reorder %s", d.titles[id])).Padding(10, 0).Gap(10).AlignItems(ui.Center).Drag(discoveryDrag(id))
				row.Children(func() {
					on := d.draft.Enabled[id]
					ui.Checkbox(c.Key("enabled"), &on, d.titles[id]).OnChange(func() { d.draft.Enabled[id] = on }).Disabled(d.busy).Grow(1)
					ui.Text(c, "☰").TextColor(a.pal.muted)
				})
				if _, over := ui.DragOver[discoveryDrag](row); over {
					row.Background(a.pal.hover)
				}
				if drag, ok := ui.Drop[discoveryDrag](row); ok && !d.busy {
					from, to := slices.Index(d.draft.Order, string(drag)), slices.Index(d.draft.Order, id)
					if from >= 0 && to >= 0 && from != to {
						order := slices.Clone(d.draft.Order)
						order = slices.Delete(order, from, from+1)
						d.draft.Order = slices.Insert(order, to, string(drag))
					}
				}
			}
		})
		if d.error != "" {
			ui.Text(c, d.error).TextColor(a.pal.muted)
		}
		ui.Row(c).Gap(10).Children(func() {
			ui.Button(c, T("Restore Defaults")).Disabled(d.busy).OnClick(func() {
				if lib := a.libOf(d.plugin); lib != nil {
					d.draft = discoveryprefs.Empty().Resolve(lib.id, discoveryDeclarations(lib.src))
				}
			})
			ui.Box(c).Grow(1)
			ui.Button(c, T("Cancel")).Disabled(d.busy).OnClick(func() { d.open = false })
			ui.Button(c, T("Save")).Disabled(d.busy).OnClick(func() { a.saveDiscoveryEditor() })
		})
	})
}
func (a *app) saveDiscoveryEditor() {
	d := &a.discoveryEditor
	if a.discoveryPrefs == nil || d.busy {
		return
	}
	d.busy = true
	plugin, before, draft, store := d.plugin, d.before, d.draft, a.discoveryPrefs
	revision := d.revision
	go func() {
		doc, err := store.Update(func(current discoveryprefs.Document, writer string) (discoveryprefs.Document, error) {
			return current.Edit(plugin, before, draft, writer)
		})
		a.post(func() {
			if d.revision != revision {
				if err == nil {
					a.applyDiscoveryPreferences(doc)
					a.syncDiscoveryPreferences()
				}
				return
			}
			d.busy = false
			if err != nil {
				d.error = err.Error()
				return
			}
			d.open = false
			a.applyDiscoveryPreferences(doc)
			a.syncDiscoveryPreferences()
		})
	}()
}

// 两行卡片使用已有资源组件和路由，栏目标题左、更多右。
func (a *app) discoveryPage(c *ui.Context) {
	top, bottom := a.insets(c)
	ui.Scroll(c.Key(a.route)).Grow(1).Padding(top+pageGap, pagePad, bottom+32, pagePad).Children(func() {
		a.homeHello(c, c.Now())
		if a.homeLoading && a.home == nil {
			a.homeSkeleton(c, a.pageShimmer(c), false)
			return
		}
		if a.home == nil {
			return
		}
		for index, section := range a.home.Sections {
			c := c.Key(index)
			a.browseTitle(c, &section, index == 0)
			var cards []func(*ui.Context)
			for i := range section.Tracks {
				track := &section.Tracks[i]
				cards = append(cards, func(c *ui.Context) {
					if a.coverCard(c, track.Name, track.Album.ImageURL, artistNames(track.Artists), nil) {
						a.pl.PlayQueue(section.Tracks, i)
					}
				})
			}
			for i := range section.Playlists {
				cards = append(cards, func(c *ui.Context) { a.playlistCard(c, &section.Playlists[i]) })
			}
			for i := range section.Albums {
				cards = append(cards, func(c *ui.Context) { a.albumCard(c, &section.Albums[i]) })
			}
			for i := range section.Artists {
				cards = append(cards, func(c *ui.Context) { a.artistCard(c, &section.Artists[i]) })
			}
			for i := range section.Shows {
				cards = append(cards, func(c *ui.Context) { a.showCard(c, &section.Shows[i]) })
			}
			for i := range section.Stations {
				cards = append(cards, func(c *ui.Context) { a.stationCard(c, &section.Stations[i]) })
			}
			for _, link := range section.Links {
				cards = append(cards, func(c *ui.Context) {
					if a.coverCard(c, link.Title, link.ImageURL, link.Subtitle, nil) {
						a.open(route{page: pageBrowse, id: link.Page})
					}
				})
			}
			if len(cards) == 0 {
				ui.Text(c, section.Empty).TextColor(a.pal.muted)
				continue
			}
			ui.ScrollHorizontal(c).Margin(0, 0, 20, 0).Children(func() {
				ui.Row(c).Gap(18).Children(func() {
					for start := 0; start < len(cards); start += 2 {
						ui.Column(c.Key(start)).Width(156).Gap(16).Children(func() {
							for i := start; i < min(start+2, len(cards)); i++ {
								ui.Box(c.Key(i)).Width(156).Children(func() { cards[i](c) })
							}
						})
					}
				})
			})
		}
	})
}
