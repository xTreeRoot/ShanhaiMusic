package edge

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"mygo-music/internal/music"
)

const testRecovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
const testRef = "rQgGydt1-xJIAjpM4GXEO4Q0GyBkb4Sro0a5saijxaY"

func TestProtocolVector(t *testing.T) {
	space, plugin := "test-space", "example"
	envelope := Envelope{1, "AAECAwQFBgcICQoL", "4pKzDTCYmD-RlfPCsaqP3vVTdyYeYH7RZNXWMsAplG6Y_w3pJB2uUQ-IuPszwsAhbCxTVArDavI3up94c68j9YUzB1KJ1Tnm"}
	if got := encoding.EncodeToString(derive(testRecovery, space, "session")); got != "pm2o2eFnLNImVtdISrzDqGKuRpo_NKJqy9KmR7A-X9I" {
		t.Fatal("wrong session key")
	}
	value, err := openSession(testRecovery, space, plugin, testRef, envelope)
	if err != nil || value.AccountID != "user-123" || string(value.Data) != `{"demo":"synthetic"}` {
		t.Fatalf("vector failed: %v", err)
	}
	if _, err := openSession(testRecovery, space, "wrong-plugin", testRef, envelope); err == nil {
		t.Fatal("accepted wrong AAD")
	}
	envelope.Format = 2
	if _, err := openSession(testRecovery, space, plugin, testRef, envelope); err == nil {
		t.Fatal("accepted modified format")
	}
	first, ref, err := seal(testRecovery, space, plugin, 1, value)
	second, _, secondErr := seal(testRecovery, space, plugin, 1, value)
	if err != nil || secondErr != nil || ref != testRef || first.Nonce == second.Nonce {
		t.Fatalf("seal failure: %v %v", err, secondErr)
	}
}

func useServer(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	client := newClient("https://node.example.test")
	client.http.Transport = testTransport(func(request *http.Request) (*http.Response, error) {
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		return recorder.Result(), nil
	})
	return client
}

type testTransport func(*http.Request) (*http.Response, error)

func (transport testTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	return transport(request)
}

func TestJoinPasswordTLSNodeIsolationAndKeyRotation(t *testing.T) {
	const password = " \t测试\n "
	var pin atomic.Value
	pin.Store(testRecovery)
	otherKey := encoding.EncodeToString(bytes.Repeat([]byte{1}, 32))
	newServer := func(token string) *httptest.Server {
		return httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
			if request.Method != "POST" || request.URL.Path != "/v1/join" || request.Header.Get("Authorization") != "" {
				t.Error("invalid node join request")
			}
			var body map[string]string
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil || body["password"] != password || body["platform"] != "desktop" || body["name"] == "" {
				t.Error("join did not send the original password and host device fields")
			}
			writer.WriteHeader(201)
			json.NewEncoder(writer).Encode(nodeGrant{Grant{encoding.EncodeToString(make([]byte, 16)), "node", token, time.Now().Add(time.Hour).Unix()}, testRecovery, pin.Load().(string)})
		}))
	}
	first, second := newServer(testRecovery), newServer(otherKey)
	defer first.Close()
	defer second.Close()
	root := t.TempDir()
	nodes := NewNodes(root)
	nodes.makeClient = func(address string) *Client {
		client := newClient(address)
		client.http.Transport = first.Client().Transport
		return client
	}
	ctx := context.Background()
	node, err := nodes.Join(ctx, nil, strings.TrimPrefix(first.URL, "https://")+"/", password, "")
	if err != nil || node.Address != first.URL || node.Catalog() == nil {
		t.Fatalf("first TLS join: %v", err)
	}
	secondNode, err := nodes.Join(ctx, node, second.URL, password, "")
	if err != nil || secondNode.dir == node.dir || secondNode.Sync.state.Grant.Token != otherKey || node.Sync.state.Grant.Token != testRecovery {
		t.Fatalf("node credentials were not isolated: %v", err)
	}
	pin.Store(otherKey)
	if _, err := nodes.Join(ctx, secondNode, second.URL, password, ""); err == nil || secondNode.Catalog() != nil {
		t.Fatal("changed key was silently accepted")
	}
	resumed, err := NewNodes(root).Resume()
	if err != nil || resumed.Address != second.URL || resumed.Catalog() != nil {
		t.Fatalf("changed key restriction did not survive restart: %v", err)
	}
	if _, err := nodes.Join(ctx, secondNode, second.URL, password, otherKey); err != nil || secondNode.Catalog() == nil {
		t.Fatalf("approved key could not reconnect: %v", err)
	}
	for _, bad := range []string{"http://node.test", "https://user:pass@node.test", "node.test/path", "node.test?key=1", "node.test#fragment"} {
		if _, err := NormalizeAddress(bad); err == nil {
			t.Errorf("accepted invalid address %s", bad)
		}
	}
	if address, _ := NormalizeAddress("HTTPS://NODE.TEST.:443/"); address != "https://node.test" {
		t.Fatalf("address aliases were not canonicalized: %s", address)
	}
}

