package main

import (
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

type dataFixture struct {
	t       *testing.T
	db      *sql.DB
	handler http.Handler
}

func newDataFixture(t *testing.T) *dataFixture {
	t.Helper()
	db, err := openStore(filepath.Join(t.TempDir(), "data.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return &dataFixture{t, db, (&api{db: db, catalog: &publishedCatalog{}}).handler()}
}
func (f *dataFixture) grant(space, platform string) deviceGrant {
	f.t.Helper()
	code, err := createInvite(f.t.Context(), f.db, space, time.Now())
	if err != nil {
		f.t.Fatal(err)
	}
	grant, err := pairDevice(f.t.Context(), f.db, pairing{Code: code, Name: "Synthetic test", Platform: platform}, time.Now())
	if err != nil {
		f.t.Fatal(err)
	}
	return grant
}
func (f *dataFixture) call(method, path, token, tag, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	if tag == "*" {
		req.Header.Set("If-None-Match", tag)
	} else if tag != "" {
		req.Header.Set("If-Match", tag)
	}
	res := httptest.NewRecorder()
	f.handler.ServeHTTP(res, req)
	return res
}
func (f *dataFixture) check(res *httptest.ResponseRecorder, status int, code string) {
	f.t.Helper()
	if res.Code != status {
		f.t.Fatalf("HTTP %d, want %d: %s", res.Code, status, res.Body.String())
	}
	if code != "" {
		var value map[string]string
		if json.Unmarshal(res.Body.Bytes(), &value) != nil || value["error"] != code {
			f.t.Fatalf("incorrect error: %s", res.Body.String())
		}
	}
}

const dataPathFixture = "/v1/data/example/profile"
const dataBodyFixture = `{"format":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`

func TestPlatformDataLifecycle(t *testing.T) {
	f := newDataFixture(t)
	desktop, ios, android, other := f.grant("shared", "desktop"), f.grant("shared", "ios"), f.grant("shared", "android"), f.grant("other", "desktop")
	f.check(f.call("GET", dataPathFixture, "", "", ""), 401, "unauthorized")
	f.check(f.call("GET", dataPathFixture, ios.Token, "", ""), 404, "not_found")
	f.check(f.call("PUT", dataPathFixture, desktop.Token, "", dataBodyFixture), 428, "precondition_required")
	created := f.call("PUT", dataPathFixture, desktop.Token, "*", dataBodyFixture)
	f.check(created, 201, "")
	tag := created.Header().Get("ETag")
	if len(tag) != 24 || !validToken(tag[1:23], 16) {
		t.Fatal("invalid strong revision")
	}
	for _, peer := range []deviceGrant{ios, android} {
		res := f.call("GET", dataPathFixture, peer.Token, "", "")
		f.check(res, 200, "")
		if res.Body.String() != dataBodyFixture || res.Header().Get("ETag") != tag || res.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("peer did not receive identical ciphertext/revision")
		}
	}
	f.check(f.call("GET", dataPathFixture, other.Token, "", ""), 404, "not_found")
	f.check(f.call("DELETE", dataPathFixture, other.Token, tag, ""), 412, "revision_conflict")
	for _, path := range []string{"/v1/data/another/profile", "/v1/data/example/another", "/v1/sessions/example/" + randomToken(32)} {
		f.check(f.call("GET", path, ios.Token, "", ""), 404, "not_found")
	}
	responses := make(chan *httptest.ResponseRecorder, 2)
	var workers sync.WaitGroup
	for _, peer := range []deviceGrant{ios, android} {
		workers.Go(func() { responses <- f.call("PUT", dataPathFixture, peer.Token, tag, dataBodyFixture) })
	}
	workers.Wait()
	close(responses)
	latest := ""
	conflicts := 0
	for res := range responses {
		switch res.Code {
		case 204:
			latest = res.Header().Get("ETag")
		case 412:
			conflicts++
		default:
			t.Fatalf("unexpected status %d", res.Code)
		}
	}
	if latest == "" || latest == tag || conflicts != 1 {
		t.Fatal("concurrent writers must have one winner")
	}
	deleted := f.call("DELETE", dataPathFixture, desktop.Token, latest, "")
	f.check(deleted, 204, "")
	gone := f.call("GET", dataPathFixture, android.Token, "", "")
	f.check(gone, 410, "deleted")
	if gone.Header().Get("ETag") != deleted.Header().Get("ETag") {
		t.Fatal("missing tombstone revision")
	}
	f.check(f.call("PUT", dataPathFixture, ios.Token, "*", dataBodyFixture), 412, "revision_conflict")
	f.check(f.call("PUT", dataPathFixture, ios.Token, latest, dataBodyFixture), 412, "revision_conflict")
	if err := revokeDevice(t.Context(), f.db, "shared", ios.ID); err != nil {
		t.Fatal(err)
	}
	f.check(f.call("GET", dataPathFixture, ios.Token, "", ""), 401, "unauthorized")
	if _, err := f.db.Exec(`UPDATE devices SET expires=0 WHERE id=?`, android.ID); err != nil {
		t.Fatal(err)
	}
	f.check(f.call("GET", dataPathFixture, android.Token, "", ""), 401, "unauthorized")
}

func TestPlatformDataRejectsMalformedWrites(t *testing.T) {
	f := newDataFixture(t)
	grant := f.grant("test", "android")
	for _, test := range []struct {
		name, path, tag, body string
		status                int
	}{
		{"platform", "/v1/data/Bad/profile", "*", dataBodyFixture, 400},
		{"key", "/v1/data/example/bad.key", "*", dataBodyFixture, 400},
		{"weak tag", dataPathFixture, `W/"AAAAAAAAAAAAAAAAAAAAAA"`, dataBodyFixture, 400},
		{"short tag", dataPathFixture, `"a"`, dataBodyFixture, 400},
		{"nonce", dataPathFixture, "*", `{"format":1,"nonce":"x","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`, 400},
		{"format", dataPathFixture, "*", `{"format":0,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`, 400},
		{"unknown field", dataPathFixture, "*", strings.TrimSuffix(dataBodyFixture, "}") + `,"value":"plain"}`, 400},
		{"extra json", dataPathFixture, "*", dataBodyFixture + dataBodyFixture, 400},
		{"oversized", dataPathFixture, "*", `{"format":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"` + strings.Repeat("A", maxEnvelope) + `"}`, 413},
	} {
		t.Run(test.name, func(t *testing.T) {
			f.check(f.call("PUT", test.path, grant.Token, test.tag, test.body), test.status, "")
		})
	}
	var count int
	if err := f.db.QueryRow(`SELECT count(*) FROM platform_data`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("invalid write changed data: %v", err)
	}
}

func TestVersionOneMigrationPreservesGrantsAndSessions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.sqlite")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec(schema); err != nil {
		t.Fatal(err)
	}
	f := &dataFixture{t: t, db: db}
	grant := f.grant("personal", "ios")
	ref := randomToken(32)
	revision, err := changeSession(t.Context(), db, "personal", "example", ref, "", 1, []byte("existing ciphertext"), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = openStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var version int
	if err = db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil || version != 2 {
		t.Fatalf("migration: %d %v", version, err)
	}
	var saved string
	if err = db.QueryRow(`SELECT revision FROM sessions WHERE account=?`, ref).Scan(&saved); err != nil || saved != revision {
		t.Fatal("migration changed session")
	}
	f.db = db
	f.handler = (&api{db: db, catalog: &publishedCatalog{}}).handler()
	f.check(f.call("GET", dataPathFixture, grant.Token, "", ""), 404, "not_found")
	if err = initialize(db); err != nil {
		t.Fatalf("non-idempotent migration: %v", err)
	}
}
