// Package devicebackup defines the portable device archive shared by all hosts.
package devicebackup

import (
	"archive/zip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
)

const MaxArchiveBytes int64 = 16 << 30
const MaxFiles = 50000
const MaxManifestBytes = 32 << 20

type Plugin struct {
	ID         string            `json:"id"`
	Repository string            `json:"repository,omitempty"`
	Revision   string            `json:"revision,omitempty"`
	Name       string            `json:"name,omitempty"`
	Enabled    bool              `json:"enabled"`
	Settings   map[string]string `json:"settings"`
}

// Track uses the existing mobile metadata names. Asset is an archive-relative
// path; absolute paths, request headers and temporary media addresses are absent.
type Track struct {
	Source       string `json:"source"`
	ID           string `json:"track"`
	Title        string `json:"title"`
	Artist       string `json:"artist"`
	Artwork      string `json:"artwork,omitempty"`
	Duration     int64  `json:"duration"`
	Page         string `json:"page,omitempty"`
	Author       string `json:"author,omitempty"`
	Asset        string `json:"asset,omitempty"`
	Kind         string `json:"kind,omitempty"`
	Lyrics       string `json:"lyrics,omitempty"`
	Album        string `json:"album,omitempty"`
	AlbumArtist  string `json:"albumArtist,omitempty"`
	Folder       string `json:"folder,omitempty"`
	Year         string `json:"year,omitempty"`
	TrackNumber  int    `json:"trackNumber,omitempty"`
	DiscNumber   int    `json:"discNumber,omitempty"`
	ResourceType string `json:"resourceType,omitempty"`
	ResourceURL  string `json:"resourceUrl,omitempty"`
}

func (t Track) Key() string { return t.Source + ":" + t.ID }

type Playlist struct {
	ID     string   `json:"id"`
	Name   string   `json:"name"`
	Tracks []string `json:"tracks"`
}

type Queue struct {
	Tracks   []Track `json:"tracks"`
	Index    int     `json:"index"`
	Position int64   `json:"position"`
	Shuffled bool    `json:"shuffled"`
	Repeat   string  `json:"repeat"`
}

type Library struct {
	Tracks        []Track    `json:"tracks"`
	Favorites     []string   `json:"favorites"`
	Recent        []string   `json:"recent"`
	Playlists     []Playlist `json:"playlists"`
	SearchHistory []string   `json:"searchHistory"`
	Queue         *Queue     `json:"queue,omitempty"`
}

type Manifest struct {
	Version              int             `json:"version"`
	Platform             string          `json:"platform"`
	Plugins              []Plugin        `json:"plugins"`
	Library              Library         `json:"library"`
	PluginPreferences    json.RawMessage `json:"pluginPreferences,omitempty"`
	DiscoveryPreferences json.RawMessage `json:"discoveryPreferences,omitempty"`
}

type File struct{ Name, Source string }

