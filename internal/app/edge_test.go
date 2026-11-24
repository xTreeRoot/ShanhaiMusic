package app

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
	"mygo-music/internal/i18n"
)

func TestEdgeSettingsRequiresAUserAddressAndRejectsHTTP(t *testing.T) {
	a := newTestApp()
	a.edge.nodes = edge.NewNodes(t.TempDir())
	tester := ui.NewTester(a.edgeView, 620, 640)
	tester.Frame()
	if a.edge.address != "" || a.edge.password != "" {
		t.Fatal("node settings unexpectedly prefilled")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "desktop-node-settings.png", tester.Image())
	}
	if err := tester.Click(T("Server Domain or HTTPS Address")); err != nil {
		t.Fatal(err)
	}
	tester.Type("http://node.example")
	if err := tester.Click(T("Password")); err != nil {
		t.Fatal(err)
	}
	tester.Type("synthetic fixture")
	if err := tester.Click(T("Connect to Node") + " MB Server"); err != nil {
		t.Fatal(err)
	}
	if a.edge.busy || !strings.Contains(a.edge.message, "HTTPS") || a.edge.service != nil {
		t.Fatal("invalid HTTP node reached the connection flow")
	}
}

func TestEdgeCloudDeleteRequiresConfirmationAndCanBeCancelled(t *testing.T) {
	a := newTestApp()
	other := addFake(a, newFakeSource("second"))
	posted := make(chan func(), 1)
	a.postFn = func(fn func()) { posted <- fn }
	flush := func() {
		t.Helper()
		select {
		case fn := <-posted:
			fn()
		case <-time.After(3 * time.Second):
			t.Fatal("node action timed out")
		}
	}
	a.edge.profileContext = t.Context()
	a.edge.profileJobs = make(map[*library]context.CancelFunc)
	dir := t.TempDir()
	key := strings.Repeat("A", 43)
	data, err := json.Marshal(map[string]any{
		"Grant":    edge.Grant{SpaceID: "fixture", Token: key, ExpiresAt: time.Now().Add(time.Hour).Unix()},
		"Recovery": key,
		"Refs":     map[string]string{other.id: key},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "sync.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	service, err := edge.OpenSync(nil, dir)
	if err != nil {
		t.Fatal(err)
	}
	a.edge.node, a.edge.service = &edge.Node{Address: "https://example.test", Sync: service}, service
	a.edge.overview = &edge.Overview{Sessions: 1, Profile: edge.RecordMissing, PluginPreferences: edge.RecordStored, Discovery: edge.RecordStored}
	tester := ui.NewTester(a.edgeView, 620, 900)
	settled(tester)
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "desktop-node-cloud.png", tester.Image())
	}
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Connect to Node")+" MB Server"); ok {
		t.Fatal("connected device still shows the join form")
	}
	if err := tester.Click(other.name()); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if err := tester.Click(T("Sync profile")); err != nil {
		t.Fatal(err)
	}
	flush()
	tester.Frame()
	if a.library == other || a.edge.message != Tf("%s · Sign in on this device first", other.name()) {
		t.Fatal("sync did not target the expanded source")
	}
	if err := tester.Click(T("Delete This Source’s Cloud Session…")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Confirm Cloud Deletion")); !ok || !service.HasRemote(other.id) {
		t.Fatal("first click must present confirmation without deleting the session")
	}
	if err := tester.Click(T("Cancel")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Confirm Cloud Deletion")); ok || a.edge.deleteFor != "" || !service.HasRemote(other.id) {
		t.Fatal("cancel did not dismiss confirmation and preserve the cloud reference")
	}
	if err := tester.Click(T("Unlink This Device")); err != nil {
		t.Fatal(err)
	}
	flush()
	tester.Frame()
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Connect to Node")+" MB Server"); !ok || service.Authorized() {
		t.Fatal("unlink did not return to the disconnected form")
	}
}

func TestEdgeFormClearAndPasswordVisibilityPreserveInput(t *testing.T) {
	a := newTestApp()
	a.edge.address = "https://synthetic.example"
	a.edge.password = "synthetic fixture"
	tester := ui.NewTester(a.edgeView, 620, 640)
	tester.Frame()
	if err := tester.Click(T("Show Password")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if !a.edge.passwordVisible || a.edge.password != "synthetic fixture" {
		t.Fatal("revealing the password changed its contents")
	}
	if err := tester.Click(T("Hide Password")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if a.edge.passwordVisible {
		t.Fatal("password did not hide")
	}
	if err := tester.Click(T("Clear Node Address")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if a.edge.address != "" || a.edge.password != "synthetic fixture" {
		t.Fatal("clearing the address changed another field")
	}
	tester.Frame()
}

func TestEdgeRestoreDeviceSettingsKeepsUnsupportedPreferences(t *testing.T) {
	a := newTestApp()
	a.settings = defaultSettings()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	a.settings.Source = "current"
	a.edge.profileContext = t.Context()
	a.edge.service = &edge.Sync{}
	a.edge.devices = []edge.SettingsDevice{{Key: "synthetic", Name: "iPad", Updated: 1}}
	a.edge.previewKey = "synthetic"
	a.edge.preview = &edge.DeviceSettings{Version: 1, App: map[string]string{
		"quality": "lossless", "smoke": "true", "language": "invalid", "source": "other",
	}, Plugins: map[string]map[string]string{"missing": {"mode": "auto"}}}
	oldLanguage := i18n.Language()
	t.Cleanup(func() { i18n.SetLanguage(oldLanguage) })
	jobs := make(chan func(), 1)
	a.postFn = func(job func()) { jobs <- job }
	tt := ui.NewTester(func(c *ui.Context) { a.edgeDevicesView(c, paletteFor(c.Theme())) }, 620, 700)
	settled(tt)
	group := T("App preferences") + " · " + Tf("%d stored", 4)
	if err := tt.Click(T("Restore from another device")); err != nil {
		t.Fatal(err)
	}
	settled(tt)
	if err := tt.Click(group); err != nil {
		t.Fatal(err)
	}
	settled(tt)
	if a.edge.settingsGroup != "app" {
		t.Fatal("did not expand the snapshot preferences")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "desktop-device-settings.png", tt.Image())
	}
	if err := tt.Click(T("Restore these settings")); err != nil {
		t.Fatal(err)
	}
	select {
	case fn := <-jobs:
		fn()
	case <-time.After(3 * time.Second):
		t.Fatal("restore timed out")
	}
	if a.settings.Quality != "lossless" || !a.settings.Smoke || a.settings.Source != "current" || a.settings.Language != i18n.System || a.edge.message != Tf("%d restored · %d skipped", 2, 3) {
		t.Fatalf("unexpected result: %s", a.edge.message)
	}
	var persisted settings
	raw, err := os.ReadFile(a.settings.path)
	if err != nil || json.Unmarshal(raw, &persisted) != nil || persisted.Quality != "lossless" {
		t.Fatal("restored quality was not persisted")
	}
}
