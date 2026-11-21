package edge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"mygo-music/internal/pluginprefs"
)

func TestPluginPreferencesPullMergeCASAndLateLocalEdit(t *testing.T) {
	const writer = "AAECAwQFBgcICQoLDA0ODw"
	local := pluginprefs.Empty()
	remote, _ := pluginprefs.Empty().WithName("plugin-b", "远端名字", writer)
	var exists, conflict, editDuringRead bool
	writes := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/data/app/plugins" {
			t.Error(r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("ETag", `"AAECAwQFBgcICQoLDA0ODw"`)
		if r.Method == "GET" {
			if editDuringRead {
				local, _ = local.WithEnabled("not-installed", false, writer)
				editDuringRead = false
			}
			if !exists {
				w.WriteHeader(404)
				w.Write([]byte(`{"error":"not_found"}`))
				return
			}
			sealed, _ := sealData(testRecovery, "test-space", "app", "plugins", 1, remote)
			json.NewEncoder(w).Encode(sealed)
			return
		}
		writes++
		if conflict {
			conflict = false
			remote, _ = remote.WithName("plugin-a", "并发名称", "EBESExQVFhcYGRobHB0eHw")
			w.WriteHeader(412)
			return
		}
		var sealed Envelope
		if json.NewDecoder(r.Body).Decode(&sealed) != nil || openData(testRecovery, "test-space", "app", "plugins", sealed, &remote) != nil {
			t.Error("bad write")
		}
		if exists {
			if r.Header.Get("If-Match") == "" {
				t.Error("unconditional replacement")
			}
			w.WriteHeader(204)
		} else {
			if r.Header.Get("If-None-Match") != "*" {
				t.Error("unconditional creation")
			}
			w.WriteHeader(201)
		}
		exists = true
	}))
	defer server.Close()
	node, _ := OpenSync(newClient(server.URL), t.TempDir())
	node.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	node.state.Recovery = testRecovery
	sync := func() error {
		return node.SyncPluginPreferences(context.Background(), func() (pluginprefs.Document, error) { return local, nil }, func(doc pluginprefs.Document) (pluginprefs.Document, error) {
			var err error
			local, err = local.Merge(doc)
			return local, err
		})
	}
	if err := sync(); err != nil || writes != 0 {
		t.Fatal("empty created remote", err)
	}
	exists = true
	if err := sync(); err != nil || writes != 0 || local.Names["plugin-b"].Value != "远端名字" {
		t.Fatal("empty local not restored", err)
	}
	local, _ = local.WithOrder([]string{"plugin-b", "plugin-a"}, writer)
	conflict, editDuringRead = true, true
	if err := sync(); err != nil || writes != 2 || remote.Order == nil || remote.Names["plugin-a"].Value != "并发名称" || remote.Enabled["not-installed"].Value || !local.Equal(remote) {
		t.Fatalf("CAS merge failed: %+v %v writes=%d", remote, err, writes)
	}
}

func TestPluginPreferencesRejectsBadCiphertextTombstoneAndStaleBinding(t *testing.T) {
	for _, mode := range []string{"bad", "deleted", "stale"} {
		t.Run(mode, func(t *testing.T) {
			var node *Sync
			commits, writes := 0, 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != "GET" {
					writes++
					w.WriteHeader(500)
					return
				}
				w.Header().Set("Content-Type", "application/json")
				if mode == "stale" {
					node.Unbind()
					w.WriteHeader(404)
					w.Write([]byte(`{"error":"not_found"}`))
					return
				}
				if mode == "deleted" {
					w.WriteHeader(410)
					w.Write([]byte(`{"error":"deleted"}`))
					return
				}
				w.Header().Set("ETag", `"AAECAwQFBgcICQoLDA0ODw"`)
				json.NewEncoder(w).Encode(Envelope{1, "bad", "bad"})
			}))
			defer server.Close()
			node, _ = OpenSync(newClient(server.URL), t.TempDir())
			node.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
			node.state.Recovery = testRecovery
			err := node.SyncPluginPreferences(context.Background(), func() (pluginprefs.Document, error) { return pluginprefs.Empty(), nil }, func(doc pluginprefs.Document) (pluginprefs.Document, error) { commits++; return doc, nil })
			if err == nil || commits != 0 || writes != 0 {
				t.Fatalf("invalid response changed local/remote: %v %d %d", err, commits, writes)
			}
		})
	}
}
