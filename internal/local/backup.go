package local

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mygo-music/internal/music"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"

	"mygo-music/internal/devicebackup"
)

// DeviceBackup includes every indexed local audio file and the listener's
// playlists, likes and recent history. Cache files are never part of it.
func (s *Source) DeviceBackup(ctx context.Context) (devicebackup.Library, []devicebackup.File, error) {
	s.mu.RLock()
	lib := s.lib
	likes, recent := slices.Clone(lib.Likes), slices.Clone(lib.Recent)
	playlists := slices.Clone(lib.Playlists)
	s.mu.RUnlock()
	state := devicebackup.Library{Tracks: []devicebackup.Track{}, Favorites: []string{}, Recent: []string{}, Playlists: []devicebackup.Playlist{}, SearchHistory: []string{}}
	files := []devicebackup.File{}
	seen := map[string]bool{}
	for _, item := range s.cat.Load().items {
		if err := ctx.Err(); err != nil {
			return state, nil, err
		}
		f, err := os.Open(item.Path)
		if err != nil {
			return state, nil, err
		}
		hash := sha256.New()
		_, err = io.Copy(hash, f)
		f.Close()
		if err != nil {
			return state, nil, err
		}
		digest := hex.EncodeToString(hash.Sum(nil))
		ext := strings.ToLower(filepath.Ext(item.Path))
		name := "media/" + digest + ext
		track := devicebackup.Track{Source: ID, ID: item.id, Title: item.Title, Artist: item.Artist, Duration: item.Duration, Asset: name, Kind: "imported", Album: item.Album, AlbumArtist: item.AlbumArtist, TrackNumber: item.Track, DiscNumber: item.Disc}
		if item.Year > 0 {
			track.Year = strconv.Itoa(item.Year)
		}
		if lyrics, err := s.Lyrics(item.id); err == nil && lyrics != nil {
			track.Lyrics = lyrics.LRC
		}
		state.Tracks = append(state.Tracks, track)
		if !seen[name] {
			files = append(files, devicebackup.File{Name: name, Source: item.Path})
			seen[name] = true
		}
	}
	for _, id := range likes {
		state.Favorites = append(state.Favorites, ID+":"+id)
	}
	for _, id := range recent {
		state.Recent = append(state.Recent, ID+":"+id)
	}
	for _, p := range playlists {
		tracks := make([]string, len(p.Tracks))
		for i, id := range p.Tracks {
			tracks[i] = ID + ":" + id
		}
		state.Playlists = append(state.Playlists, devicebackup.Playlist{ID: p.ID, Name: p.Name, Tracks: tracks})
	}
	return state, files, nil
}