var pluginID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`)

func ValidName(name string) bool {
	if name == "" || len(name) > 1024 || path.Clean(name) != name || strings.ContainsAny(name, "\\:\x00") || strings.HasPrefix(name, "/") {
		return false
	}
	for _, part := range strings.Split(name, "/") {
		if part == "." || part == ".." || part == "" {
			return false
		}
	}
	return true
}

func (m Manifest) Validate() error {
	if m.Version != 1 || m.Platform != "desktop" && m.Platform != "ios" && m.Platform != "android" || len(m.Plugins) > 1000 {
		return errors.New("unsupported device archive")
	}
	seen := map[string]bool{}
	for _, p := range m.Plugins {
		if !pluginID.MatchString(p.ID) || strings.Contains(p.ID, "..") || strings.EqualFold(p.ID, "local") || seen[strings.ToLower(p.ID)] || p.Settings == nil {
			return errors.New("invalid backup plugin")
		}
		seen[strings.ToLower(p.ID)] = true
	}
	seen = map[string]bool{}
	for _, tr := range m.Library.Tracks {
		if tr.Source == "" || tr.ID == "" || seen[tr.Key()] || tr.Asset != "" && (!ValidName(tr.Asset) || !strings.HasPrefix(tr.Asset, "media/")) {
			return errors.New("invalid backup track")
		}
		seen[tr.Key()] = true
	}
	return nil
}

// Tree enumerates regular files only and rejects symbolic links rather than
// following them into unrelated user data.
func Tree(root, prefix string) ([]File, error) {
	var files []File
	info, err := os.Lstat(root)
	if errors.Is(err, fs.ErrNotExist) {
		return files, nil
	}
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("invalid backup root")
	}
	err = filepath.WalkDir(root, func(file string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if file == root {
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return errors.New("symbolic link in backup input")
		}
		if entry.IsDir() {
			return nil
		}
		if !entry.Type().IsRegular() {
			return errors.New("nonregular backup input")
		}
		rel, err := filepath.Rel(root, file)
		if err != nil {
			return err
		}
		name := prefix + "/" + filepath.ToSlash(rel)
		if !ValidName(name) {
			return errors.New("invalid backup path")
		}
		files = append(files, File{Name: name, Source: file})
		return nil
	})
	return files, err
}

func Write(ctx context.Context, output string, manifest Manifest, files []File) (err error) {
	if err = manifest.Validate(); err != nil {
		return err
	}
	metadata, err := json.Marshal(manifest)
	if err != nil || len(metadata) > MaxManifestBytes {
		return errors.New("device archive metadata too large")
	}
	f, err := os.OpenFile(output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := f.Close(); err == nil {
			err = closeErr
		}
		if err != nil {
			_ = os.Remove(output)
		}
	}()
	z := zip.NewWriter(f)
	seen := map[string]bool{"manifest.json": true}
	var total int64
	w, err := z.Create("manifest.json")
	if err != nil {
		return err
	}
	if _, err = w.Write(metadata); err != nil {
		return err
	}
	if len(files) > MaxFiles {
		return errors.New("too many backup files")
	}
	for _, item := range files {
		if err = ctx.Err(); err != nil {
			return err
		}
		folded := strings.ToLower(item.Name)
		if !ValidName(item.Name) || seen[folded] {
			return errors.New("duplicate or unsafe backup path")
		}
		seen[folded] = true
		info, statErr := os.Lstat(item.Source)
		if statErr != nil {
			return statErr
		}
		if !info.Mode().IsRegular() {
			return errors.New("nonregular backup file")
		}
		total += info.Size()
		if total > MaxArchiveBytes {
			return errors.New("device backup exceeds 16 GiB")
		}
		header := &zip.FileHeader{Name: item.Name, Method: zip.Deflate}
		header.SetMode(0o600)
		w, err = z.CreateHeader(header)
		if err != nil {
			return err
		}
		r, openErr := os.Open(item.Source)
		if openErr != nil {
			return openErr
		}
		n, copyErr := io.Copy(w, &contextReader{ctx: ctx, r: r})
		after, statErr := r.Stat()
		r.Close()
		if copyErr != nil {
			return copyErr
		}
		if statErr != nil {
			return statErr
		}
		if n != info.Size() || after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
			return errors.New("backup file changed while reading")
		}
	}
	return z.Close()
}

type contextReader struct {
	ctx context.Context
	r   io.Reader
}

func (r *contextReader) Read(b []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.r.Read(b)
}

// Extract validates the full archive before creating files in a fresh staging
// directory. The returned manifest is not applied to the live app here.
func Extract(ctx context.Context, input, stage string) (Manifest, error) {
	var manifest Manifest
	z, err := zip.OpenReader(input)
	if err != nil {
		return manifest, err
	}
	defer z.Close()
	if len(z.File) > MaxFiles+1 {
		return manifest, errors.New("too many backup entries")
	}
	seen := map[string]bool{}
	var total uint64
	for _, f := range z.File {
		name := strings.TrimSuffix(f.Name, "/")
		folded := strings.ToLower(name)
		if !ValidName(name) || seen[folded] || f.Mode()&os.ModeSymlink != 0 || !f.Mode().IsRegular() && !f.FileInfo().IsDir() {
			return manifest, errors.New("unsafe device archive")
		}
		seen[folded] = true
		if f.UncompressedSize64 > uint64(MaxArchiveBytes)-total {
			return manifest, errors.New("device archive expands beyond 16 GiB")
		}
		total += f.UncompressedSize64
		if total > uint64(MaxArchiveBytes) {
			return manifest, errors.New("device archive expands beyond 16 GiB")
		}
	}
	f, err := z.Open("manifest.json")
	if err != nil {
		return manifest, err
	}
	metadata, err := io.ReadAll(io.LimitReader(f, MaxManifestBytes+1))
	f.Close()
	if err != nil || len(metadata) > MaxManifestBytes || json.Unmarshal(metadata, &manifest) != nil {
		return manifest, errors.New("invalid backup manifest")
	}
	if err := manifest.Validate(); err != nil {
		return manifest, err
	}
	for _, p := range manifest.Plugins {
		if !seen[strings.ToLower("plugins/"+p.ID+"/package.zip")] {
			return manifest, fmt.Errorf("missing plugin package: %s", p.ID)
		}
	}
	for _, tr := range manifest.Library.Tracks {
		if tr.Asset != "" && !seen[strings.ToLower(tr.Asset)] {
			return manifest, errors.New("missing backup media")
		}
	}
	if _, err := os.Lstat(stage); !errors.Is(err, fs.ErrNotExist) {
		return manifest, errors.New("backup staging directory must be new")
	}
	if err := os.MkdirAll(stage, 0o700); err != nil {
		return manifest, err
	}
	for _, f := range z.File {
		if err := ctx.Err(); err != nil {
			return manifest, err
		}
		target := filepath.Join(stage, filepath.FromSlash(f.Name))
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o700); err != nil {
				return manifest, err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
			return manifest, err
		}
		r, err := f.Open()
		if err != nil {
			return manifest, err
		}
		w, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err != nil {
			r.Close()
			return manifest, err
		}
		n, copyErr := io.Copy(w, &contextReader{ctx: ctx, r: r})
		closeErr := w.Close()
		r.Close()
		if copyErr != nil {
			return manifest, copyErr
		}
		if closeErr != nil {
			return manifest, closeErr
		}
		if uint64(n) != f.UncompressedSize64 {
			return manifest, errors.New("incomplete archive entry")
		}
	}
	return manifest, nil
}

// EnsureDirectory creates private directories without following links inside a
// destination owned by the app. Call before copying restored private files.
func EnsureDirectory(root, relative string) error {
	if relative != "" && !ValidName(filepath.ToSlash(relative)) {
		return errors.New("unsafe restore directory")
	}
	current := root
	parts := []string{""}
	if relative != "" {
		parts = append(parts, strings.Split(filepath.ToSlash(relative), "/")...)
	}
	for _, part := range parts {
		current = filepath.Join(current, part)
		if err := os.MkdirAll(current, 0o700); err != nil {
			return err
		}
		info, err := os.Lstat(current)
		if err != nil {
			return err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("link in restore destination")
		}
	}
	return nil
}
