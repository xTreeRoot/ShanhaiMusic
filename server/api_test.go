package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestSyncLifecycle(t *testing.T) {
	db, err := openStore(filepath.Join(t.TempDir(), "test.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := (&api{db: db, catalog: &publishedCatalog{}}).handler()
	call := func(method, path, token, precondition, body string) *httptest.ResponseRecorder {
		request := httptest.NewRequest(method, path, strings.NewReader(body))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Authorization", "Bearer "+token)
		if precondition == "*" {
			request.Header.Set("If-None-Match", precondition)
		} else if precondition != "" {
			request.Header.Set("If-Match", precondition)
		}
		result := httptest.NewRecorder()
		handler.ServeHTTP(result, request)
		return result
	}
	assertStatus := func(result *httptest.ResponseRecorder, expected int) {
		t.Helper()
		if result.Code != expected {
			t.Fatalf("status %d, want %d: %s", result.Code, expected, result.Body.String())
		}
	}
	register := func(space, platform string) deviceGrant {
		t.Helper()
		code, err := createInvite(t.Context(), db, space, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		body, _ := json.Marshal(pairing{Code: code, Name: "Test " + platform, Platform: platform})
		result := call("POST", "/v1/pair", "", "", string(body))
		assertStatus(result, 201)
		var grant deviceGrant
		if err := json.Unmarshal(result.Body.Bytes(), &grant); err != nil || grant.Space != space {
			t.Fatalf("invalid grant: %v", err)
		}
		assertStatus(call("POST", "/v1/pair", "", "", string(body)), 403)
		return grant
	}
	desktop := register("personal", "desktop")
	phone := register("personal", "ios")
	other := register("other", "android")
	path := "/v1/sessions/netease/" + randomToken(32)
	const body = `{"format":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`
	assertStatus(call("GET", path, "", "", ""), 401)
	assertStatus(call("GET", path, phone.Token, "", ""), 404)
	assertStatus(call("PUT", path, desktop.Token, "", body), 428)
	assertStatus(call("PUT", path, desktop.Token, "*", `{"format":1,"nonce":"bad","ciphertext":"bad"}`), 400)
	created := call("PUT", path, desktop.Token, "*", body)
	assertStatus(created, 201)
	initial := created.Header().Get("ETag")
	fetched := call("GET", path, phone.Token, "", "")
	assertStatus(fetched, 200)
	if fetched.Body.String() != body || fetched.Header().Get("ETag") != initial || fetched.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("peer did not receive matching ciphertext, revision and cache policy")
	}
	assertStatus(call("GET", path, other.Token, "", ""), 404)
	assertStatus(call("DELETE", path, other.Token, initial, ""), 412)
	responses := make(chan *httptest.ResponseRecorder, 2)
	var group sync.WaitGroup
	for _, token := range []string{desktop.Token, phone.Token} {
		group.Go(func() { responses <- call("PUT", path, token, initial, body) })
	}
	group.Wait()
	close(responses)
	var newETag string
	var conflicts int
	for response := range responses {
		switch response.Code {
		case 204:
			newETag = response.Header().Get("ETag")
		case 412:
			conflicts++
		}
	}
	if newETag == "" || newETag == initial || conflicts != 1 {
		t.Fatal("exactly one concurrent update should succeed")
	}
	deleted := call("DELETE", path, phone.Token, newETag, "")
	assertStatus(deleted, 204)
	assertStatus(call("PUT", path, desktop.Token, newETag, body), 412)
	assertStatus(call("PUT", path, desktop.Token, "*", body), 412)
	assertStatus(call("GET", path, desktop.Token, "", ""), 410)
	listing := call("GET", "/v1/sessions/netease", desktop.Token, "", "")
	assertStatus(listing, 200)
	var records struct{ Sessions []sessionEntry }
	if err := json.Unmarshal(listing.Body.Bytes(), &records); err != nil || len(records.Sessions) != 1 ||
		!records.Sessions[0].Deleted || records.Sessions[0].ETag != deleted.Header().Get("ETag") {
		t.Fatalf("missing deletion record: %s", listing.Body.String())
	}
	assertStatus(call("PUT", path, desktop.Token, records.Sessions[0].ETag, body), 204)
	if err := revokeDevice(t.Context(), db, "personal", phone.ID); err != nil {
		t.Fatal(err)
	}
	assertStatus(call("GET", path, phone.Token, "", ""), 401)
	if _, err := db.ExecContext(t.Context(), `UPDATE devices SET expires=0 WHERE id=?`, desktop.ID); err != nil {
		t.Fatal(err)
	}
	assertStatus(call("GET", path, desktop.Token, "", ""), 401)
}

func TestInviteExpiryAndBackup(t *testing.T) {
	dir := t.TempDir()
	db, err := openStore(filepath.Join(dir, "store.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Now()
	ctx := context.Background()
	code, err := createInvite(ctx, db, "personal", now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pairDevice(ctx, db, pairing{Code: code, Name: "Phone", Platform: "ios"}, now.Add(time.Hour)); err == nil {
		t.Fatal("accepted expired invite")
	}
	account := randomToken(32)
	if _, err := changeSession(ctx, db, "personal", "netease", account, "", 1, []byte("snapshot test"), now); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "backup.sqlite")
	if err := backupStore(ctx, db, path); err != nil {
		t.Fatal(err)
	}
	if err := backupStore(ctx, db, path); err == nil {
		t.Fatal("backup overwrote an existing snapshot")
	}
	snapshot, err := openStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer snapshot.Close()
	var value string
	if err := snapshot.QueryRow(`SELECT envelope FROM sessions WHERE account=?`, account).Scan(&value); err != nil || value != "snapshot test" {
		t.Fatalf("snapshot lost committed WAL data: %v", err)
	}
}

func TestPairBodyLimits(t *testing.T) {
	handler := (&api{catalog: &publishedCatalog{}}).handler()
	for _, example := range []struct {
		contentType string
		body        string
		status      int
	}{
		{"text/plain", `{}`, http.StatusUnsupportedMediaType},
		{"application/json", `{}` + `{}`, http.StatusBadRequest},
		{"application/json", `{"code":"` + strings.Repeat("a", 4096) + `"}`, http.StatusRequestEntityTooLarge},
	} {
		request := httptest.NewRequest("POST", "/v1/pair", strings.NewReader(example.body))
		request.Header.Set("Content-Type", example.contentType)
		result := httptest.NewRecorder()
		handler.ServeHTTP(result, request)
		if result.Code != example.status {
			t.Errorf("status %d, want %d", result.Code, example.status)
		}
	}
}