// RestoreDeviceBackup copies managed media before publishing merged metadata.
// Old IDs are mapped to this device's local paths so playlist references work.
func (s *Source) RestoreDeviceBackup(ctx context.Context, state devicebackup.Library, stage string) error {
	directory := filepath.Join(s.dir, "restored")
	mapping := map[string]string{}
	if err := devicebackup.EnsureDirectory(directory, ""); err != nil {
		return err
	}
	entries := map[string]entry{}
	for _, item := range s.cat.Load().items {
		entries[item.Path] = item.entry
	}
	for _, track := range state.Tracks {
		if track.Asset == "" {
			continue
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if !devicebackup.ValidName(track.Asset) || !strings.HasPrefix(track.Asset, "media/") {
			return errors.New("invalid backup media path")
		}
		from := filepath.Join(stage, filepath.FromSlash(track.Asset))
		name := filepath.Base(from)
		to := filepath.Join(directory, name)
		if _, err := os.Lstat(to); errors.Is(err, os.ErrNotExist) {
			src, err := os.Open(from)
			if err != nil {
				return err
			}
			tmp, err := os.CreateTemp(directory, ".restore-")
			if err != nil {
				src.Close()
				return err
			}
			_, copyErr := io.Copy(tmp, src)
			src.Close()
			closeErr := tmp.Close()
			if copyErr != nil || closeErr != nil {
				os.Remove(tmp.Name())
				return errors.Join(copyErr, closeErr)
			}
			if err := os.Rename(tmp.Name(), to); err != nil {
				os.Remove(tmp.Name())
				return err
			}
		} else if err != nil {
			return err
		} else if info, err := os.Lstat(to); err != nil || !info.Mode().IsRegular() {
			return errors.New("unsafe restored media destination")
		}
		if track.Lyrics != "" {
			sidecar := strings.TrimSuffix(to, filepath.Ext(to)) + ".lrc"
			if _, err := os.Lstat(sidecar); errors.Is(err, os.ErrNotExist) {
				if err := os.WriteFile(sidecar, []byte(track.Lyrics), 0600); err != nil {
					return err
				}
			}
		}
		info, err := os.Stat(to)
		if err != nil {
			return err
		}
		year, _ := strconv.Atoi(track.Year)
		if _, exists := entries[to]; !exists {
			tagged, _ := s.readFile(found{path: to, size: info.Size(), mod: info.ModTime().UnixNano()})
			entries[to] = entry{Cover: tagged.Cover, Sidecar: tagged.Sidecar, Gain: tagged.Gain, Peak: tagged.Peak, Path: to, Size: info.Size(), Mod: info.ModTime().UnixNano(),
				Title: track.Title, Artist: track.Artist, Album: track.Album, AlbumArtist: track.AlbumArtist,
				Year: year, Track: track.TrackNumber, Disc: track.DiscNumber, Duration: track.Duration}
		}
		mapping[track.Key()] = makeID("track", to)
	}
	resolve := func(keys []string) []string {
		out := []string{}
		for _, key := range keys {
			if id, ok := mapping[key]; ok {
				out = append(out, id)
			}
		}
		return out
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	err := s.change(func() error {
		if !slices.Contains(s.lib.Folders, directory) {
			s.lib.Folders = append(s.lib.Folders, directory)
		}
		for _, id := range resolve(state.Favorites) {
			if !slices.Contains(s.lib.Likes, id) {
				s.lib.Likes = append(s.lib.Likes, id)
			}
		}
		for _, id := range resolve(state.Recent) {
			if !slices.Contains(s.lib.Recent, id) {
				s.lib.Recent = append(s.lib.Recent, id)
			}
		}
		for _, incoming := range state.Playlists {
			tracks := resolve(incoming.Tracks)
			if current := s.lib.playlist(incoming.ID); current != nil {
				for _, id := range tracks {
					if !slices.Contains(current.Tracks, id) {
						current.Tracks = append(current.Tracks, id)
					}
				}
			} else {
				s.lib.Playlists = append(s.lib.Playlists, playlist{ID: incoming.ID, Name: incoming.Name, Tracks: tracks})
			}
		}
		return nil
	}, true)
	if err == nil {
		s.mu.Lock()
		if s.cancelScan != nil {
			s.cancelScan()
		}
		s.scanGen++
		gen := s.scanGen
		s.mu.Unlock()
		s.publish(gen, entries, true)
		s.saveIndex(entries)
		s.Rescan()
	}
	return err
}

// RestoredBackupTrack resolves the portable asset to this device's local ID.
func (s *Source) RestoredBackupTrack(track devicebackup.Track) music.Track {
	if track.Asset != "" {
		file := filepath.Join(s.dir, "restored", filepath.Base(track.Asset))
		if item := s.cat.Load().byPath[file]; item != nil {
			return item.public()
		}
	}
	return music.Track{ID: track.ID, Source: track.Source, Name: track.Title, Duration: track.Duration,
		URL: track.Page, Artists: []music.Artist{{ID: track.Author, Name: track.Artist}}, Album: music.Album{Name: track.Album, ImageURL: track.Artwork}}
}
