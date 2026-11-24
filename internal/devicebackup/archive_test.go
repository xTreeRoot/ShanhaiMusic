package devicebackup

import (
	"archive/zip"
	"context"
	"os"
	"path/filepath"
	"testing"
)

func testManifest() Manifest {
	return Manifest{Version: 1, Platform: "android", Plugins: []Plugin{{ID: "fixture", Enabled: true, Settings: map[string]string{"mode": "all"}}}, Library: Library{Tracks: []Track{{Source: "local", ID: "old", Title: "原标题", Artist: "艺人", Asset: "media/song.mp3"}}, Favorites: []string{"local:old"}, Playlists: []Playlist{{ID: "p", Name: "歌单", Tracks: []string{"local:old"}}}}}
}
func TestDeviceArchiveRoundTripAndCancelledWrite(t *testing.T) {
	root := t.TempDir()
	input := filepath.Join(root, "input")
	if err := os.WriteFile(input, []byte("fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(root, "backup.zip")
	files := []File{{Name: "plugins/fixture/package.zip", Source: input}, {Name: "plugins/fixture/data/storage.json", Source: input}, {Name: "media/song.mp3", Source: input}}
	if err := Write(t.Context(), output, testManifest(), files); err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(root, "stage")
	doc, err := Extract(t.Context(), output, stage)
	if err != nil {
		t.Fatal(err)
	}
	if doc.Plugins[0].Settings["mode"] != "all" || doc.Library.Tracks[0].Title != "原标题" || doc.Library.Playlists[0].Tracks[0] != "local:old" {
		t.Fatal("metadata lost")
	}
	if _, err := Extract(t.Context(), output, stage); err == nil {
		t.Fatal("reused live directory")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	cancelled := filepath.Join(root, "cancelled.zip")
	if err := Write(ctx, cancelled, testManifest(), files); err == nil {
		t.Fatal("cancelled write succeeded")
	}
	if _, err := os.Stat(cancelled); !os.IsNotExist(err) {
		t.Fatal("partial archive retained")
	}
}
func TestRejectUnsafeOrIncompleteDeviceArchive(t *testing.T) {
	for _, name := range []string{"../escape", "/absolute", "plugins\\fixture", "plugins/../escape", "plugins/fixture/../../../escape"} {
		t.Run(name, func(t *testing.T) {
			root := t.TempDir()
			file := filepath.Join(root, "bad.zip")
			w, _ := os.Create(file)
			z := zip.NewWriter(w)
			entry, _ := z.Create(name)
			entry.Write([]byte("bad"))
			z.Close()
			w.Close()
			if _, err := Extract(t.Context(), file, filepath.Join(root, "stage")); err == nil {
				t.Fatal("unsafe archive accepted")
			}
		})
	}
	root := t.TempDir()
	output := filepath.Join(root, "missing.zip")
	if err := Write(t.Context(), output, testManifest(), nil); err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(root, "stage")
	if _, err := Extract(t.Context(), output, stage); err == nil {
		t.Fatal("missing package accepted")
	}
	if _, err := os.Stat(stage); !os.IsNotExist(err) {
		t.Fatal("invalid archive created destination")
	}
	outside := t.TempDir()
	destination := filepath.Join(root, "destination")
	os.Mkdir(destination, 0700)
	os.Symlink(outside, filepath.Join(destination, "link"))
	if err := EnsureDirectory(destination, "link/child"); err == nil {
		t.Fatal("followed target link")
	}
	if _, err := os.Stat(filepath.Join(outside, "child")); !os.IsNotExist(err) {
		t.Fatal("wrote outside destination")
	}
}
