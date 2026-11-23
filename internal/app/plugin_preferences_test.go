package app

import (
	"os"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/pluginprefs"
)

func TestPluginLocalNameEditorPersistsAndRestoresToEverySource(t *testing.T) {
	a := newTestApp()
	_, other := addOther(a)
	post := posts(a)
	store := pluginprefs.NewStore(t.TempDir(), pluginprefs.Legacy{})
	a.pluginPrefs = store
	a.settingsPage = "plugins"
	tt := ui.NewTester(a.settingsView, 800, 800)
	click := func(label string) {
		t.Helper()
		if err := tt.Click(T(label)); err != nil {
			t.Fatalf("%v; shows %q", err, tt.Texts())
		}
		tt.Frame()
	}
	tt.Frame()
	click("Edit Plugins")
	click("Move Down")
	runPost(t, post)
	tt.Frame()
	if a.libs[0] != other {
		t.Fatal("move did not update source tabs")
	}
	click("Rename Locally")
	clickSettingRole(t, tt, ui.RoleTextField, T("Local Name"))
	tt.Type("我的平台")
	tt.Frame()
	click("Save")
	runPost(t, post)
	tt.Frame()
	if other.name() != "我的平台" {
		t.Fatal("rename did not reach source name")
	}
	if path := os.Getenv("MM_FRAMES"); path != "" {
		saveFrame(t, path, "plugins-edit.png", tt.Image())
	}
	click("Finish Editing")
	a.setPluginEnabled(other, false)
	runPost(t, post)
	if a.pluginEnabled(other) {
		t.Fatal("disabled preference was not applied")
	}
	doc, err := store.Load()
	if err != nil {
		t.Fatal(err)
	}
	b := newTestApp()
	_, restored := addOther(b)
	b.applyPluginPreferences(doc)
	if restored.name() != "我的平台" || b.pluginEnabled(restored) || b.allLibs[0] != restored {
		t.Fatal("reopened app lost name/order/enabled")
	}
}
