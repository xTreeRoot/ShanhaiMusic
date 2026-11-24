package app

import (
	"context"
	"fmt"
	"maps"
	"slices"
	"sort"
	"strconv"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
)

func portableChoice(setting music.Setting, value string) bool {
	switch setting.Type {
	case "boolean":
		return value == "true" || value == "false"
	case "select":
		for _, option := range setting.Options {
			if option.Value == value && value != "" {
				return true
			}
		}
	}
	return false
}

func (a *app) edgeDevicesView(c *ui.Context, p palette) {
	panel := &a.edge
	ui.Column(c.Key("settings-devices")).Gap(6).Children(func() {
		ui.Row(c).AlignItems(ui.Center).Children(func() {
			ui.Text(c, T("Device settings")).Grow(1)
			ui.Button(c.Key("backup"), T("Back up this device")).Disabled(panel.busy).OnClick(a.backupDeviceSettings)
			ui.Button(c.Key("restore-other"), T("Restore from another device")).Disabled(panel.busy).OnClick(func() { panel.restoreDevices = !panel.restoreDevices })
		})
		if panel.devicesError != "" {
			ui.Text(c, panel.devicesError).TextColor(p.muted)
		}
		available := 0
		for _, dev := range panel.devices {
			if !panel.restoreDevices || dev.Current || dev.Updated == 0 {
				continue
			}
			available++
			name := deviceLabel(dev)
			ui.Button(c.Key(dev.Key), name).Disabled(panel.busy).OnClick(func() {
				if panel.previewKey == dev.Key {
					panel.previewKey = ""
					panel.preview = nil
					return
				}
				active := panel.profileContext
				var snapshot edge.DeviceSettings
				a.runEdge("Loading device settings…", func(s *edge.Sync) error {
					var err error
					snapshot, err = s.ReadDeviceSettings(active, dev.Key)
					return err
				}, func() {
					panel.previewKey = dev.Key
					panel.preview = &snapshot
					panel.settingsGroup = ""
					panel.message = ""
				})
			})
			if doc := panel.preview; doc != nil && panel.previewKey == dev.Key {
				if doc.Backup != nil {
					ui.Text(c, Tf("Complete backup · %d plugins", doc.Backup.Plugins)).TextColor(p.muted)
				} else {
					ui.Text(c, T("Settings only · legacy backup")).TextColor(p.muted)
				}
				a.deviceSettingRows(c, p, "", T("App preferences"), doc.App)
				keys := make([]string, 0, len(doc.Plugins))
				for id := range doc.Plugins {
					keys = append(keys, id)
				}
				sort.Strings(keys)
				for _, id := range keys {
					a.deviceSettingRows(c, p, id, T("Plugin settings")+" · "+a.settingsSourceName(id), doc.Plugins[id])
				}
				keys = keys[:0]
				for id := range doc.Accounts {
					keys = append(keys, id)
				}
				sort.Strings(keys)
				for _, id := range keys {
					edgeRecordRow(c, p, T("Account profile")+" · "+a.settingsSourceName(id), doc.Accounts[id])
					if lib := a.libOf(id); lib != nil && lib.can("signIn") {
						ui.Button(c.Key("login-"+id), Tf("Open %s account", lib.name())).OnClick(func() { a.promptSignIn(lib) })
					}
				}
				label := T("Restore these settings")
				if doc.Backup != nil {
					label = T("Restore this device")
				}
				ui.Button(c.Key("apply"), label).Disabled(panel.busy).OnClick(func() { a.restoreDeviceSettings(*doc) })
			}
		}
		if panel.restoreDevices && available == 0 {
			ui.Text(c, T("No other device backups. Back up the source device first.")).TextColor(p.muted)
		}
	})
}

func (a *app) deviceSettingRows(c *ui.Context, p palette, source, title string, values map[string]string) {
	group := "app"
	var fields []music.Setting
	if source != "" {
		group = "plugin/" + source
		if lib := a.libOf(source); lib != nil {
			fields = lib.src.Info().Settings
		}
	}
	ui.Button(c.Key(group), title+" · "+Tf("%d stored", len(values))).OnClick(func() {
		if a.edge.settingsGroup == group {
			a.edge.settingsGroup = ""
		} else {
			a.edge.settingsGroup = group
		}
	})
	if a.edge.settingsGroup != group {
		return
	}
	for _, key := range slices.Sorted(maps.Keys(values)) {
		label, value := key, values[key]
		if source == "" {
			if name := deviceFieldNames[key]; name != "" {
				label = T(name)
			}
			if name := deviceValueNames[value]; name != "" {
				value = T(name)
			}
		} else {
			for _, field := range fields {
				if field.Key != key {
					continue
				}
				label = field.Label
				if field.Type == "boolean" {
					if name := deviceValueNames[value]; name != "" {
						value = T(name)
					}
				}
				for _, option := range field.Options {
					if option.Value == values[key] {
						value = option.Label
						break
					}
				}
				break
			}
		}
		edgeRecordRow(c, p, label, value)
	}
}

func deviceLabel(d edge.SettingsDevice) string {
	suffix := d.Key
	if len(suffix) > 6 {
		suffix = suffix[len(suffix)-6:]
	}
	return d.Name + " · " + suffix + " · " + time.Unix(d.Updated, 0).Local().Format("01-02 15:04")
}
func (a *app) settingsSourceName(id string) string {
	for _, l := range a.allLibs {
		if l.id == id {
			return l.name()
		}
	}
	return id
}

