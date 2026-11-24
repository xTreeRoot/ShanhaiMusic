package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func TestBackupObjectOwnershipIsolationAndImmutability(t *testing.T) {
	db, err := openStore(filepath.Join(t.TempDir(), "backup.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := (&api{db: db, catalog: &publishedCatalog{}}).handler()
	register := func(space, platform string) deviceGrant {
		t.Helper()
		code, err := createInvite(t.Context(), db, space, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		grant, err := pairDevice(t.Context(), db, pairing{Code: code, Name: platform, Platform: platform}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		return grant
	}
	owner, peer, outsider := register("home", "desktop"), register("home", "ios"), register("other", "android")
	path := "/v1/backups/" + hex.EncodeToString([]byte(owner.ID)) + "/00112233445566778899aabbccddeeff"
	env := encryptedSession{Format: 1, Nonce: "AAAAAAAAAAAAAAAA", Ciphertext: base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{42}, 128<<10))}
	body, _ := json.Marshal(env)
	call := func(method, token string, body []byte) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	assert := func(w *httptest.ResponseRecorder, code int) {
		t.Helper()
		if w.Code != code {
			t.Fatalf("status %d, expected %d: %s", w.Code, code, w.Body.String())
		}
	}
	assert(call("PUT", peer.Token, body), 403)
	assert(call("PUT", owner.Token, body), 201)
	assert(call("PUT", owner.Token, body), 204)
	read := call("GET", peer.Token, nil)
	assert(read, 200)
	if !bytes.Equal(read.Body.Bytes(), body) {
		t.Fatal("peer received a different encrypted object")
	}
	env.Ciphertext = base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{43}, 128<<10))
	other, _ := json.Marshal(env)
	assert(call("PUT", owner.Token, other), 409)
	assert(call("GET", outsider.Token, nil), 404)
	assert(call("DELETE", peer.Token, nil), 403)
	if err := revokeDevice(t.Context(), db, "home", owner.ID); err != nil {
		t.Fatal(err)
	}
	assert(call("GET", peer.Token, nil), 404)
	assert(call("GET", owner.Token, nil), 401)
}

func TestBackupSchemaUpgradeRetainsExistingRecords(t *testing.T) {
	path := filepath.Join(t.TempDir(), "backup.sqlite")
	db, err := openStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`DROP TABLE backup_objects; PRAGMA user_version=3`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = openStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := initialize(db); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM backup_objects`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("migration: count=%d err=%v", count, err)
	}
}
