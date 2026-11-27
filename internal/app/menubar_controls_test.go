package app

import (
	"os"
	"path/filepath"
	"testing"

	"mygo-music/internal/local"
	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

func menuBarTestPlugin(t *testing.T, id, script string) *plugin.Plugin {
	t.Helper()
	dir := t.TempDir()
	manifest := []byte(`{"id":"` + id + `","name":"` + id + `"}`)
	if err := os.WriteFile(filepath.Join(dir, "plugin.json"), manifest, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "index.js"), []byte(script), 0o644); err != nil {
		t.Fatal(err)
	}
	p, err := plugin.Load(dir, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	return p
}

func TestMenuBarHeartDoesNotFallBackToLocalLikes(t *testing.T) {
	a := newTestApp()
	a.library = addFake(a, local.New(t.TempDir()))
	a.library.liked = map[string]bool{"shared-id": true}
	readOnly := addFake(a, menuBarTestPlugin(t, "read-only", `export function isLiked() { return {}; }`))
	disabled := menuBarTestPlugin(t, "disabled", `export function like() { throw new Error("disabled source called"); }`)
	a.allLibs = append(a.allLibs, newLibrary(disabled))
	for name, source := range map[string]string{"local": local.ID, "missing": "removed", "unstamped": "", "read only": readOnly.id, "disabled": disabled.Info().ID} {
		t.Run(name, func(t *testing.T) {
			a.pl.Cue([]music.Track{{ID: "shared-id", Source: source}})
			if state := a.menuBarControlState(); state.canLike || state.liked {
				t.Fatalf("unexpected menu bar heart state: %+v", state)
			}
			a.runMenuBarAction(menuBarLike)
			if !a.library.liked["shared-id"] || a.toast != "" {
				t.Fatal("menu bar heart changed a local like")
			}
		})
	}
}

func TestMenuBarReadOnlyPluginShowsItsOwnKnownHeart(t *testing.T) {
	a := newTestApp()
	lib := addFake(a, menuBarTestPlugin(t, "read-only-known", `export function isLiked({ids}) { return Object.fromEntries(ids.map(id => [id, true])); }`))
	lib.rememberLike("saved", true)
	a.pl.Cue([]music.Track{{ID: "saved", Source: lib.id}})
	state := a.menuBarControlState()
	if !state.liked || state.canLike {
		t.Fatalf("readonly heart: %+v", state)
	}
	a.runMenuBarAction(menuBarLike)
	if !lib.liked["saved"] {
		t.Fatal("readonly heart changed")
	}
}
