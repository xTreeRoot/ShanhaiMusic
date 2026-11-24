package edge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
)

const (
	pluginPreferencesKey    = "plugins"
	discoveryPreferencesKey = "discovery"
)

type RecordStatus uint8

const (
	RecordUnchecked RecordStatus = iota
	RecordStored
	RecordMissing
	RecordUnavailable
)

type Overview struct {
	Sessions          int
	SessionsError     error
	RestoreBlocked    bool
	AccountDeleted    bool
	Profile           RecordStatus
	PluginPreferences RecordStatus
	Discovery         RecordStatus
	Records           []CloudRecord
	InventoryError    error
}

type CloudRecord struct {
	Kind     string `json:"kind"`
	Platform string `json:"platform"`
	Key      string `json:"key"`
	Count    int    `json:"count"`
}

func (client *Client) inventory(ctx context.Context, grant Grant) ([]CloudRecord, error) {
	data, _, err := client.request(ctx, http.MethodGet, "/v1/sync/overview", grant.Token, "", nil, envelopeLimit, http.StatusOK)
	if err != nil {
		return nil, err
	}
	var reply struct{ Records []CloudRecord }
	invalid := errors.New("invalid MB cloud inventory")
	if json.Unmarshal(data, &reply) != nil || reply.Records == nil {
		return nil, invalid
	}
	seen := make(map[[3]string]bool)
	for _, record := range reply.Records {
		address := [3]string{record.Kind, record.Platform, record.Key}
		if seen[address] || !identifier.MatchString(record.Platform) ||
			!(record.Kind == "sessions" && record.Key == "" && record.Count > 0 ||
				record.Kind == "data" && identifier.MatchString(record.Key) && record.Count == 1) {
			return nil, invalid
		}
		seen[address] = true
	}
	return reply.Records, nil
}

func (service *Sync) Authorized() bool {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.snapshot("").usable()
}

func (service *Sync) Inspect(ctx context.Context, platform, account string) (Overview, error) {
	service.mu.Lock()
	op := service.snapshot(platform)
	overview := Overview{RestoreBlocked: service.state.Blocked[platform]}
	service.mu.Unlock()
	if !op.usable() {
		return Overview{}, &StatusError{Code: http.StatusUnauthorized}
	}
	overview.Records, overview.InventoryError = service.client.inventory(ctx, op.grant)
	status := func(namespace, key string) RecordStatus {
		if overview.InventoryError == nil {
			for _, record := range overview.Records {
				if record.Kind == "data" && record.Platform == namespace && record.Key == key {
					return RecordStored
				}
			}
			return RecordMissing
		}
		_, _, err := service.client.getData(ctx, op.grant, namespace, key)
		switch {
		case err == nil:
			return RecordStored
		case dataStatus(err, http.StatusNotFound, "not_found"), dataStatus(err, http.StatusGone, ""):
			return RecordMissing
		default:
			return RecordUnavailable
		}
	}
	if platform != "" {
		ref := ""
		if account != "" {
			ref = accountRef(op.recovery, op.grant.SpaceID, platform, account)
		}
		entries, err := service.client.list(ctx, op.grant, platform)
		overview.SessionsError = err
		for _, entry := range entries {
			if !entry.Deleted {
				overview.Sessions++
			} else if entry.Ref == ref {
				overview.AccountDeleted = true
			}
		}
		overview.Profile = status(platform, profileKey)
	}
	overview.PluginPreferences = status("app", pluginPreferencesKey)
	overview.Discovery = status("app", discoveryPreferencesKey)
	service.mu.Lock()
	defer service.mu.Unlock()
	if !service.current(ctx, platform, op) {
		if err := ctx.Err(); err != nil {
			return Overview{}, err
		}
		return Overview{}, context.Canceled
	}
	return overview, nil
}
