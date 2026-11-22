package app

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"mygo-music/internal/discoveryprefs"
	"mygo-music/internal/local"
	"mygo-music/internal/music"
	"slices"
)

func (a *app) startDiscoveryPreferences() {
	a.discoveryPrefs = discoveryprefs.NewStore(userDataDir("discovery-preferences"))
	doc, err := a.discoveryPrefs.Load()
	if err != nil {
		a.edge.message = err.Error()
		return
	}
	a.discoveryDocument = doc
}
func discoveryDefinitions(src music.Source) []music.DiscoverySection {
	info := src.Info()
	if len(info.Discovery) > 0 {
		return info.Discovery
	}
	result := []music.DiscoverySection{{ID: "home", Title: T("Home"), Builtin: "home"}}
	if _, localFiles := src.(*local.Source); localFiles {
		return result
	}
	seen := map[string]bool{"home": true}
	for _, item := range info.Navigation {
		route, kind := item.Page, "page:"
		if route == "" {
			route, kind = item.Builtin, "collection:"
		}
		if route == "" || route == "home" || route == "search" || item.Placement == "personal" {
			continue
		}
		if item.Page == "" && !slices.Contains([]string{"playlists", "liked", "recent", "savedAlbums", "shows", "stations"}, item.Builtin) {
			continue
		}
		hash := sha256.Sum256([]byte(kind + route))
		id := fmt.Sprintf("nav-%x", hash[:12])
		if seen[id] {
			continue
		}
		seen[id] = true
		title := item.Title
		if title == "" {
			title = route
		}
		result = append(result, music.DiscoverySection{ID: id, Title: title, Page: item.Page, Builtin: item.Builtin})
	}
	return result
}
func discoveryDeclarations(src music.Source) []discoveryprefs.Section {
	var sections []discoveryprefs.Section
	for _, section := range discoveryDefinitions(src) {
		sections = append(sections, discoveryprefs.Section{ID: section.ID, DefaultEnabled: section.EnabledByDefault()})
	}
	return sections
}
func (a *app) discoverySelection(lib *library) discoveryprefs.Selection {
	doc := a.discoveryDocument
	if doc.Version == 0 {
		doc = discoveryprefs.Empty()
	}
	return doc.Resolve(lib.id, discoveryDeclarations(lib.src))
}
func (a *app) applyDiscoveryPreferences(doc discoveryprefs.Document) {
	previous := a.discoveryDocument
	if previous.Version == 1 {
		merged, err := previous.Merge(doc)
		if err != nil {
			a.edge.message = err.Error()
			return
		}
		doc = merged
	}
	a.discoveryDocument = doc
	for _, lib := range a.allLibs {
		sections := discoveryDeclarations(lib.src)
		old, next := previous.Resolve(lib.id, sections), doc.Resolve(lib.id, sections)
		changed := !slices.Equal(old.Order, next.Order)
		for id, on := range next.Enabled {
			changed = changed || old.Enabled[id] != on
		}
		if changed {
			if lib.homeCancel != nil {
				lib.homeCancel()
			}
			lib.homeRevision++
			lib.homeLoading = false
			lib.home = nil
			if lib == a.library {
				a.loadHome(lib)
			}
		}
	}
	a.redraw()
}
func (a *app) syncDiscoveryPreferences() {
	if a.discoveryPrefs == nil || a.edge.service == nil || a.edge.profileContext == nil || a.discoverySyncing {
		return
	}
	a.discoverySyncing = true
	service, store, ctx := a.edge.service, a.discoveryPrefs, a.edge.profileContext
	go func() {
		err := service.SyncDiscoveryPreferences(ctx, store.Load, func(remote discoveryprefs.Document) (discoveryprefs.Document, error) {
			if ctx.Err() != nil {
				return discoveryprefs.Empty(), context.Canceled
			}
			doc, err := store.Merge(remote)
			if err == nil {
				a.post(func() {
					if service == a.edge.service && ctx.Err() == nil {
						a.applyDiscoveryPreferences(doc)
					}
				})
			}
			return doc, err
		})
		a.post(func() {
			a.discoverySyncing = false
			if ctx.Err() == nil && err != nil {
				a.edge.message = edgeError(err)
			}
			if service != a.edge.service {
				a.syncDiscoveryPreferences()
			}
		})
	}()
}

