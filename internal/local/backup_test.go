package local

import (
	"mygo-music/internal/devicebackup"
	"os"
	"path/filepath"
	"testing"
)

func TestDeviceRestoreMapsReferencesAndKeepsPortableMetadata(t *testing.T) {
	stage := t.TempDir()
	os.Mkdir(filepath.Join(stage, "media"), 0700)
	if err := os.WriteFile(filepath.Join(stage, "media", "digest.mp3"), fixture(t, "seek.mp3"), 0600); err != nil {
		t.Fatal(err)
	}
	incoming := devicebackup.Library{Tracks: []devicebackup.Track{{Source: "local", ID: "other-device", Asset: "media/digest.mp3", Title: "原来的歌名", Artist: "原来的艺人", Album: "原来的专辑", Duration: 5000}}, Favorites: []string{"local:other-device"}, Recent: []string{"local:other-device"}, Playlists: []devicebackup.Playlist{{ID: "playlist", Name: "歌单", Tracks: []string{"local:other-device"}}}}
	source := New(t.TempDir())
	if err := source.RestoreDeviceBackup(t.Context(), incoming, stage); err != nil {
		t.Fatal(err)
	}
	wait(t, source)
	track := songNamed(t, source, "原来的歌名")
	if track.ID == "other-device" || track.ArtistNames() != "原来的艺人" || track.Album.Name != "原来的专辑" {
		t.Fatal("metadata or identity lost")
	}
	liked, err := source.Liked(0)
	if err != nil || len(liked.Tracks) != 1 || liked.Tracks[0].ID != track.ID {
		t.Fatalf("likes not remapped: %v %v", liked, err)
	}
	playlist, err := source.Playlist("playlist")
	if err != nil || len(playlist.Tracks) != 1 || playlist.Tracks[0].ID != track.ID {
		t.Fatalf("playlist not remapped: %v %v", playlist, err)
	}
	reloaded := New(source.dir)
	rescan(t, reloaded)
	if got := songNamed(t, reloaded, "原来的歌名"); got.ID != track.ID {
		t.Fatal("metadata did not persist")
	}
}
