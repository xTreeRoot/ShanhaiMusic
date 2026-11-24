package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
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
	a.library.accountAsked = true
	dir := t.TempDir()
	key := strings.Repeat("A", 43)
	data, err := json.Marshal(map[string]any{
		"Grant":    edge.Grant{SpaceID: "fixture", Token: key, ExpiresAt: time.Now().Add(time.Hour).Unix()},
		"Recovery": key,
		"Refs":     map[string]string{a.library.id: key},
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
	a.edge.overviewSource = a.library
	a.edge.overview = &edge.Overview{Sessions: 1, Profile: edge.RecordMissing, PluginPreferences: edge.RecordStored, Discovery: edge.RecordStored}
	tester := ui.NewTester(a.edgeView, 620, 900)
	settled(tester)
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "desktop-node-cloud.png", tester.Image())
	}
	if err := tester.Click(T("Delete This Source’s Cloud Session…")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Confirm Cloud Deletion")); !ok || !service.HasRemote(a.library.id) {
		t.Fatal("first click must present confirmation without deleting the session")
	}
	if err := tester.Click(T("Cancel")); err != nil {
		t.Fatal(err)
	}
	tester.Frame()
	if _, ok := tester.AccessByRole(ui.RoleButton, T("Confirm Cloud Deletion")); ok || a.edge.deleteFor != "" || !service.HasRemote(a.library.id) {
		t.Fatal("cancel did not dismiss confirmation and preserve the cloud reference")
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
