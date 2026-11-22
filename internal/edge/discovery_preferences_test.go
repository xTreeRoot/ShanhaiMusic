package edge

import (
	"context"
	"encoding/json"
	"mygo-music/internal/discoveryprefs"
	"mygo-music/internal/pluginprefs"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestDiscoveryPullRestoresMissingLocalWithoutWritingPluginsRecord(t *testing.T) {
	sections := []discoveryprefs.Section{{ID: "one", DefaultEnabled: true}}
	empty := discoveryprefs.Empty()
	before := empty.Resolve("fixture", sections)
	draft := discoveryprefs.Selection{Order: before.Order, Enabled: map[string]bool{"one": false}}
	remote, err := empty.Edit("fixture", before, draft, pluginprefs.NewWriter())
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/data/app/discovery" || r.Method != "GET" {
			t.Error("unexpected request", r.Method, r.URL.Path)
		}
		sealed, err := sealData(testRecovery, "test-space", "app", "discovery", 1, remote)
		if err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("ETag", `"AAECAwQFBgcICQoLDA0ODw"`)
		json.NewEncoder(w).Encode(sealed)
	}))
	defer server.Close()
	node, _ := OpenSync(newClient(server.URL), t.TempDir())
	node.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	node.state.Recovery = testRecovery
	store := discoveryprefs.NewStore(t.TempDir())
	if err = node.SyncDiscoveryPreferences(context.Background(), store.Load, store.Merge); err != nil {
		t.Fatal(err)
	}
	local, err := store.Load()
	if err != nil || !local.Equal(remote) || local.Resolve("fixture", sections).Enabled["one"] {
		t.Fatal(local, err)
	}
}
