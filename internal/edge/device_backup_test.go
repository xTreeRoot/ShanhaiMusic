package edge

import (
	"bytes"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDeviceBackupAuthenticatedChunksAndStaging(t *testing.T) {
	objects := map[string][]byte{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+testRecovery {
			t.Error("missing authorization")
		}
		switch r.Method {
		case "PUT":
			body, _ := io.ReadAll(r.Body)
			objects[r.URL.Path] = body
			w.WriteHeader(201)
		case "GET":
			body, ok := objects[r.URL.Path]
			if !ok {
				w.WriteHeader(404)
				return
			}
			w.Write(body)
		case "DELETE":
			delete(objects, r.URL.Path)
			w.WriteHeader(204)
		}
	}))
	defer server.Close()
	node, err := OpenSync(newClient(server.URL), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	const device = "AAECAwQFBgcICQoLDA0ODw"
	node.state.Grant = Grant{DeviceID: device, SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	node.state.Recovery = testRecovery
	plain := bytes.Repeat([]byte("portable-plugin-archive\x00"), 120000)
	ref, err := node.UploadDeviceBackup(t.Context(), bytes.NewReader(plain), "desktop", 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(objects) != 3 || ref.Plugins != 3 {
		t.Fatalf("chunked backup: %d objects %+v", len(objects), ref)
	}
	for _, body := range objects {
		if bytes.Contains(body, []byte("portable-plugin")) {
			t.Fatal("plaintext uploaded")
		}
	}
	owner := hex.EncodeToString([]byte(device))
	var target bytes.Buffer
	if err := node.DownloadDeviceBackup(t.Context(), owner, ref, &target); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(target.Bytes(), plain) {
		t.Fatal("archive changed")
	}
	wrong := ref
	wrong.SHA256 = strings.Repeat("0", 64)
	if err := node.DownloadDeviceBackup(t.Context(), owner, wrong, io.Discard); err == nil {
		t.Fatal("accepted mismatched archive metadata")
	}
	indexPath := "/v1/backups/" + owner + "/" + ref.Index
	valid := objects[indexPath]
	objects[indexPath] = bytes.Replace(valid, []byte(`"nonce":"`), []byte(`"nonce":"x`), 1)
	if err := node.DownloadDeviceBackup(t.Context(), owner, ref, io.Discard); err == nil {
		t.Fatal("accepted modified envelope")
	}
	objects[indexPath] = valid
	if err := node.DeleteDeviceBackup(t.Context(), ref); err != nil || len(objects) != 0 {
		t.Fatalf("cleanup err=%v objects=%d", err, len(objects))
	}
}
