package app

import (
	"maps"

	"mygo-music/internal/music"
)

func continuationOffset(offset, next, count int) int {
	if next > offset {
		return next
	}
	if next != 0 {
		return offset
	}
	return offset + count
}

func newCatalogTracks(known map[[2]string]bool, tracks []music.Track) []music.Track {
	var fresh []music.Track
	for _, track := range tracks {
		key := [2]string{track.Source, track.ID}
		if !known[key] {
			known[key] = true
			fresh = append(fresh, track)
		}
	}
	return fresh
}

func (a *app) loadPlaylistNext(lib *library, id string, playlist *music.Playlist) {
	pager, ok := lib.src.(music.PlaylistPager)
	if !ok || !playlist.More || lib.loadingPl[id] || lib.playlists[id] != playlist {
		return
	}
	offset := continuationOffset(0, playlist.NextOffset, len(playlist.Tracks))
	if offset == 0 {
		return
	}
	lib.loadingPl[id] = true
	delete(lib.plErr, id)
	gen := lib.accountGen
	go func() {
		page, err := answered(pager.PlaylistPage(id, offset))
		a.post(func() {
			if lib.accountGen != gen {
				return
			}
			delete(lib.loadingPl, id)
			if lib.playlists[id] != playlist {
				return
			}
			if err != nil {
				lib.plErr[id] = a.problem(lib, err, T("Couldn't load this playlist"))
				return
			}
			known := map[[2]string]bool{}
			newCatalogTracks(known, playlist.Tracks)
			fresh := newCatalogTracks(known, page.Tracks)
			combined := *playlist
			combined.Tracks = append(append([]music.Track(nil), playlist.Tracks...), fresh...)
			combined.NextOffset = continuationOffset(offset, page.NextOffset, len(page.Tracks))
			combined.More = page.More && combined.NextOffset > offset && (len(fresh) > 0 || len(page.Tracks) == 0 && page.NextOffset > offset)
			lib.playlists[id] = &combined
		})
	}()
}

type catalogNext func() ([]music.Track, bool, error)

func offsetTrackPages(offset int, load func(int) ([]music.Track, bool, int, error)) catalogNext {
	return func() ([]music.Track, bool, error) {
		tracks, more, next, err := load(offset)
		if err != nil {
			return nil, false, err
		}
		advanced := continuationOffset(offset, next, len(tracks))
		more = more && advanced > offset
		offset = advanced
		return tracks, more, nil
	}
}

func (a *app) playCatalog(lib *library, tracks []music.Track, index int, next catalogNext) {
	gen := lib.accountGen
	known := map[[2]string]bool{}
	newCatalogTracks(known, tracks)
	a.pl.PlayPaged(tracks, index, func(got func([]music.Track, bool, error)) {
		if lib.accountGen != gen {
			got(nil, false, nil)
			return
		}
		go func() {
			for range 8 {
				batch, more, err := next()
				fresh := newCatalogTracks(known, batch)
				if len(fresh) != 0 || !more || err != nil {
					a.post(func() {
						if lib.accountGen != gen {
							got(nil, false, nil)
							return
						}
						got(fresh, more && len(fresh) != 0, err)
					})
					return
				}
			}
			a.post(func() { got(nil, false, nil) })
		}()
	})
}

func playlistTrackPages(lib *library, id string, playlist *music.Playlist) catalogNext {
	if pager, ok := lib.src.(music.PlaylistPager); ok && playlist.More {
		offset := continuationOffset(0, playlist.NextOffset, len(playlist.Tracks))
		return offsetTrackPages(offset, func(offset int) ([]music.Track, bool, int, error) {
			page, err := answered(pager.PlaylistPage(id, offset))
			if err != nil {
				return nil, false, 0, err
			}
			return page.Tracks, page.More, page.NextOffset, nil
		})
	}
	return nil
}

func (a *app) playPlaylist(lib *library, id string, playlist *music.Playlist, index int) {
	a.playResumableList(lib, playlistResumeKey(lib, id), playlist.Tracks, index, playlistTrackPages(lib, id, playlist))
}

