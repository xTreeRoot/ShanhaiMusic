package app

import (
	"context"
	"errors"
	"mygo-music/internal/devicebackup"
	"mygo-music/internal/edge"
	"mygo-music/internal/plugin"
	"os"
	"path/filepath"
)

func withDeviceArchive(ctx context.Context, service *edge.Sync, owner string, ref edge.DeviceBackupReference, apply func(string, devicebackup.Manifest) error) error {
	root, err := newDeviceBackupDirectory()
	if err != nil {
		return err
	}
	defer os.RemoveAll(root)
	archive := filepath.Join(root, "device.zip")
	file, err := os.OpenFile(archive, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	err = service.DownloadDeviceBackup(ctx, owner, ref, file)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	stage := filepath.Join(root, "content")
	manifest, err := devicebackup.Extract(ctx, archive, stage)
	if err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return apply(stage, manifest)
}

// restoreDesktopPluginInfo imports no library, app or presentation documents.
// Existing installed code remains at its current version.
func restoreDesktopPluginInfo(ctx context.Context, stage string, manifest devicebackup.Manifest, id string) error {
	if err := manifest.Validate(); err != nil {
		return err
	}
	var record *devicebackup.Plugin
	for i := range manifest.Plugins {
		if manifest.Plugins[i].ID == id {
			record = &manifest.Plugins[i]
			break
		}
	}
	if record == nil {
		return errors.New("plugin is absent from this backup")
	}
	candidate, err := plugin.Open(filepath.Join(stage, "plugins", id, "package.zip"), pluginsDir())
	if err != nil {
		return err
	}
	defer candidate.Discard()
	if candidate.Manifest.ID != id {
		return errors.New("backup plugin ID mismatch")
	}
	if record.Repository != "" {
		candidate.Repo, err = plugin.ParseRepo(record.Repository)
		if err != nil {
			return err
		}
		candidate.Version = record.Revision
	}
	if _, err := devicebackup.Tree(filepath.Join(stage, "plugins", id, "data"), "data"); err != nil {
		return err
	}
	fields := candidate.Manifest.Settings
	if _, statErr := os.Lstat(filepath.Join(pluginsDir(), id)); errors.Is(statErr, os.ErrNotExist) {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := candidate.Install(pluginsDir()); err != nil {
			return err
		}
	} else if statErr != nil {
		return statErr
	} else {
		current, err := plugin.Load(filepath.Join(pluginsDir(), id), userDataDir("plugin-data"))
		if err != nil {
			return err
		}
		fields = current.Info().Settings
		current.Close()
	}
	values := map[string]string{}
	for _, field := range fields {
		if value, ok := record.Settings[field.Key]; ok {
			values[field.Key] = value
		}
	}
	return plugin.ReplaceBackupData(ctx, filepath.Join(stage, "plugins", id, "data"), userDataDir("plugin-data/"+id), values)
}

func (a *app) loadBackupPluginChoices() {
	if a.edge.preview == nil || a.edge.preview.Backup == nil {
		return
	}
	ctx, owner, ref := a.edge.profileContext, a.edge.previewKey, *a.edge.preview.Backup
	var choices []devicebackup.Plugin
	a.runEdge("Reading backup plugins…", func(service *edge.Sync) error {
		return withDeviceArchive(ctx, service, owner, ref, func(_ string, manifest devicebackup.Manifest) error { choices = manifest.Plugins; return nil })
	}, func() { a.edge.backupPlugins = choices; a.edge.pluginsLoaded = true; a.edge.confirmPlugin = "" })
}

func (a *app) restoreBackupPlugin(id string) {
	if a.edge.preview == nil || a.edge.preview.Backup == nil {
		return
	}
	ctx, owner, ref := a.edge.profileContext, a.edge.previewKey, *a.edge.preview.Backup
	var existing *plugin.Plugin
	if lib := a.libOf(id); lib != nil {
		a.invalidateEdge(lib)
		existing = pluginOf(lib)
	}
	a.runEdge("Restoring plugin information…", func(service *edge.Sync) error {
		return withDeviceArchive(ctx, service, owner, ref, func(stage string, manifest devicebackup.Manifest) error {
			if existing != nil {
				existing.RetireForDataRestore()
			}
			err := restoreDesktopPluginInfo(ctx, stage, manifest, id)
			a.post(func() {
				if service == a.edge.service {
					if e := a.pluginInstalled(id); e != nil {
						a.edge.message = e.Error()
					}
				}
			})
			return err
		})
	}, func() { a.edge.confirmPlugin = ""; a.edge.message = T("Selected plugin information restored") })
}
