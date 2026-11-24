package plugin

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"

	"mygo-music/internal/edge"
	"mygo-music/internal/music"
)

func PrepareCatalogPackage(ctx context.Context, catalog *edge.Catalog, entry edge.Package, dir string) (*Pending, error) {
	data, err := catalog.Download(ctx, entry)
	if err != nil {
		return nil, err
	}
	return pending(dir, func(tmp string) (*Pending, error) {
		archive := filepath.Join(tmp, "package.zip")
		if err := os.WriteFile(archive, data, 0o600); err != nil {
			return nil, err
		}
		unpacked := filepath.Join(tmp, "unpacked")
		if err := unzip(archive, unpacked); err != nil {
			return nil, err
		}
		result, err := checked(unpacked, tmp, "MB Server", &Pending{Path: "MB Server", Release: Release{Version: entry.Version}, catalog: true})
		if err != nil {
			return nil, err
		}
		manifest := result.Manifest
		contents, err := os.ReadFile(filepath.Join(result.root, "plugin.json"))
		var raw struct{ Permissions json.RawMessage }
		var actual, expected map[string]any
		if err != nil || json.Unmarshal(contents, &raw) != nil || json.Unmarshal(raw.Permissions, &actual) != nil ||
			json.Unmarshal(entry.Permissions, &expected) != nil || !reflect.DeepEqual(actual, expected) ||
			manifest.ID != entry.ID || manifest.Version != entry.Version || manifest.MinAppVersion != entry.MinHost {
			return nil, errors.New("plugin manifest does not match MB catalog")
		}
		if err := os.Remove(filepath.Join(result.root, originFile)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		return result, nil
	})
}

func (p *Plugin) SessionMigrator() edge.Migrator {
	if !p.Can("exportSession") || !p.Can("validateSession") || !p.Can("commitSession") {
		return nil
	}
	return p
}

var ErrUntrustedSessionPlugin = errors.New("plugin does not match the node's signed catalog")

type verifiedSessionMigrator struct {
	plugin  *Plugin
	catalog *edge.Catalog
	ctx     context.Context
}

func (p *Plugin) VerifiedSessionMigrator(ctx context.Context, catalog *edge.Catalog) edge.Migrator {
	if catalog == nil || p.SessionMigrator() == nil {
		return nil
	}
	return verifiedSessionMigrator{p, catalog, ctx}
}

func (v verifiedSessionMigrator) verify() error {
	entries, err := v.catalog.Fetch(v.ctx)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.ID == v.plugin.manifest.ID && entry.Version == v.plugin.manifest.Version &&
			entry.ContentHash != "" && entry.ContentHash == v.plugin.contentHash {
			return nil
		}
	}
	return ErrUntrustedSessionPlugin
}

func (v verifiedSessionMigrator) ExportSession() (edge.Session, int, error) {
	if err := v.verify(); err != nil {
		return edge.Session{}, 0, err
	}
	return v.plugin.ExportSession()
}

func (v verifiedSessionMigrator) StageSession(format int, session edge.Session) (func() (*music.Account, error), error) {
	if err := v.verify(); err != nil {
		return nil, err
	}
	return v.plugin.StageSession(format, session)
}

type migration struct {
	Format int `json:"format"`
	edge.Session
}

func (p *Plugin) ExportSession() (edge.Session, int, error) {
	var result migration
	if p.SessionMigrator() == nil {
		return result.Session, 0, music.ErrUnsupported
	}
	err := p.call("exportSession", nil, &result)
	return result.Session, result.Format, err
}

func (p *Plugin) StageSession(format int, session edge.Session) (func() (*music.Account, error), error) {
	if p.SessionMigrator() == nil {
		return nil, music.ErrUnsupported
	}
	var account *music.Account
	input := migration{format, session}
	if err := p.call("validateSession", input, &account); err != nil {
		return nil, err
	}
	if account == nil || account.ID != session.AccountID {
		return nil, nil
	}
	return func() (*music.Account, error) {
		var result *music.Account
		err := p.call("commitSession", struct {
			migration
			Account *music.Account `json:"account"`
		}{input, account}, &result)
		return result, err
	}, nil
}
