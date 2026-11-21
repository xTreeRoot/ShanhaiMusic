package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFixedPasswordJoin(t *testing.T) {
	dir := t.TempDir()
	password := " 猫 "
	config := filepath.Join(dir, "node.json")
	if err := createNodeConfig(config, strings.NewReader(password)); err != nil {
		t.Fatal(err)
	}
	if err := createNodeConfig(config, strings.NewReader("replacement")); err == nil {
		t.Fatal("creating node credentials overwrote the original recovery key")
	}
	access, err := loadNodeAccess(config)
	if err != nil {
		t.Fatal(err)
	}
	db, err := openStore(filepath.Join(dir, "test.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	publicKey := randomToken(32)
	node := &api{db: db, catalog: &publishedCatalog{key: publicKey}, node: access}
	join := func(server *api, supplied, platform string, status int) *httptest.ResponseRecorder {
		t.Helper()
		body, _ := json.Marshal(map[string]string{"password": supplied, "name": "Test", "platform": platform})
		request := httptest.NewRequest("POST", "/v1/join", strings.NewReader(string(body)))
		request.Header.Set("Content-Type", "application/json")
		result := httptest.NewRecorder()
		server.handler().ServeHTTP(result, request)
		if result.Code != status {
			t.Fatalf("join status %d, want %d", result.Code, status)
		}
		return result
	}
	join(&api{catalog: node.catalog}, password, "ios", 404)
	join(node, strings.TrimSpace(password), "ios", 403)
	first := join(node, password, "ios", 201)
	second := join(node, password, "android", 201)
	type joined struct {
		deviceGrant
		Key    string `json:"recovery_key"`
		Signer string `json:"catalog_public_key"`
	}
	var phone, tablet joined
	for _, entry := range []struct {
		response *httptest.ResponseRecorder
		grant    *joined
	}{{first, &phone}, {second, &tablet}} {
		if err := json.Unmarshal(entry.response.Body.Bytes(), entry.grant); err != nil || !validToken(entry.grant.Token, 32) ||
			entry.grant.Space != nodeSpace || entry.grant.Key != access.recoveryKey || entry.grant.Signer != publicKey ||
			entry.response.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("invalid join response")
		}
	}
	if phone.ID == tablet.ID || phone.Token == tablet.Token {
		t.Fatal("devices share an identity or token")
	}
	for _, grant := range []joined{phone, tablet} {
		request := httptest.NewRequest("GET", "/v1/sessions/netease", nil)
		request.Header.Set("Authorization", "Bearer "+grant.Token)
		result := httptest.NewRecorder()
		node.handler().ServeHTTP(result, request)
		if result.Code != 200 {
			t.Fatalf("issued token rejected: %d", result.Code)
		}
	}
	shortConfig := filepath.Join(dir, "short.json")
	if err := createNodeConfig(shortConfig, strings.NewReader("1")); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(shortConfig); err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("short password file has invalid permissions: %v", err)
	}
}
