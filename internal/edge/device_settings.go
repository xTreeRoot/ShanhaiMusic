package edge

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
)

const deviceSettings = "device-settings"

type SettingsDevice struct {
	Key      string `json:"key"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
	Current  bool   `json:"current"`
	Updated  int64  `json:"updated_at"`
}

// DeviceSettings contains portable preferences and display names only.
type DeviceSettings struct {
	Version  int                          `json:"version"`
	App      map[string]string            `json:"app"`
	Plugins  map[string]map[string]string `json:"plugins"`
	Accounts map[string]string            `json:"accounts"`
}

func (s *Sync) settingsOperation() (operation, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	op := s.snapshot("")
	if !op.usable() {
		return op, &StatusError{Code: http.StatusUnauthorized}
	}
	return op, nil
}

func (s *Sync) SettingsDevices(ctx context.Context) ([]SettingsDevice, error) {
	op, err := s.settingsOperation()
	if err != nil {
		return nil, err
	}
	raw, _, err := s.client.request(ctx, http.MethodGet, "/v1/sync/devices", op.grant.Token, "", nil, envelopeLimit, 200)
	if err != nil {
		return nil, err
	}
	var result struct{ Devices []SettingsDevice }
	if json.Unmarshal(raw, &result) != nil || result.Devices == nil {
		return nil, errors.New("invalid device list")
	}
	return result.Devices, nil
}

func (s *Sync) SaveDeviceSettings(ctx context.Context, doc DeviceSettings) error {
	op, err := s.settingsOperation()
	if err != nil {
		return err
	}
	key := hex.EncodeToString([]byte(op.grant.DeviceID))
	_, tag, err := s.client.getData(ctx, op.grant, deviceSettings, key)
	if dataStatus(err, 404, "not_found") {
		tag = "*"
	} else if err != nil {
		return err
	}
	env, err := sealData(op.recovery, op.grant.SpaceID, deviceSettings, key, 1, doc)
	if err != nil {
		return err
	}
	return s.client.changeData(ctx, op.grant, deviceSettings, key, tag, &env)
}

func (s *Sync) ReadDeviceSettings(ctx context.Context, key string) (DeviceSettings, error) {
	var doc DeviceSettings
	op, err := s.settingsOperation()
	if err != nil {
		return doc, err
	}
	env, _, err := s.client.getData(ctx, op.grant, deviceSettings, key)
	if err != nil {
		return doc, err
	}
	if env.Format != 1 || openData(op.recovery, op.grant.SpaceID, deviceSettings, key, env, &doc) != nil || doc.Version != 1 || doc.App == nil || doc.Plugins == nil || doc.Accounts == nil {
		return doc, errors.New("invalid device settings")
	}
	return doc, nil
}
