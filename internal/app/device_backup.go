package app

import (
	"context"
	"encoding/json"
	"errors"
	"mygo-music/internal/player"
	"os"
	"path/filepath"
	"slices"

	"mygo-music/internal/devicebackup"
	"mygo-music/internal/discoveryprefs"
	"mygo-music/internal/edge"
	"mygo-music/internal/local"
	"mygo-music/internal/plugin"
	"mygo-music/internal/pluginprefs"
)

type desktopBackupPlugin struct {
	source *plugin.Plugin
	info   devicebackup.Plugin
}
type desktopBackupInput struct {
	session     *player.Session
	settings    settings
	plugins     []desktopBackupPlugin
	local       *local.Source
	preferences *pluginprefs.Store
	discovery   *discoveryprefs.Store
}

func (a *app) captureDeviceBackup() desktopBackupInput {
	input := desktopBackupInput{settings: a.settings, local: a.local, preferences: a.pluginPrefs, discovery: a.discoveryPrefs}
	if a.pl != nil {
		if session, ok := a.pl.Session(); ok {
			session.Queue = slices.Clone(session.Queue)
			session.Order = slices.Clone(session.Order)
			input.session = &session
		}
	}
	for _, lib := range a.allLibs {
		source := pluginOf(lib)
		if source == nil {
			continue
		}
		info := devicebackup.Plugin{ID: lib.id, Name: lib.localName, Enabled: false, Settings: map[string]string{}}
		for _, enabled := range a.libs {
			if enabled == lib {
				info.Enabled = true
			}
		}
		if origin := source.Origin(); origin != nil {
			info.Repository = origin.Repo
			info.Revision = origin.Version
		}
		input.plugins = append(input.plugins, desktopBackupPlugin{source: source, info: info})
	}
	return input
}

func buildDesktopBackup(ctx context.Context, input desktopBackupInput, root string) (string, error) {
	manifest := devicebackup.Manifest{Version: 1, Platform: "desktop", Plugins: []devicebackup.Plugin{}}
	files := []devicebackup.File{}
	if input.local != nil {
		var err error
		manifest.Library, files, err = input.local.DeviceBackup(ctx)
		if err != nil {
			return "", err
		}
	}
	if input.session != nil {
		session := input.session
		queue := &devicebackup.Queue{Index: session.At, Position: session.Position, Shuffled: session.Shuffle, Repeat: []string{"off", "all", "one"}[session.Repeat%3]}
		// Export the visible playback order so a different host can resume it.
		for _, index := range session.Order {
			track := session.Queue[index]
			queue.Tracks = append(queue.Tracks, devicebackup.Track{Source: track.Source, ID: track.ID,
				Title: track.Name, Artist: track.ArtistNames(), Artwork: track.Album.ImageURL, Duration: track.Duration, Page: track.URL})
		}
		manifest.Library.Queue = queue
	}
	manifest.Library.SearchHistory = input.settings.SearchHistory
	if input.preferences != nil {
		doc, err := input.preferences.Load()
		if err != nil {
			return "", err
		}
		manifest.PluginPreferences, err = json.Marshal(doc)
		if err != nil {
			return "", err
		}
	}
	if input.discovery != nil {
		doc, err := input.discovery.Load()
		if err != nil {
			return "", err
		}
		manifest.DiscoveryPreferences, err = json.Marshal(doc)
		if err != nil {
			return "", err
		}
	}
	for _, record := range input.plugins {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		info := record.info
		for _, field := range record.source.Info().Settings {
			info.Settings[field.Key] = record.source.Setting(field.Key)
		}
		file := filepath.Join(root, info.ID+".zip")
		if err := record.source.ArchiveForBackup(ctx, file); err != nil {
			return "", err
		}
		files = append(files, devicebackup.File{Name: "plugins/" + info.ID + "/package.zip", Source: file})
		data, err := devicebackup.Tree(userDataDir("plugin-data/"+info.ID), "plugins/"+info.ID+"/data")
		if err != nil {
			return "", err
		}
		files = append(files, data...)
		manifest.Plugins = append(manifest.Plugins, info)
	}
	metadata, err := json.Marshal(input.settings)
	if err != nil {
		return "", err
	}
	file := filepath.Join(root, "settings.json")
	if err := os.WriteFile(file, metadata, 0o600); err != nil {
		return "", err
	}
	files = append(files, devicebackup.File{Name: "native/desktop/settings.json", Source: file})
	output := filepath.Join(root, "device.zip")
	return output, devicebackup.Write(ctx, output, manifest, files)
}

func newDeviceBackupDirectory() (string, error) {
	root := userDataDir("device-backups")
	if err := os.MkdirAll(root, 0o700); err != nil {
		return "", err
	}
	return os.MkdirTemp(root, "operation-")
}

