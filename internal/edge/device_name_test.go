package edge

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestRenameDeviceUsesCurrentAuthorizationAndDoesNotReplaceGrant(t *testing.T) {
	calls := 0
	client := useServer(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method != http.MethodPatch || r.URL.Path != "/v1/sync/device" || r.Header.Get("Authorization") != "Bearer "+testRecovery {
			t.Error("incorrect rename request")
		}
		var body map[string]string
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body) != 1 || body["name"] != "书房 Mac" {
			t.Error("incorrect rename body")
		}
		w.WriteHeader(http.StatusNoContent)
	})
	service, err := OpenSync(client, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	service.state.Grant = Grant{DeviceID: "current", SpaceID: "space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	service.state.Recovery = testRecovery
	before := service.state.Grant
	if err := service.RenameDevice(t.Context(), "书房 Mac"); err != nil {
		t.Fatal(err)
	}
	if calls != 1 || service.state.Grant != before {
		t.Fatal("rename changed authorization")
	}
}