// 调用独立插件路由；关闭栏目既不会调用接口，也不会把其封面放进页面预取。
func loadDiscovery(ctx context.Context, src music.Source, selection discoveryprefs.Selection) (*music.Browse, error) {
	page := &music.Browse{Title: src.Info().Name}
	for _, id := range selection.Order {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if !selection.Enabled[id] {
			continue
		}
		declarations := discoveryDefinitions(src)
		index := slices.IndexFunc(declarations, func(item music.DiscoverySection) bool { return item.ID == id })
		if index < 0 {
			continue
		}
		item := declarations[index]
		if item.Builtin == "home" {
			home, err := src.Home()
			if err != nil && !errors.Is(err, music.ErrUnsupported) {
				page.Sections = append(page.Sections, music.Section{Title: item.Title, Empty: err.Error()})
			}
			if home != nil {
				for _, row := range home.Sections {
					if !slices.ContainsFunc(page.Sections, func(existing music.Section) bool { return existing.Title == row.Title }) {
						page.Sections = append(page.Sections, row)
					}
				}
			}
			continue
		}
		if slices.ContainsFunc(page.Sections, func(existing music.Section) bool {
			return item.Page != "" && existing.MorePage == item.Page || existing.Title == item.Title
		}) {
			continue
		}
		section := music.Section{Title: item.Title, MorePage: item.Page, MoreBuiltin: item.Builtin}
		var err error
		if item.Page != "" {
			var result *music.Browse
			result, err = src.Browse(item.Page)
			if result != nil && len(result.Sections) == 1 && result.Sections[0].MorePage != "" {
				section.MorePage = result.Sections[0].MorePage
			}
			if result != nil {
				for _, row := range result.Sections {
					section.Tracks = append(section.Tracks, row.Tracks...)
					section.Albums = append(section.Albums, row.Albums...)
					section.Artists = append(section.Artists, row.Artists...)
					section.Playlists = append(section.Playlists, row.Playlists...)
					section.Shows = append(section.Shows, row.Shows...)
					section.Stations = append(section.Stations, row.Stations...)
					section.Links = append(section.Links, row.Links...)
					if section.Empty == "" {
						section.Empty = row.Empty
					}
					if row.TrackLayout != "" {
						section.TrackLayout = row.TrackLayout
					}
				}
			}
		} else {
			switch item.Builtin {
			case "playlists":
				section.Playlists, err = src.Playlists()
			case "recent":
				section.Tracks, err = src.Recent()
			case "stations":
				section.Stations, err = src.Stations()
			case "shows":
				section.Shows, err = src.Shows()
			case "liked":
				var value *music.Page
				value, err = src.Liked(0)
				if value != nil {
					section.Tracks = value.Tracks
				}
			case "savedAlbums":
				var value *music.AlbumList
				value, err = src.SavedAlbums(0)
				if value != nil {
					section.Albums = value.Albums
				}
			}
		}
		if err != nil {
			section.Empty = err.Error()
		}
		section.Tracks = section.Tracks[:min(12, len(section.Tracks))]
		section.Albums = section.Albums[:min(12, len(section.Albums))]
		section.Playlists = section.Playlists[:min(12, len(section.Playlists))]
		section.Artists = section.Artists[:min(12, len(section.Artists))]
		section.Shows = section.Shows[:min(12, len(section.Shows))]
		section.Stations = section.Stations[:min(12, len(section.Stations))]
		section.Links = section.Links[:min(12, len(section.Links))]
		page.Sections = append(page.Sections, section)
	}
	return page, nil
}

func (a *app) loadDiscoveryCollection(lib *library, name string) {
	d := &browseData{loading: true}
	lib.browses[discoveryCollectionKey(name)] = d
	gen := lib.accountGen
	go func() {
		section := music.Section{}
		var err error
		switch name {
		case "playlists":
			section.Playlists, err = lib.src.Playlists()
		case "recent":
			section.Tracks, err = lib.src.Recent()
		default:
			err = music.ErrUnsupported
		}
		a.post(func() {
			if gen != lib.accountGen {
				return
			}
			d.loading = false
			if err != nil {
				d.err = err.Error()
				return
			}
			d.page = &music.Browse{Sections: []music.Section{section}}
		})
	}()
}

func discoveryCollectionKey(name string) string { return "mb-collection:" + name }