func (a *app) backupDeviceSettings() {
	s := a.settings
	doc := edge.DeviceSettings{Version: 1, App: map[string]string{"appearance": s.Appearance, "language": s.Language, "quality": s.Quality, "smoke": strconv.FormatBool(s.Smoke), "fog": strconv.FormatBool(s.Fog), "growFromCover": strconv.FormatBool(s.GrowFromCover), "lyricsStage": strconv.FormatBool(s.LyricsStage), "hotComments": strconv.FormatBool(s.HotComments), "stageSparks": strconv.FormatBool(s.StageSparks)}, Plugins: map[string]map[string]string{}, Accounts: map[string]string{}}
	libs := append([]*library(nil), a.allLibs...)
	enabled := map[*library]bool{}
	for _, l := range a.libs {
		enabled[l] = true
	}
	ctx := a.edge.profileContext
	input := a.captureDeviceBackup()
	a.runEdge("Backing up this device…", func(s *edge.Sync) error {
		for _, l := range libs {
			src := pluginOf(l)
			if src == nil {
				continue
			}
			if enabled[l] && src.Can("account") {
				if acc, err := src.Account(); err == nil && acc != nil {
					doc.Accounts[l.id] = acc.Name
				}
			}
			values := map[string]string{}
			for _, st := range src.Info().Settings {
				if v := src.Setting(st.Key); portableChoice(st, v) {
					values[st.Key] = v
				}
			}
			if len(values) > 0 {
				doc.Plugins[l.id] = values
			}
		}
		return uploadDesktopBackup(ctx, s, input, &doc)
	}, func() { a.edge.preview = nil; a.edge.message = T("Device backed up, including plugins and their data") })
}

func (a *app) restoreDeviceSettings(doc edge.DeviceSettings) {
	if doc.Backup != nil {
		a.restoreCompleteDeviceBackup(doc)
		return
	}
	ctx := a.edge.profileContext
	libs := append([]*library(nil), a.allLibs...)
	applied := 0
	skipped := 0
	a.runEdge("Restoring settings…", func(_ *edge.Sync) error {
		for id, items := range doc.Plugins {
			matched := 0
			for _, l := range libs {
				if l.id != id {
					continue
				}
				src := pluginOf(l)
				if src == nil {
					break
				}
				for _, st := range src.Info().Settings {
					if v, ok := items[st.Key]; ok && portableChoice(st, v) {
						if ctx.Err() != nil {
							return context.Canceled
						}
						if err := src.SetSetting(st.Key, v); err != nil {
							return fmt.Errorf("%s: %w", id, err)
						}
						matched++
					}
				}
				break
			}
			applied += matched
			skipped += len(items) - matched
		}
		return nil
	}, func() {
		s := a.settings
		var count, missing int
		s, count, missing = restorePortableAppSettings(s, doc.App)
		applied += count
		skipped += missing
		if err := s.save(); err != nil {
			a.edge.message = err.Error()
			return
		}
		languageChanged := s.Language != a.settings.Language
		a.settings = s
		s.applyAppearance()
		if languageChanged {
			a.setLanguage(s.Language)
		}
		a.edge.message = Tf("%d restored · %d skipped", applied, skipped)
	})
}

var deviceFieldNames = map[string]string{
	"appearance":                "Appearance",
	"language":                  "Language",
	"quality":                   "Quality",
	"smoke":                     "Smoke on the Progress Bar",
	"fog":                       "Fog Backdrop",
	"growFromCover":             "Grow From the Cover",
	"lyricsStage":               "Stage Lyrics",
	"hotComments":               "Top Comments in Quiet Parts",
	"stageSparks":               "Sparks",
	"nowPlayingLyrics":          "Now Playing lyrics",
	"pauseOtherSourcesOnSwitch": "Pause when switching sources",
	"inAppVideoWindow":          "In-app video window",
	"systemVideoWindow":         "System video window",
	"automaticUpdateChecks":     "Automatic update checks",
}
var deviceValueNames = map[string]string{
	"true":     "Turn On",
	"false":    "Off",
	"system":   "System",
	"dark":     "Dark",
	"light":    "Light",
	"high":     "High",
	"lossless": "Lossless",
	"standard": "Standard",
	"zh-CN":    "Simplified Chinese",
	"en":       "English",
}

func restorePortableAppSettings(s settings, values map[string]string) (settings, int, int) {
	applied, skipped := 0, 0
	for k, v := range values {
		switch k {
		case "appearance":
			if v != "system" && v != "dark" && v != "light" {
				skipped++
				continue
			}
			s.Appearance = v
		case "language":
			if v != i18n.System && v != i18n.Chinese && v != i18n.English {
				skipped++
				continue
			}
			s.Language = v
		case "quality":
			if v != "standard" && v != "high" && v != "lossless" {
				skipped++
				continue
			}
			s.Quality = v
		default:
			fields := map[string]*bool{"smoke": &s.Smoke, "fog": &s.Fog, "growFromCover": &s.GrowFromCover, "lyricsStage": &s.LyricsStage, "hotComments": &s.HotComments, "stageSparks": &s.StageSparks}
			f := fields[k]
			if f == nil || v != "true" && v != "false" {
				skipped++
				continue
			}
			*f = v == "true"
		}
		applied++
	}
	return s, applied, skipped
}
