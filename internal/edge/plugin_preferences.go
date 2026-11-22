package edge

import (
	"context"
	"errors"
	"net/http"

	"mygo-music/internal/discoveryprefs"
	"mygo-music/internal/pluginprefs"
)

// SyncPluginPreferences pulls before publishing and commits merged preferences locally first.
// Callers serialize load/commit with local edits. commit must merge its argument with
// the latest local document and durably save it before returning that merged document.
// The callbacks must not call Sync methods: commit runs under the binding guard.
func (service *Sync) SyncPluginPreferences(ctx context.Context, load func() (pluginprefs.Document, error), commit func(pluginprefs.Document) (pluginprefs.Document, error)) error {
	return syncPreferences(service, ctx, "plugins", pluginprefs.Empty, load, commit)
}

// SyncDiscoveryPreferences uses its own encrypted record and the same guarded CAS cycle.
func (service *Sync) SyncDiscoveryPreferences(ctx context.Context, load func() (discoveryprefs.Document, error), commit func(discoveryprefs.Document) (discoveryprefs.Document, error)) error {
	return syncPreferences(service, ctx, "discovery", discoveryprefs.Empty, load, commit)
}

type preferencesDocument[T any] interface {
	Validate() error
	Equal(T) bool
}

func syncPreferences[T preferencesDocument[T]](service *Sync, ctx context.Context, key string, empty func() T, load func() (T, error), commit func(T) (T, error)) error {
	service.mu.Lock()
	op := service.snapshot("app")
	service.mu.Unlock()
	if !op.usable() {
		return nil
	}
	current := func() bool {
		service.mu.Lock()
		defer service.mu.Unlock()
		return service.current(ctx, "app", op)
	}
	for attempt := 0; attempt < 6; attempt++ {
		if !current() {
			return context.Canceled
		}
		remote := empty()
		envelope, tag, err := service.client.getData(ctx, op.grant, "app", key)
		if err == nil {
			if envelope.Format != 1 || openData(op.recovery, op.grant.SpaceID, "app", key, envelope, &remote) != nil || remote.Validate() != nil {
				return pluginprefs.ErrInvalid
			}
		} else if dataStatus(err, http.StatusNotFound, "not_found") {
			tag = "*"
		} else if retryData(err) {
			continue
		} else {
			return err
		}
		service.mu.Lock()
		if !service.current(ctx, "app", op) {
			service.mu.Unlock()
			return context.Canceled
		}
		merged, err := commit(remote)
		service.mu.Unlock()
		if err != nil {
			return err
		}
		if err := merged.Validate(); err != nil {
			return err
		}
		// Empty local state must not create a remote placeholder. Equality includes tombstones.
		if merged.Equal(remote) {
			latest, err := load()
			if err != nil {
				return err
			}
			if !current() {
				return context.Canceled
			}
			if latest.Equal(merged) {
				return nil
			}
			continue
		}
		sealed, err := sealData(op.recovery, op.grant.SpaceID, "app", key, 1, merged)
		if err != nil {
			return err
		}
		if !current() {
			return context.Canceled
		}
		err = service.client.changeData(ctx, op.grant, "app", key, tag, &sealed)
		if err != nil {
			if dataStatus(err, http.StatusPreconditionFailed, "") || retryData(err) {
				continue
			}
			return err
		}
		latest, err := load()
		if err != nil {
			return err
		}
		if !current() {
			return context.Canceled
		}
		if latest.Equal(merged) {
			return nil
		}
	}
	return errors.New("plugin preferences changed during sync; retry later")
}