func uploadDesktopBackup(ctx context.Context, service *edge.Sync, input desktopBackupInput, doc *edge.DeviceSettings) error {
	devices, err := service.SettingsDevices(ctx)
	if err != nil {
		return err
	}
	var old edge.DeviceSettings
	for _, device := range devices {
		if device.Current && device.Updated > 0 {
			old, err = service.ReadDeviceSettings(ctx, device.Key)
			if err != nil {
				return err
			}
			break
		}
	}
	root, err := newDeviceBackupDirectory()
	if err != nil {
		return err
	}
	defer os.RemoveAll(root)
	archive, err := buildDesktopBackup(ctx, input, root)
	if err != nil {
		return err
	}
	file, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer file.Close()
	ref, err := service.UploadDeviceBackup(ctx, file, "desktop", len(input.plugins))
	if err != nil {
		return err
	}
	doc.Backup = &ref
	doc.PreviousBackup = old.Backup
	if err := service.SaveDeviceSettings(ctx, *doc); err != nil {
		// A lost response can follow a successful commit. Keep the complete
		// objects so an already-published snapshot never becomes unreadable.
		return err
	}
	if old.PreviousBackup != nil {
		_ = service.DeleteDeviceBackup(ctx, *old.PreviousBackup)
	}
	return nil
}

type desktopBackupResult struct {
	session     *player.Session
	ids         []string
	settings    settings
	preferences pluginprefs.Document
	discovery   discoveryprefs.Document
}

func restoreDesktopArchive(ctx context.Context, stage string, manifest devicebackup.Manifest, input desktopBackupInput, values map[string]string, overwrite bool) (result desktopBackupResult, err error) {
	result.settings = input.settings
	if manifest.Platform == "desktop" {
		if bytes, readErr := os.ReadFile(filepath.Join(stage, "native/desktop/settings.json")); readErr == nil {
			native := defaultSettings()
			native.path = input.settings.path
			if json.Unmarshal(bytes, &native) != nil {
				return result, errors.New("invalid backup app settings")
			}
			result.settings = native
		}
	}
	result.settings, _, _ = restorePortableAppSettings(result.settings, values)
	if len(manifest.PluginPreferences) > 0 {
		if json.Unmarshal(manifest.PluginPreferences, &result.preferences) != nil || result.preferences.Validate() != nil {
			return result, errors.New("invalid backup plugin preferences")
		}
	}
	if len(manifest.DiscoveryPreferences) > 0 {
		if json.Unmarshal(manifest.DiscoveryPreferences, &result.discovery) != nil || result.discovery.Validate() != nil {
			return result, errors.New("invalid backup discovery preferences")
		}
	}
	var pending []*plugin.Pending
	defer func() {
		for _, p := range pending {
			p.Discard()
		}
	}()
	// Validate every package before changing installed code or account storage.
	for _, record := range manifest.Plugins {
		p, openErr := plugin.Open(filepath.Join(stage, "plugins", record.ID, "package.zip"), pluginsDir())
		if openErr != nil {
			return result, openErr
		}
		pending = append(pending, p)
		if p.Manifest.ID != record.ID {
			return result, errors.New("backup plugin ID mismatch")
		}
		if record.Repository != "" {
			p.Repo, err = plugin.ParseRepo(record.Repository)
			if err != nil {
				return result, err
			}
			p.Version = record.Revision
		}
	}
	for i, record := range manifest.Plugins {
		if err := ctx.Err(); err != nil {
			return result, err
		}
		_, existingErr := os.Lstat(filepath.Join(pluginsDir(), record.ID))
		if errors.Is(existingErr, os.ErrNotExist) {
			if err := pending[i].Install(pluginsDir()); err != nil {
				return result, err
			}

		} else if existingErr != nil {
			return result, existingErr
		}
		result.ids = append(result.ids, record.ID)
		data := userDataDir("plugin-data/" + record.ID)
		source := filepath.Join(stage, "plugins", record.ID, "data")
		if overwrite {
			values := map[string]string{}
			fields := pending[i].Manifest.Settings
			// Existing code stays installed and provides the accepted settings.
			if existingErr == nil {
				current, err := plugin.Load(filepath.Join(pluginsDir(), record.ID), userDataDir("plugin-data"))
				if err != nil {
					return result, err
				}
				fields = current.Info().Settings
				current.Close()
			}
			for _, field := range fields {
				if value, ok := record.Settings[field.Key]; ok {
					values[field.Key] = value
				}
			}
			for _, installed := range input.plugins {
				if installed.info.ID == record.ID {
					installed.source.RetireForDataRestore()
					break
				}
			}
			if err := plugin.ReplaceBackupData(ctx, source, data, values); err != nil {
				return result, err
			}
			continue
		}
		if err := plugin.RestoreBackupData(source, data); err != nil {
			return result, err
		}
		settingsFile := filepath.Join(data, "settings.json")
		values := map[string]string{}
		if bytes, readErr := os.ReadFile(settingsFile); readErr == nil {
			info, statErr := os.Lstat(settingsFile)
			if statErr != nil || !info.Mode().IsRegular() || json.Unmarshal(bytes, &values) != nil {
				return result, errors.New("invalid existing plugin settings")
			}
		} else if !errors.Is(readErr, os.ErrNotExist) {
			return result, readErr
		}
		for _, field := range pending[i].Manifest.Settings {
			if value, ok := record.Settings[field.Key]; ok {
				values[field.Key] = value
			}
		}
		if err := devicebackup.EnsureDirectory(data, ""); err != nil {
			return result, err
		}
		bytes, err := json.Marshal(values)
		if err != nil {
			return result, err
		}
		temporary, err := os.CreateTemp(data, ".backup-settings-")
		if err != nil {
			return result, err
		}
		_, writeErr := temporary.Write(bytes)
		closeErr := temporary.Close()
		if writeErr == nil && closeErr == nil {
			writeErr = os.Rename(temporary.Name(), settingsFile)
		}
		_ = os.Remove(temporary.Name())
		if err := errors.Join(writeErr, closeErr); err != nil {
			return result, err
		}

	}
	if input.local != nil {
		if err := input.local.RestoreDeviceBackup(ctx, manifest.Library, stage, overwrite); err != nil {
			return result, err
		}
	}
	if input.preferences != nil {
		result.preferences, err = input.preferences.Update(func(current pluginprefs.Document, writer string) (pluginprefs.Document, error) {
			var e error
			if result.preferences.Version == 1 {
				current, e = current.Merge(result.preferences)
				if e != nil {
					return current, e
				}
			}
			order := []string{}
			for _, record := range manifest.Plugins {
				current, e = current.WithName(record.ID, record.Name, writer)
				if e != nil {
					return current, e
				}
				current, e = current.WithEnabled(record.ID, record.Enabled, writer)
				if e != nil {
					return current, e
				}
				order = append(order, record.ID)
			}
			if current.Order != nil {
				for _, id := range current.Order.Value {
					if !slices.Contains(order, id) {
						order = append(order, id)
					}
				}
			}
			return current.WithOrder(order, writer)
		})
		if err != nil {
			return result, err
		}
	}
	if input.discovery != nil && result.discovery.Version == 1 {
		result.discovery, err = input.discovery.Merge(result.discovery)
		if err != nil {
			return result, err
		}
	}
	if queue := manifest.Library.Queue; queue != nil && len(queue.Tracks) > 0 {
		session := player.Session{At: max(0, min(queue.Index, len(queue.Tracks)-1)), Position: max(0, queue.Position), Shuffle: queue.Shuffled}
		switch queue.Repeat {
		case "all":
			session.Repeat = player.RepeatAll
		case "one":
			session.Repeat = player.RepeatOne
		}
		records := map[string]devicebackup.Track{}
		for _, tr := range manifest.Library.Tracks {
			records[tr.Key()] = tr
		}
		for _, tr := range queue.Tracks {
			if full, ok := records[tr.Key()]; ok {
				tr = full
			}
			session.Queue = append(session.Queue, input.local.RestoredBackupTrack(tr))
			session.Order = append(session.Order, len(session.Order))
		}
		result.session = &session
	}
	result.settings.SearchHistory = manifest.Library.SearchHistory
	if err := ctx.Err(); err != nil {
		return result, err
	}
	return result, result.settings.save()
}

