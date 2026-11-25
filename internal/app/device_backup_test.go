package app

import (
	"github.com/egoist/mygo"
	"mygo-music/internal/devicebackup"
	"mygo-music/internal/local"
	"mygo-music/internal/plugin"
	"mygo-music/internal/pluginprefs"
	"os"
	"path/filepath"
	"testing"
)

func TestCompleteDeviceArchiveRestoresBlankPluginHostAndKeepsExistingStorage(t *testing.T) {
	mygo.App.SetPath(mygo.PathUserData, t.TempDir())
	t.Cleanup(func() { mygo.App.SetPath(mygo.PathUserData, "") })
	code := t.TempDir()
	write := func(file, value string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(file), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(code, "plugin.json"), `{"id":"backup-fixture","name":"Fixture","settings":[{"key":"mode","type":"string","label":"Mode"}]}`)
	write(filepath.Join(code, "index.js"), `export function search(){return {tracks:[]};}`)
	source, err := plugin.Load(code, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer source.Close()
	if err := source.SetSetting("mode", "complete"); err != nil {
		t.Fatal(err)
	}
	prefs := pluginprefs.NewStore(userDataDir("plugin-preferences"), pluginprefs.Legacy{})
	input := desktopBackupInput{settings: defaultSettings(), local: local.New(t.TempDir()), preferences: prefs, plugins: []desktopBackupPlugin{{source: source, info: devicebackup.Plugin{ID: "backup-fixture", Name: "我的插件", Enabled: true, Settings: map[string]string{}}}}}
	input.settings.path = filepath.Join(t.TempDir(), "settings.json")
	// Data lives separately from code; use a source-host file for the export.
	privateFile := userDataDir("plugin-data/backup-fixture/storage.json")
	write(privateFile, `{"session":"synthetic"}`)
	scratch := t.TempDir()
	archive, err := buildDesktopBackup(t.Context(), input, scratch)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(privateFile); err != nil {
		t.Fatal(err)
	}
	stage := filepath.Join(t.TempDir(), "stage")
	manifest, err := devicebackup.Extract(t.Context(), archive, stage)
	if err != nil {
		t.Fatal(err)
	}
	result, err := restoreDesktopArchive(t.Context(), stage, manifest, input, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.ids) != 1 || result.preferences.Names["backup-fixture"].Value != "我的插件" || !result.preferences.Enabled["backup-fixture"].Value {
		t.Fatal("presentation lost")
	}
	bytes, err := os.ReadFile(privateFile)
	if err != nil || string(bytes) != `{"session":"synthetic"}` {
		t.Fatal("private data lost")
	}
	installed, err := plugin.Load(filepath.Join(pluginsDir(), "backup-fixture"), userDataDir("plugin-data"))
	if err != nil {
		t.Fatal(err)
	}
	defer installed.Close()
	if installed.Setting("mode") != "complete" {
		t.Fatal("full settings lost")
	}
	write(privateFile, `{"session":"current"}`)
	if _, err := restoreDesktopArchive(t.Context(), stage, manifest, input, nil, false); err != nil {
		t.Fatal(err)
	}
	bytes, _ = os.ReadFile(privateFile)
	if string(bytes) != `{"session":"current"}` {
		t.Fatal("existing account replaced")
	}
	if _, err := restoreDesktopArchive(t.Context(), stage, manifest, input, nil, true); err != nil {
		t.Fatal(err)
	}
	bytes, err = os.ReadFile(privateFile)
	if err != nil || string(bytes) != `{"session":"synthetic"}` {
		t.Fatal("explicit overwrite did not replace the saved session")
	}
}
