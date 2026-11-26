package app

import (
	"os"
	"slices"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
	"mygo-music/internal/pluginprefs"
)

func TestPluginLocalNameEditorPersistsAndRestoresToEverySource(t *testing.T) {
	a := newTestApp()
	first := a.library
	_, other := addOther(a)
	last := addFake(a, newFakeSource("last"))
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
	if _, ok := tt.Find(Tf("Display Settings for %s", other.name())); ok {
		t.Fatal("the edit list still exposes display settings")
	}
	from, srcOK := tt.Find(Tf("Reorder %s", first.name()))
	to, dstOK := tt.Find(Tf("Reorder %s", last.name()))
	if !srcOK || !dstOK {
		t.Fatalf("drag handles missing: %q", tt.Texts())
	}
	fromX, fromY := from.X+from.W/2, from.Y+from.H/2
	toX, toY := to.X+to.W/2, to.Y+to.H/2
	tt.Press(fromX, fromY)
	tt.Move(fromX, fromY+10)
	tt.Move(toX, toY)
	tt.Release(toX, toY)
	runPost(t, post)
	tt.Frame()
	if !slices.Equal(a.libs, []*library{other, last, first}) {
		t.Fatal("drag did not insert the plugin after the drop target")
	}
	if err := tt.RightClick(Tf("Reorder %s", first.name())); err != nil {
		t.Fatal(err)
	}
	if err := tt.ChooseMenuItem(T("Move Up")); err != nil {
		t.Fatal(err)
	}
	runPost(t, post)
	tt.Frame()
	if !slices.Equal(a.libs, []*library{other, first, last}) {
		t.Fatal("move menu did not update source tabs")
	}
	chooseSource(t, tt, Tf("Rename %s Locally", other.name()))
	clickSettingRole(t, tt, ui.RoleTextField, T("Local Name"))
	tt.Type("取消这个名字")
	click("Cancel")
	if other.name() != "Other" || a.pluginPrefsBusy {
		t.Fatal("cancel kept the draft")
	}
	chooseSource(t, tt, Tf("Rename %s Locally", other.name()))
	clickSettingRole(t, tt, ui.RoleTextField, T("Local Name"))
	tt.Type("我的平台")
	tt.Frame()
	click("Save")
	runPost(t, post)
	tt.Frame()
	if other.name() != "我的平台" {
		t.Fatal("rename did not reach source name")
	}
	click("Finish Editing")
	chooseSource(t, tt, Tf("Settings for %s", other.name()))
	click(Tf("Enable %s", other.name()))
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

func TestPluginSettingsKeepDetailsOutOfTheList(t *testing.T) {
	language := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	t.Cleanup(func() { i18n.SetLanguage(language) })
	a := newTestApp()
	a.pluginPrefs = pluginprefs.NewStore(t.TempDir(), pluginprefs.Legacy{})
	a.settingsPage = "plugins"
	first := a.library
	first.src.(*fakeSource).info.Name = "直播与播客测试插件"
	first.src.(*fakeSource).info.Version = "1.4.2"
	guard := &guardedSource{fakeSource: newFakeSource("guarded"), perms: plugin.Permissions{
		Network: []string{"media.example.test", "account.example.test"},
	}}
	guard.info.Name, guard.info.Version = "音乐来源测试插件", "2.1.0"
	lib := addFake(a, guard)
	lib.account = &music.Account{ID: "1", Name: "已登录的用户"}
	addFake(a, githubPluginFor(t, "video", "视频来源测试插件", "example/video"))
	tt := ui.NewTester(a.settingsView, 640, 520)
	frame := func(name string) {
		t.Helper()
		settled(tt)
		if path := os.Getenv("MM_FRAMES"); path != "" {
			saveFrame(t, path, name, tt.Image())
		}
	}
	frame("plugins-list.png")
	if _, ok := tt.Find(T("Sign Out")); ok {
		t.Fatal("the plugin list exposes account actions")
	}
	chooseSource(t, tt, Tf("Display Settings for %s", lib.name()))
	if !a.discoveryEditor.open || a.discoveryEditor.plugin != lib.id || a.installer.detailsID != "" {
		t.Fatal("display settings did not open directly for the selected plugin")
	}
	chooseSource(t, tt, T("Cancel"))
	chooseSource(t, tt, T("Edit Plugins"))
	frame("plugins-edit.png")
	chooseSource(t, tt, T("Finish Editing"))
	chooseSource(t, tt, Tf("Settings for %s", lib.name()))
	frame("plugins-details.png")
	if _, ok := tt.Find(T("Sign Out")); !ok {
		t.Fatal("the plugin details lost the account action")
	}
	if _, ok := tt.Find(guard.perms.Network[0]); ok {
		t.Fatal("network permissions are expanded by default")
	}
	chooseSource(t, tt, T("Network Permissions"))
	frame("plugins-permissions.png")
	if _, ok := tt.Find(guard.perms.Network[0]); !ok {
		t.Fatal("network permissions did not expand")
	}
	chooseSource(t, tt, T("Back to Plugins"))
	chooseSource(t, tt, Tf("Settings for %s", lib.name()))
	if _, ok := tt.Find(guard.perms.Network[0]); ok {
		t.Fatal("reopening details left network permissions expanded")
	}
}

// Main-window editing must not inherit a search left in the separate settings window.
func TestPluginManagerEditingIgnoresSettingsSearch(t *testing.T) {
	a := newTestApp()
	a.pluginPrefs = pluginprefs.NewStore(t.TempDir(), pluginprefs.Legacy{})
	a.settingsQuery = "unrelated settings search"
	a.openPlugins()
	view := ui.NewTester(a.view, 1200, 800)
	settled(view)
	if err := view.Click(T("Edit Plugins")); err != nil {
		t.Fatal(err)
	}
	settled(view)
	if _, ok := view.Find(Tf("Reorder %s", a.library.name())); !ok {
		t.Fatal("main manager did not enter editing")
	}
	if err := view.Click(T("Finish Editing")); err != nil {
		t.Fatal(err)
	}
	settled(view)
	if _, ok := view.Find(Tf("Display Settings for %s", a.library.name())); !ok {
		t.Fatal("main manager did not leave editing")
	}
}
