package discoveryprefs

import (
	"os"
	"path/filepath"
	"slices"
	"testing"

	"mygo-music/internal/pluginprefs"
)

func TestDraftMergePreservesRemoteEditsAndUnknownSections(t *testing.T) {
	sections := []Section{{"one", true}, {"two", true}}
	empty := Empty()
	before := empty.Resolve("fixture", sections)
	writer := pluginprefs.NewWriter()
	local, err := empty.Edit("fixture", before, before, writer)
	if err != nil {
		t.Fatal(err)
	}
	remote := local
	draft := local.Resolve("fixture", sections)
	draft.Enabled["two"] = false
	remote, err = remote.Edit("fixture", before, draft, pluginprefs.NewWriter())
	if err != nil {
		t.Fatal(err)
	}
	moved := Selection{Order: []string{"two", "one"}, Enabled: map[string]bool{"one": true, "two": true}}
	merged, err := remote.Edit("fixture", before, moved, writer)
	if err != nil {
		t.Fatal(err)
	}
	resolved := merged.Resolve("fixture", append(sections, Section{"new", true}))
	if resolved.Enabled["two"] || resolved.Enabled["new"] || !slices.Equal(resolved.Order, []string{"two", "one", "new"}) {
		t.Fatal(resolved)
	}
	// 未安装栏目继续保留；重新声明时恢复其显式开关与原排序。
	hidden := merged.Resolve("fixture", sections[:1])
	if len(hidden.Order) != 1 {
		t.Fatal(hidden)
	}
	if _, ok := merged.Plugins["fixture"].Enabled["two"]; !ok {
		t.Fatal("lost unknown entry")
	}
}
func TestCacheRepairsCorruptPrimaryAndDoesNotResetBothCorrupt(t *testing.T) {
	root := t.TempDir()
	store := NewStore(root)
	sections := []Section{{"one", true}}
	initial := Empty().Resolve("fixture", sections)
	doc, err := store.Update(func(d Document, w string) (Document, error) { return d.Edit("fixture", initial, initial, w) })
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, "preferences.json"), []byte("broken"), 0600); err != nil {
		t.Fatal(err)
	}
	restored, err := store.Load()
	if err != nil || !restored.Equal(doc) {
		t.Fatal(err, restored)
	}
	for _, name := range []string{"preferences.json", "recovery/preferences.json"} {
		if err = os.WriteFile(filepath.Join(root, name), []byte("broken"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = store.Load(); err == nil {
		t.Fatal("silently replaced corrupt preferences")
	}
}
func TestConcurrentDevicesConvergeAndAllDisabledStaysEmpty(t *testing.T) {
	sections := []Section{{"one", true}, {"two", true}}
	empty := Empty()
	before := empty.Resolve("fixture", sections)
	draft := Selection{Order: before.Order, Enabled: map[string]bool{"one": false, "two": false}}
	a, err := empty.Edit("fixture", before, draft, pluginprefs.NewWriter())
	if err != nil {
		t.Fatal(err)
	}
	b, err := empty.Edit("fixture", before, before, pluginprefs.NewWriter())
	if err != nil {
		t.Fatal(err)
	}
	ab, err := a.Merge(b)
	if err != nil {
		t.Fatal(err)
	}
	ba, err := b.Merge(a)
	if err != nil || !ab.Equal(ba) {
		t.Fatal(err)
	}
	off := a.Resolve("fixture", sections)
	for _, on := range off.Enabled {
		if on {
			t.Fatal("all off restored a default")
		}
	}
}
