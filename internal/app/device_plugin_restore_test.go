package app

import (
	"context"
	"github.com/egoist/mygo"
	"mygo-music/internal/devicebackup"
	"mygo-music/internal/plugin"
	"os"
	"path/filepath"
	"testing"
)

func TestSelectedPluginRestoreOverwritesOnlyItsInformationAndKeepsInstalledCode(t *testing.T) {
	mygo.App.SetPath(mygo.PathUserData, t.TempDir())
	t.Cleanup(func() { mygo.App.SetPath(mygo.PathUserData, "") })
	write := func(path, value string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
	}
	var records []desktopBackupPlugin
	for _, id := range []string{"chosen", "other"} {
		code := t.TempDir()
		write(filepath.Join(code, "plugin.json"), `{"id":"`+id+`","name":"Fixture","settings":[{"key":"mode","label":"Mode","type":"string"}]}`)
		write(filepath.Join(code, "index.js"), `export function search(){return {tracks:[]};}`)
		source, err := plugin.Load(code, t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		defer source.Close()
		if err := source.SetSetting("mode", "from-B"); err != nil {
			t.Fatal(err)
		}
		records = append(records, desktopBackupPlugin{source: source, info: devicebackup.Plugin{ID: id, Enabled: true, Settings: map[string]string{}}})
		write(userDataDir("plugin-data/"+id+"/storage.json"), "from-B-"+id)
	}
	input := desktopBackupInput{settings: defaultSettings(), plugins: records}
	scratch := t.TempDir()
	archive, err := buildDesktopBackup(t.Context(), input, scratch)
	if err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(t.TempDir(), "content")
	manifest, err := devicebackup.Extract(t.Context(), archive, stage)
	if err != nil {
		t.Fatal(err)
	}
	if err := restoreDesktopPluginInfo(t.Context(), stage, manifest, "chosen"); err != nil {
		t.Fatal(err)
	}
	if err := restoreDesktopPluginInfo(t.Context(), stage, manifest, "other"); err != nil {
		t.Fatal(err)
	}
	code := filepath.Join(pluginsDir(), "chosen", "index.js")
	write(code, "// newer installed code")
	write(userDataDir("plugin-data/chosen/storage.json"), "local-A")
	write(userDataDir("plugin-data/chosen/local-only"), "old")
	write(userDataDir("plugin-data/other/storage.json"), "other-local-A")
	write(userDataDir("settings-sentinel.json"), "keep app and library")
	canceled, cancel := context.WithCancel(t.Context())
	cancel()
	if restoreDesktopPluginInfo(canceled, stage, manifest, "chosen") == nil {
		t.Fatal("canceled restore succeeded")
	}
	if restoreDesktopPluginInfo(t.Context(), stage, manifest, "missing") == nil {
		t.Fatal("missing selection succeeded")
	}
	assertFile := func(path, want string) {
		t.Helper()
		b, err := os.ReadFile(path)
		if err != nil || string(b) != want {
			t.Fatalf("unexpected data at %s", path)
		}
	}
	assertFile(userDataDir("plugin-data/chosen/storage.json"), "local-A")
	if err := restoreDesktopPluginInfo(t.Context(), stage, manifest, "chosen"); err != nil {
		t.Fatal(err)
	}
	assertFile(userDataDir("plugin-data/chosen/storage.json"), "from-B-chosen")
	assertFile(userDataDir("plugin-data/other/storage.json"), "other-local-A")
	assertFile(userDataDir("settings-sentinel.json"), "keep app and library")
	assertFile(code, "// newer installed code")
	if _, err := os.Stat(userDataDir("plugin-data/chosen/local-only")); !os.IsNotExist(err) {
		t.Fatal("old selected data survived replacement")
	}
	restored, err := plugin.Load(filepath.Join(pluginsDir(), "chosen"), userDataDir("plugin-data"))
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	if restored.Setting("mode") != "from-B" {
		t.Fatal("selected settings not restored")
	}
}