func (a *app) restoreCompleteDeviceBackup(doc edge.DeviceSettings) {
	if doc.Backup == nil {
		return
	}
	ctx, key, input := a.edge.profileContext, a.edge.previewKey, a.captureDeviceBackup()
	var restored desktopBackupResult
	a.runEdge("Restoring device backup…", func(service *edge.Sync) error {
		err := withDeviceArchive(ctx, service, key, *doc.Backup, func(stage string, manifest devicebackup.Manifest) error {
			var err error
			restored, err = restoreDesktopArchive(ctx, stage, manifest, input, doc.App, true)
			return err
		})
		if err != nil && len(restored.ids) > 0 {
			a.post(func() {
				for _, id := range restored.ids {
					_ = a.pluginInstalled(id)
				}
			})
		}
		return err
	}, func() {
		a.settings = restored.settings
		a.settings.applyAppearance()
		a.setLanguage(a.settings.Language)
		for _, id := range restored.ids {
			if err := a.pluginInstalled(id); err != nil {
				a.edge.message = err.Error()
				return
			}
		}
		if restored.preferences.Version == 1 {
			a.applyPluginPreferences(restored.preferences)
		}
		if restored.discovery.Version == 1 {
			a.applyDiscoveryPreferences(restored.discovery)
		}
		if a.pl != nil {
			a.pl.ClearSession()
			if restored.session != nil {
				a.pl.Restore(*restored.session)
				a.saveSession()
			} else if path, err := sessionPath(); err == nil {
				if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
					a.edge.message = err.Error()
					return
				}
				a.savedSession = ""
			}
		}
		a.edge.confirmDeviceRestore = false
		a.edge.message = Tf("Device restored · %d plugins", doc.Backup.Plugins)
	})
}
