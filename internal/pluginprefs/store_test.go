package pluginprefs

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestStoreRecoversDeletedOrCorruptPrimaryAndKeepsExplicitReset(t *testing.T) {
	root := t.TempDir()
	store := NewStore(root, Legacy{Names: map[string]string{"one": "旧名字"}, Enabled: map[string]bool{"one": false}})
	first, err := store.Load()
	if err != nil {
		t.Fatal(err)
	}
	reset, err := store.Update(func(doc Document, writer string) (Document, error) { return doc.WithName("one", "", writer) })
	if err != nil {
		t.Fatal(err)
	}
	if reset.Names["one"].Clock <= first.Names["one"].Clock {
		t.Fatal("reset lost metadata")
	}
	for _, broken := range []bool{false, true} {
		primary := filepath.Join(root, "preferences.json")
		if broken {
			os.WriteFile(primary, []byte("broken"), 0600)
		} else {
			os.Remove(primary)
		}
		restored, err := NewStore(root, Legacy{Names: map[string]string{"one": "不能复活"}}).Load()
		if err != nil || !restored.Equal(reset) {
			t.Fatalf("recovery mismatch: %v", err)
		}
	}
	for _, name := range []string{"preferences.json", "recovery/preferences.json"} {
		os.WriteFile(filepath.Join(root, name), []byte("broken"), 0600)
	}
	if _, err := store.Load(); err == nil {
		t.Fatal("both invalid copies must not replace data with defaults")
	}
}
func TestStoreConcurrentLocalEditsAndRemoteMergeAreDurable(t *testing.T) {
	root := t.TempDir()
	store := NewStore(root, Legacy{})
	remote, err := Empty().WithName("remote", "另一设备", NewWriter())
	if err != nil {
		t.Fatal(err)
	}
	var tasks sync.WaitGroup
	for i := range 2 {
		tasks.Add(1)
		go func() {
			defer tasks.Done()
			var err error
			if i == 0 {
				_, err = store.Update(func(doc Document, writer string) (Document, error) { return doc.WithName("local", "本地", writer) })
			} else {
				_, err = store.Merge(remote)
			}
			if err != nil {
				t.Error(err)
			}
		}()
	}
	tasks.Wait()
	doc, err := NewStore(root, Legacy{}).Load()
	if err != nil {
		t.Fatal(err)
	}
	if doc.Names["local"].Value != "本地" || doc.Names["remote"].Value != "另一设备" {
		t.Fatal("concurrent edit lost")
	}
}
