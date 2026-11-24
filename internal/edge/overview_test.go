package edge

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestOverviewDistinguishesStoredMissingAndUnavailable(t *testing.T) {
	const platform = "example"
	tag := `"` + strings.Repeat("A", 22) + `"`
	active := accountRef(testRecovery, "test-space", platform, "active")
	deleted := accountRef(testRecovery, "test-space", platform, "deleted")
	client := useServer(t, func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet || request.Header.Get("Authorization") != "Bearer "+testRecovery {
			t.Errorf("unexpected request: %s %s", request.Method, request.URL.Path)
		}
		response.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case "/v1/sync/overview":
			response.WriteHeader(http.StatusNotFound)
		case "/v1/sessions/example":
			json.NewEncoder(response).Encode(map[string][]SessionInfo{"sessions": {
				{Ref: active, ETag: tag, Format: 1}, {Ref: deleted, ETag: tag, Deleted: true},
			}})
		case "/v1/data/example/profile":
			response.WriteHeader(http.StatusNotFound)
			response.Write([]byte(`{"error":"not_found"}`))
		case "/v1/data/app/plugins":
			response.Header().Set("ETag", tag)
			json.NewEncoder(response).Encode(Envelope{Format: 1})
		case "/v1/data/app/discovery":
			response.WriteHeader(http.StatusNotFound)
			response.Write([]byte(`{"error":"unsupported"}`))
		default:
			t.Errorf("unexpected URL: %s", request.URL.Path)
			response.WriteHeader(http.StatusBadRequest)
		}
	})
	service, err := OpenSync(client, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	service.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	service.state.Recovery = testRecovery
	service.state.Profiles = map[string]Profile{platform: {AccountID: "local", Name: "Local Fixture"}}
	if err := service.Block(platform); err != nil {
		t.Fatal(err)
	}
	overview, err := service.Inspect(context.Background(), platform, "deleted")
	if err != nil || overview.SessionsError != nil || overview.Sessions != 1 || !overview.AccountDeleted || !overview.RestoreBlocked {
		t.Fatalf("incorrect session state: %+v (%v)", overview, err)
	}
	if overview.Profile != RecordMissing || overview.PluginPreferences != RecordStored || overview.Discovery != RecordUnavailable {
		t.Fatalf("incorrect data state: %+v", overview)
	}
}

func TestOverviewUsesOneInventoryRequestWhenNoSourceIsSelected(t *testing.T) {
	client := useServer(t, func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/sync/overview" || request.Header.Get("Authorization") != "Bearer "+testRecovery {
			t.Errorf("unexpected request: %s", request.URL.Path)
		}
		response.Write([]byte(`{"records":[{"kind":"data","platform":"app","key":"plugins","count":1},{"kind":"sessions","platform":"example","count":2}]}`))
	})
	service, err := OpenSync(client, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	service.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	service.state.Recovery = testRecovery
	overview, err := service.Inspect(t.Context(), "", "")
	if err != nil || overview.InventoryError != nil || len(overview.Records) != 2 ||
		overview.Records[1].Count != 2 || overview.PluginPreferences != RecordStored || overview.Discovery != RecordMissing {
		t.Fatalf("incorrect inventory: %+v (%v)", overview, err)
	}
}
