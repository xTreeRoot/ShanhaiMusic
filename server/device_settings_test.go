package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDeviceSettingsOwnerAndSpace(t *testing.T) {
	db, err := openStore(filepath.Join(t.TempDir(), "test.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	grant := func(space string) deviceGrant {
		t.Helper()
		code, err := createInvite(ctx, db, space, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		g, err := pairDevice(ctx, db, pairing{Code: code, Name: "Fixture", Platform: "desktop"}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		return g
	}
	owner, peer, other := grant("shared"), grant("shared"), grant("isolated")
	handler := (&api{db: db, catalog: &publishedCatalog{}}).handler()
	call := func(method, path string, g deviceGrant, want int) string {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(`{"format":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`))
		r.Header.Set("Authorization", "Bearer "+g.Token)
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("If-None-Match", "*")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != want {
			t.Fatalf("%s %s: %d, want %d", method, path, w.Code, want)
		}
		return w.Body.String()
	}
	key := hex.EncodeToString([]byte(owner.ID))
	path := "/v1/data/device-settings/" + key
	call("PUT", path, peer, 403)
	call("PUT", path, owner, 201)
	call("GET", path, peer, 200)
	call("GET", path, other, 404)
	inventory := func(g deviceGrant, want bool) {
		t.Helper()
		var list struct {
			Devices []struct {
				Key     string
				Updated int64 `json:"updated_at"`
			}
		}
		if err := json.Unmarshal([]byte(call("GET", "/v1/sync/devices", g, 200)), &list); err != nil {
			t.Fatal(err)
		}
		found := false
		for _, v := range list.Devices {
			if v.Key == key && v.Updated > 0 {
				found = true
			}
		}
		if found != want {
			t.Fatal("unexpected backup visibility")
		}
	}
	inventory(peer, true)
	inventory(other, false)
	if b := call("GET", "/v1/sync/overview", peer, 200); strings.Contains(b, deviceSettingsPlatform) {
		t.Fatal("device backup shown as music source")
	}
	if err := revokeDevice(ctx, db, owner.Space, owner.ID); err != nil {
		t.Fatal(err)
	}
	inventory(peer, false)
	call("GET", path, peer, 404)
}