func TestSignedCatalogRollbackAndBadPackage(t *testing.T) {
	public, private, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	pack := []byte("good package")
	digest := sha256.Sum256(pack)
	entry := Package{ID: "example", Version: "1.2.3", MinHost: "0.1.25", Permissions: json.RawMessage(`{"network":[]}`), SHA256: hex.EncodeToString(digest[:]), Size: int64(len(pack)), Path: "/v1/packages/" + hex.EncodeToString(digest[:])}
	payload := func(revision int, padding string) []byte {
		t.Helper()
		encoded, _ := json.Marshal(entry)
		return fmt.Appendf(nil, `{"protocol":1,%s"revision":%d,"plugins":[%s]}`, padding, revision, encoded)
	}
	signed := func(payload []byte, signature []byte) []byte {
		encoded, _ := json.Marshal(map[string]string{"payload": encoding.EncodeToString(payload), "signature": encoding.EncodeToString(signature)})
		return encoded
	}
	data := payload(2, " ")
	response := signed(data, ed25519.Sign(private, data))
	client := useServer(t, func(writer http.ResponseWriter, req *http.Request) {
		switch req.URL.Path {
		case "/v1/catalog":
			writer.Write(response)
		case entry.Path:
			writer.Write(pack)
		default:
			http.NotFound(writer, req)
		}
	})
	dir := t.TempDir()
	catalog := newCatalog(client, dir, encoding.EncodeToString(public))
	ctx := context.Background()
	values, err := catalog.Fetch(ctx)
	if err != nil || len(values) != 1 {
		t.Fatalf("fetch signed payload: %v", err)
	}
	if got, err := catalog.Download(ctx, values[0]); err != nil || !bytes.Equal(got, pack) {
		t.Fatalf("download signed bytes: %v", err)
	}
	forged := entry
	forged.Version = "1.2.4"
	if _, err := catalog.Download(ctx, forged); err == nil {
		t.Fatal("downloaded an entry not approved by the latest verified index")
	}
	pack = []byte("bad! package")
	if _, err := catalog.Download(ctx, values[0]); err == nil {
		t.Fatal("accepted wrong package hash")
	}
	reopened := newCatalog(client, dir, encoding.EncodeToString(public))
	for _, next := range [][]byte{payload(1, " "), payload(2, "  ")} {
		response = signed(next, ed25519.Sign(private, next))
		if _, err := reopened.Fetch(ctx); err == nil {
			t.Fatal("accepted a rollback or different bytes at the same revision")
		}
	}
	response = signed(payload(3, " "), ed25519.Sign(private, data))
	if _, err := reopened.Fetch(ctx); err == nil {
		t.Fatal("accepted tampered payload")
	}
}

func TestDeviceAuthorizationIsNotForwarded(t *testing.T) {
	destination := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("redirect was followed") }))
	defer destination.Close()
	client := useServer(t, func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer device-token" {
			t.Error("initial protocol request is missing the device token")
		}
		http.Redirect(writer, request, destination.URL, http.StatusTemporaryRedirect)
	})
	_, err := client.list(context.Background(), Grant{Token: "device-token"}, "example")
	if status, ok := errors.AsType[*StatusError](err); !ok || status.Code != http.StatusTemporaryRedirect {
		t.Fatalf("expected redirect failure, got %v", err)
	}
}

