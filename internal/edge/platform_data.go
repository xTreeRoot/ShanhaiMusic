package edge

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"
)

const profileKey = "profile"

type Profile struct {
	AccountID string `json:"account_id"`
	Name      string `json:"display_name"`
}

func (profile Profile) valid() bool {
	return len(profile.AccountID) > 0 && len(profile.AccountID) <= 1024 && len(profile.Name) > 0 && len(profile.Name) <= 512
}

func dataCipher(recovery, space, platform, key string, format int) (cipher.AEAD, []byte, error) {
	if !validBase64(recovery, 32) || !identifier.MatchString(space) || !identifier.MatchString(platform) || !identifier.MatchString(key) || format < 1 || format > 65535 {
		return nil, nil, errors.New("invalid MB platform data context")
	}
	block, err := aes.NewCipher(derive(recovery, space, "platform-data"))
	if err != nil {
		return nil, nil, err
	}
	gcm, err := cipher.NewGCM(block)
	return gcm, fmt.Appendf(nil, "mb-data-v1\n%s\n%s\n%s\n%d", space, platform, key, format), err
}

func sealData(recovery, space, platform, key string, format int, value any) (Envelope, error) {
	gcm, aad, err := dataCipher(recovery, space, platform, key, format)
	if err != nil {
		return Envelope{}, err
	}
	plain, err := json.Marshal(struct {
		Value any `json:"value"`
	}{value})
	if err != nil {
		return Envelope{}, err
	}
	nonce := randBytes(gcm.NonceSize())
	return Envelope{Format: format, Nonce: encoding.EncodeToString(nonce), Ciphertext: encoding.EncodeToString(gcm.Seal(nil, nonce, plain, aad))}, nil
}

func openData(recovery, space, platform, key string, envelope Envelope, value any) error {
	gcm, aad, err := dataCipher(recovery, space, platform, key, envelope.Format)
	if err != nil {
		return err
	}
	if !validBase64(envelope.Nonce, gcm.NonceSize()) || len(envelope.Ciphertext) > envelopeLimit {
		return errors.New("invalid MB platform data envelope")
	}
	nonce, _ := encoding.DecodeString(envelope.Nonce)
	sealed, err := encoding.DecodeString(envelope.Ciphertext)
	if err != nil || encoding.EncodeToString(sealed) != envelope.Ciphertext {
		return errors.New("invalid MB platform ciphertext")
	}
	plain, err := gcm.Open(nil, nonce, sealed, aad)
	if err != nil {
		return errors.New("cannot authenticate MB platform data")
	}
	var target struct {
		Value json.RawMessage `json:"value"`
	}
	if json.Unmarshal(plain, &target) != nil || len(target.Value) == 0 || string(target.Value) == "null" {
		return errors.New("invalid MB platform data payload")
	}
	return json.Unmarshal(target.Value, value)
}

func dataPath(platform, key string) (string, error) {
	if !identifier.MatchString(platform) || !identifier.MatchString(key) {
		return "", errors.New("invalid MB platform data key")
	}
	return "/v1/data/" + platform + "/" + key, nil
}

func (client *Client) getData(ctx context.Context, grant Grant, platform, key string) (Envelope, string, error) {
	path, err := dataPath(platform, key)
	if err != nil {
		return Envelope{}, "", err
	}
	data, header, err := client.request(ctx, http.MethodGet, path, grant.Token, "", nil, envelopeLimit, http.StatusOK)
	tag := header.Get("ETag")
	if err != nil {
		return Envelope{}, tag, err
	}
	var envelope Envelope
	if !validTag(tag) || json.Unmarshal(data, &envelope) != nil {
		return Envelope{}, "", errors.New("invalid MB platform data response")
	}
	return envelope, tag, nil
}

func (client *Client) changeData(ctx context.Context, grant Grant, platform, key, tag string, envelope *Envelope) error {
	path, err := dataPath(platform, key)
	if err != nil {
		return err
	}
	if !validTag(tag) && (envelope == nil || tag != "*") {
		return errors.New("invalid MB platform data precondition")
	}
	method, status := http.MethodDelete, http.StatusNoContent
	var body []byte
	if envelope != nil {
		method = http.MethodPut
		if tag == "*" {
			status = http.StatusCreated
		}
		body, err = json.Marshal(envelope)
		if err != nil || len(body) > envelopeLimit {
			return errors.New("MB platform data exceeds size limit")
		}
	}
	_, header, err := client.request(ctx, method, path, grant.Token, tag, body, 0, status)
	if err == nil && !validTag(header.Get("ETag")) {
		return errors.New("missing MB platform data revision")
	}
	return err
}

func dataStatus(err error, code int, kind string) bool {
	status, ok := errors.AsType[*StatusError](err)
	return ok && status.Code == code && (kind == "" || status.Kind == kind)
}

func retryData(err error) bool {
	if status, ok := errors.AsType[*StatusError](err); ok {
		return status.Code >= 500 && status.Code <= 599
	}
	return errors.Is(err, errNodeUnavailable)
}

func (service *Sync) SyncProfile(ctx context.Context, platform string, ticket Ticket, local Profile, publish bool) error {
	if !identifier.MatchString(platform) || !local.valid() {
		if publish {
			return errors.New("This account has no valid profile to sync.")
		}
		return nil
	}
	service.mu.Lock()
	op := service.snapshot(platform)
	service.mu.Unlock()
	if !op.usable() || ticket != (Ticket{op.serial, op.epoch}) {
		return nil
	}
	current := func() bool {
		service.mu.Lock()
		defer service.mu.Unlock()
		return service.current(ctx, platform, op)
	}
	get := func() (Envelope, string, error) {
		for attempt := 0; ; attempt++ {
			if !current() {
				return Envelope{}, "", context.Canceled
			}
			envelope, tag, err := service.client.getData(ctx, op.grant, platform, profileKey)
			if attempt == 2 || !retryData(err) {
				return envelope, tag, err
			}
			select {
			case <-ctx.Done():
				return Envelope{}, "", ctx.Err()
			case <-time.After(time.Duration(attempt+1) * 250 * time.Millisecond):
			}
		}
	}
	remote, tag, err := get()
	profile := local
	if err == nil {
		if remote.Format != 1 || openData(op.recovery, op.grant.SpaceID, platform, profileKey, remote, &profile) != nil || !profile.valid() {
			return errors.New("invalid MB cloud profile")
		}
		if profile.AccountID != local.AccountID {
			return errors.New("MB cloud profile belongs to another account")
		}
	} else if dataStatus(err, http.StatusNotFound, "not_found") {
		tag = "*"
	} else {
		return err
	}
	if tag == "*" || publish && profile != local {
		sealed, err := sealData(op.recovery, op.grant.SpaceID, platform, profileKey, 1, local)
		if err != nil {
			return err
		}
		if !current() {
			return context.Canceled
		}
		if err := service.client.changeData(ctx, op.grant, platform, profileKey, tag, &sealed); err != nil {
			if !retryData(err) && !dataStatus(err, http.StatusPreconditionFailed, "") {
				return err
			}
			observed, _, observedErr := get()
			if observedErr != nil || observed != sealed {
				return err
			}
		}
		profile = local
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	if !service.current(ctx, platform, op) {
		return context.Canceled
	}
	if service.state.Profiles[platform] != profile {
		next := service.state
		next.Profiles = cloned(next.Profiles)
		next.Profiles[platform] = profile
		return service.saveLocked(next)
	}
	return nil
}
