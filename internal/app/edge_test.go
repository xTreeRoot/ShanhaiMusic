package app

import (
	"os"
	"strings"
	"testing"

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