type fakeMigration struct {
	session Session
	stage   func()
	commits int
}

func (fake *fakeMigration) ExportSession() (Session, int, error) { return fake.session, 1, nil }
func (fake *fakeMigration) StageSession(_ int, session Session) (func() (*music.Account, error), error) {
	if fake.stage != nil {
		fake.stage()
	}
	return func() (*music.Account, error) {
		fake.commits++
		return &music.Account{ID: session.AccountID}, nil
	}, nil
}

func TestPendingReopenConflictAndBlockedRestore(t *testing.T) {
	const plugin = "example"
	tag := `"` + encoding.EncodeToString(make([]byte, 16)) + `"`
	otherTag := `"` + encoding.EncodeToString(bytes.Repeat([]byte{1}, 16)) + `"`
	grant := Grant{encoding.EncodeToString(make([]byte, 16)), "test-space", encoding.EncodeToString(make([]byte, 32)), time.Now().Add(time.Hour).Unix()}
	migrator := &fakeMigration{session: Session{AccountID: "user-123", Label: "example user", Data: json.RawMessage(`{"cookie":"synthetic-only"}`)}}
	var remote Envelope
	var puts, lists int
	var conflict bool
	client := useServer(t, func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer "+grant.Token {
			t.Error("bad auth")
		}
		if request.URL.Path == "/v1/sessions/example" {
			lists++
			json.NewEncoder(writer).Encode(map[string]any{"sessions": []SessionInfo{{testRef, otherTag, 1, false}}})
			return
		}
		if request.URL.Path != "/v1/sessions/example/"+testRef {
			t.Error("bad session path")
		}
		if request.Method == "PUT" {
			puts++
			if conflict {
				if request.Header.Get("If-Match") != otherTag {
					t.Error("did not preserve observed revision")
				}
				writer.WriteHeader(412)
				return
			}
			if request.Header.Get("If-None-Match") != "*" {
				t.Error("first upload did not use If-None-Match")
			}
			json.NewDecoder(request.Body).Decode(&remote)
			writer.WriteHeader(500)
			return
		}
		writer.Header().Set("ETag", otherTag)
		json.NewEncoder(writer).Encode(remote)
	})
	dir := t.TempDir()
	service, err := OpenSync(client, dir)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if err := service.setGrant(nodeGrant{grant, testRecovery, testRecovery}, ""); err != nil {
		t.Fatal(err)
	}
	if err := service.Publish(ctx, plugin, "user-123", service.Ticket(plugin), migrator); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(filepath.Join(dir, "sync.json")); err != nil || strings.Contains(string(data), "synthetic-only") {
		t.Fatalf("plaintext escaped into the queue: %v", err)
	}
	service, err = OpenSync(client, dir)
	if err != nil || service.Flush(ctx) != nil || puts != 1 || service.state.Versions[plugin+"/"+testRef] != otherTag {
		t.Fatalf("reopening or reconciling lost response failed: %v", err)
	}
	conflict = true
	if err := service.Publish(ctx, plugin, "user-123", service.Ticket(plugin), migrator); err != nil {
		t.Fatal(err)
	}
	if err := service.Flush(ctx); err == nil {
		t.Fatal("conflict went unreported")
	}
	if err := service.Flush(ctx); err != nil || puts != 2 || service.state.Versions[plugin+"/"+testRef] == tag {
		t.Fatal("conflicting write was retried")
	}
	migrator.stage = func() { service.Invalidate(plugin) }
	if _, _, err := service.Restore(ctx, plugin, service.Ticket(plugin), migrator, ""); !errors.Is(err, context.Canceled) || migrator.commits != 0 {
		t.Fatal("late staged result committed")
	}
	if err := service.Block(plugin); err != nil {
		t.Fatal(err)
	}
	service, err = OpenSync(client, dir)
	if err != nil {
		t.Fatal(err)
	}
	if account, _, err := service.Restore(ctx, plugin, service.Ticket(plugin), migrator, ""); err != nil || account != nil || lists != 1 {
		t.Fatal("signed-out account fetched or restored after relaunch")
	}
	stale := service.Ticket(plugin)
	if err := service.Unbind(); err != nil {
		t.Fatal(err)
	}
	if err := service.Publish(ctx, plugin, "user-123", stale, migrator); !errors.Is(err, context.Canceled) {
		t.Fatalf("stale publish after unbind: %v", err)
	}
	if space, _ := service.Binding(); space != "" || service.state.Recovery != "" || !service.state.Blocked[plugin] || len(service.state.Pending) != 0 {
		t.Fatal("unbind leaked secrets or removed the local sign-out marker")
	}
}

