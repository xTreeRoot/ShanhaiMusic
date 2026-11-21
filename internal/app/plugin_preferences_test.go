package app

import (
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/pluginprefs"
	"testing"
)

func TestPluginLocalNameEditorPersistsAndRestoresToEverySource(t *testing.T) {
	a := newTestApp()
	_, other := addOther(a)
	post := posts(a)
	store := pluginprefs.NewStore(t.TempDir(), pluginprefs.Legacy{})
	a.pluginPrefs = store
	a.installer.renamingID = other.id
	tt := ui.NewTester(func(c *ui.Context) { a.pluginNameEditor(c, other) }, 640, 300)
	tt.Frame()
	clickSettingRole(t, tt, ui.RoleTextField, T("Local Name"))
	tt.Type("我的平台")
	tt.Frame()
	if err := tt.Click(T("Save")); err != nil {
		t.Fatal(err)
	}
	runPost(t, post)
	tt.Frame()
	if other.name() != "我的平台" {
		t.Fatal("rename did not reach source name")
	}
	a.movePlugin(other, -1)
	runPost(t, post)
	if a.libs[0] != other {
		t.Fatal("move did not update source tabs")
	}
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