func browseTrackPages(lib *library, data *browseData) catalogNext {
	if data.next == "" {
		return nil
	}
	next, visited := data.next, maps.Clone(data.visited)
	if visited == nil {
		visited = map[string]bool{}
	}
	return func() ([]music.Track, bool, error) {
		page, err := answered(lib.src.Browse(next))
		if err != nil {
			return nil, false, err
		}
		visited[next] = true
		next = browseContinuation(page, visited)
		var batch []music.Track
		for _, section := range page.Sections {
			batch = append(batch, section.Tracks...)
		}
		return batch, next != "", nil
	}
}

func trackListPages(lib *library, r route, tracks []music.Track) catalogNext {
	switch r.page {
	case pagePlaylist:
		if list := lib.playlists[r.id]; list != nil && sameList(list.Tracks, tracks) {
			return playlistTrackPages(lib, r.id, list)
		}
	case pageSearch:
		if data := lib.searches[r]; data != nil && data.page.more && sameList(data.res.Tracks, tracks) {
			offset := continuationOffset(0, data.page.offset, data.res.Len())
			return offsetTrackPages(offset, func(offset int) ([]music.Track, bool, int, error) {
				page, err := answered(lib.src.Search(r.query, r.kind, offset))
				if err != nil {
					return nil, false, 0, err
				}
				return page.Tracks, page.More, page.NextOffset, nil
			})
		}
	case pageLiked:
		if lib.likedPaged.more && sameList(lib.likedList, tracks) {
			return offsetTrackPages(len(tracks), func(offset int) ([]music.Track, bool, int, error) {
				page, err := answered(lib.src.Liked(offset))
				if err != nil {
					return nil, false, 0, err
				}
				return page.Tracks, page.More, 0, nil
			})
		}
	}
	return nil
}

func (a *app) playCatalogTrack(tracks []music.Track, index int) {
	lib, route := a.library, a.route
	if key := trackListResumeKey(lib, route); key != "" {
		a.playResumableList(lib, key, tracks, index, trackListPages(lib, route, tracks))
		return
	}
	switch route.page {
	case pageHome:
		if lib.home != nil {
			for sectionIndex, section := range lib.home.Sections {
				if sameList(section.Tracks, tracks) {
					a.playResumableList(lib, homeTrackListKey(lib, section, sectionIndex), tracks, index, nil)
					return
				}
			}
		}
		if sameList(lib.recent[:min(len(lib.recent), 8)], tracks) {
			a.playResumableList(lib, browseResumeKey(lib, "\x00recent"), tracks, index, nil)
			return
		}
	case pageBrowse, pageDiscoveryCollection:
		key := route.id
		if route.page == pageDiscoveryCollection {
			key = discoveryCollectionKey(key)
		}
		data := lib.browses[key]
		if listed := resumableBrowseTracks(data); len(listed) > 0 && sameList(listed, tracks) {
			a.playResumableList(lib, browseResumeKey(lib, key), tracks, index, browseTrackPages(lib, data))
			return
		}
		if data != nil && data.page != nil && data.visited != nil && (data.next != "" || len(data.visited) > 1) {
			var queue []music.Track
			selected := -1
			for _, section := range data.page.Sections {
				if sameList(section.Tracks, tracks) {
					selected = len(queue) + index
				}
				queue = append(queue, section.Tracks...)
			}
			if selected >= 0 {
				if next := browseTrackPages(lib, data); next != nil {
					a.playCatalog(lib, queue, selected, next)
				} else {
					a.pl.PlayTrack(queue, selected)
				}
				a.rememberTrackList(browseResumeKey(lib, key))
				return
			}
		}
		if len(resumableBrowseTracks(data)) > 0 {
			a.playResumableList(lib, browseResumeKey(lib, key), tracks, index, nil)
			return
		}
	}
	if index < 0 {
		a.pl.PlayShuffled(tracks)
	} else {
		a.pl.PlayTrack(tracks, index)
	}
}