func TestFreshLoginReplacesAnObservedSessionAndRestoresOnAnotherDevice(t *testing.T) {
	const plugin = "example"
	currentTag := `"` + encoding.EncodeToString(bytes.Repeat([]byte{1}, 16)) + `"`
	nextTag := `"` + encoding.EncodeToString(bytes.Repeat([]byte{2}, 16)) + `"`
	newSession := Session{AccountID: "user-123", Data: json.RawMessage(`{"demo":"renewed"}`)}
	desktopToken := testRecovery
	phoneToken := encoding.EncodeToString(bytes.Repeat([]byte{3}, 32))
	var remote Envelope
	putCount, deleted := 0, false
	client := useServer(t, func(writer http.ResponseWriter, request *http.Request) {
		identity := request.Header.Get("Authorization")
		switch {
		case request.URL.Path == "/v1/sessions/example":
			entries := []SessionInfo{{Ref: testRef, ETag: currentTag, Format: 1}}
			if deleted {
				entries[0].Deleted, entries[0].Format = true, 0
			}
			if identity != "Bearer "+desktopToken && identity != "Bearer "+phoneToken {
				t.Fatal("listing used an unexpected device token")
			}
			json.NewEncoder(writer).Encode(map[string][]SessionInfo{"sessions": entries})
		case request.URL.Path == "/v1/sessions/example/"+testRef && request.Method == http.MethodPut:
			putCount++
			if identity != "Bearer "+desktopToken || request.Header.Get("If-Match") != currentTag {
				t.Fatal("publish did not use desktop grant and observed revision")
			}
			if err := json.NewDecoder(request.Body).Decode(&remote); err != nil {
				t.Fatal(err)
			}
			writer.Header().Set("ETag", nextTag)
			writer.WriteHeader(http.StatusNoContent)
			currentTag = nextTag
		case request.URL.Path == "/v1/sessions/example/"+testRef && request.Method == http.MethodGet:
			if identity != "Bearer "+phoneToken {
				t.Fatal("restore used an unexpected device token")
			}
			writer.Header().Set("ETag", currentTag)
			json.NewEncoder(writer).Encode(remote)
		default:
			t.Fatalf("unexpected route %s %s", request.Method, request.URL.Path)
		}
	})
	device := func(token string) *Sync {
		service, err := OpenSync(client, t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		grant := Grant{DeviceID: encoding.EncodeToString(make([]byte, 16)), Token: token, SpaceID: "test-space", ExpiresAt: time.Now().Add(time.Hour).Unix()}
		if err := service.setGrant(nodeGrant{grant, testRecovery, testRecovery}, ""); err != nil {
			t.Fatal(err)
		}
		return service
	}
	desktop, phone := device(desktopToken), device(phoneToken)
	upload := &fakeMigration{session: newSession}
	if err := desktop.PublishCurrent(t.Context(), plugin, newSession.AccountID, desktop.Ticket(plugin), upload); err != nil {
		t.Fatal(err)
	}
	if err := desktop.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	restore := &fakeMigration{}
	account, _, err := phone.Restore(t.Context(), plugin, phone.Ticket(plugin), restore, "")
	if err != nil || account == nil || account.ID != newSession.AccountID || restore.commits != 1 {
		t.Fatalf("second device did not restore: %v", err)
	}
	deleted = true
	if err := desktop.PublishCurrent(t.Context(), plugin, newSession.AccountID, desktop.Ticket(plugin), upload); !dataStatus(err, http.StatusGone, "") || putCount != 1 {
		t.Fatalf("fresh login unexpectedly replaced a tombstone: %v", err)
	}
}
