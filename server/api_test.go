package main

import (
	"context"
	"database/sql"
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
	const overviewPath = "/v1/sync/overview"
	const profilePath = "/v1/data/netease/profile"
	assertStatus(call("GET", overviewPath, "", "", ""), 401)
	profile := call("PUT", profilePath, desktop.Token, "*", body)
	assertStatus(profile, 201)
	fetchedProfile := call("GET", profilePath, phone.Token, "", "")
	assertStatus(fetchedProfile, 200)
	if fetchedProfile.Body.String() != body || fetchedProfile.Header().Get("ETag") != profile.Header().Get("ETag") {
		t.Fatal("second device did not receive the saved profile envelope")
	}
	assertStatus(call("GET", profilePath, other.Token, "", ""), 404)
	inventory := call("GET", overviewPath, phone.Token, "", "")
	assertStatus(inventory, 200)
	var overview struct{ Records []overviewEntry }
	if err := json.Unmarshal(inventory.Body.Bytes(), &overview); err != nil || len(overview.Records) != 2 ||
		overview.Records[0].Kind != "data" || overview.Records[0].Platform != "netease" || overview.Records[0].Key != "profile" ||
		overview.Records[0].Count != 1 || overview.Records[0].Updated <= 0 ||
		overview.Records[1].Kind != "sessions" || overview.Records[1].Count != 1 || overview.Records[1].Updated <= 0 ||
		strings.Contains(inventory.Body.String(), "account_ref") || strings.Contains(inventory.Body.String(), "ciphertext") {
		t.Fatalf("unexpected inventory: %s", inventory.Body.String())
	}
	if isolated := call("GET", overviewPath, other.Token, "", ""); isolated.Body.String() != "{\"records\":[]}\n" {
		t.Fatal("inventory leaked between spaces")
	}
	deletedProfile := call("DELETE", profilePath, phone.Token, profile.Header().Get("ETag"), "")
	assertStatus(deletedProfile, 204)
	assertStatus(call("PUT", profilePath, desktop.Token, "*", body), 412)
	assertStatus(call("GET", profilePath, desktop.Token, "", ""), 410)
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
	if empty := call("GET", overviewPath, phone.Token, "", ""); empty.Body.String() != "{\"records\":[]}\n" {
		t.Fatal("inventory counts deleted records")
	}
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

func TestPlatformDataMigrationPreservesOldSessionsAndGrants(t *testing.T) {
	filename := filepath.Join(t.TempDir(), "v1.sqlite")
	db, err := sql.Open("sqlite", filename)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if _, err := db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	invite, err := createInvite(t.Context(), db, "personal", now)
	if err != nil {
		t.Fatal(err)
	}
	grant, err := pairDevice(t.Context(), db, pairing{Code: invite, Name: "Fixture", Platform: "desktop"}, now)
	if err != nil {
		t.Fatal(err)
	}
	ref := randomToken(32)
	if _, err := changeRecord(t.Context(), db, sessionTable, grant.Space, "example", ref, "", 1, []byte("fixture envelope"), now); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	upgraded, err := openStore(filename)
	if err != nil {
		t.Fatal(err)
	}
	defer upgraded.Close()
	request := httptest.NewRequest("GET", "/v1/sessions/example/"+ref, nil)
	request.Header.Set("Authorization", "Bearer "+grant.Token)
	response := httptest.NewRecorder()
	(&api{db: upgraded, catalog: &publishedCatalog{}}).handler().ServeHTTP(response, request)
	if response.Code != 200 || response.Body.String() != "fixture envelope" {
		t.Fatalf("migration lost session or grant: %d", response.Code)
	}
	if _, err := changeRecord(t.Context(), upgraded, dataTable, grant.Space, "example", "profile", "", 1, []byte("profile"), now); err != nil {
		t.Fatalf("migrated store rejected platform data: %v", err)
	}
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
	if _, err := changeRecord(ctx, db, sessionTable, "personal", "netease", account, "", 1, []byte("snapshot test"), now); err != nil {
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
