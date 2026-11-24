package main

import (
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDeviceRenameKeepsGrantAndOnlyChangesCurrentDevice(t *testing.T) {
	db, err := openStore(filepath.Join(t.TempDir(), "node.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	register := func(space string) deviceGrant {
		t.Helper()
		code, err := createInvite(t.Context(), db, space, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		grant, err := pairDevice(t.Context(), db, pairing{Code: code, Name: "Before", Platform: "ios"}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		return grant
	}
	a, b, other := register("home"), register("home"), register("other")
	// Give B a snapshot so it is visible from A using the existing list contract.
	_, err = db.Exec(`INSERT INTO platform_data VALUES(?,?,?,?,?,?,?)`, "home", deviceSettingsPlatform, hex.EncodeToString([]byte(b.ID)), "r", 1, []byte(`{}`), 1)
	if err != nil {
		t.Fatal(err)
	}
	handler := (&api{db: db, catalog: &publishedCatalog{}}).handler()
	call := func(method, path, token, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	check := func(w *httptest.ResponseRecorder, code int) {
		t.Helper()
		if w.Code != code {
			t.Fatalf("HTTP %d, expected %d: %s", w.Code, code, w.Body.String())
		}
	}
	check(call("PATCH", "/v1/sync/device", "", `{"name":"changed"}`), 401)
	for _, name := range []string{"", "  ", "bad\nname", strings.Repeat("中", 41)} {
		body, _ := json.Marshal(map[string]string{"name": name})
		check(call("PATCH", "/v1/sync/device", b.Token, string(body)), 400)
	}
	check(call("PATCH", "/v1/sync/device", b.Token, `{"name":" 工作 iPad "}`), 204)
	check(call("PATCH", "/v1/sync/device", a.Token, `{"name":"unsafe","device":"`+b.ID+`"}`), 400)
	listed := call("GET", "/v1/sync/devices", a.Token, "")
	check(listed, 200)
	if !strings.Contains(listed.Body.String(), "工作 iPad") {
		t.Fatal("peer did not see renamed device")
	}
	for _, grant := range []deviceGrant{a, other} {
		var name string
		if err := db.QueryRow(`SELECT name FROM devices WHERE id=?`, grant.ID).Scan(&name); err != nil || name != "Before" {
			t.Fatal("another device changed")
		}
	}
	check(call("GET", "/v1/sync/overview", b.Token, ""), 200)
	if err := revokeDevice(t.Context(), db, "home", b.ID); err != nil {
		t.Fatal(err)
	}
	check(call("PATCH", "/v1/sync/device", b.Token, `{"name":"revoked"}`), 401)
}
