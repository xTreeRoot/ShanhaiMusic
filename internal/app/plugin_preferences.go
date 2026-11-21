package app

import (
	"context"
	"slices"
	"strings"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/local"
	"mygo-music/internal/pluginprefs"
)

func (a *app) startPluginPreferences() {
	enabled := map[string]bool{}
	for _, lib := range a.allLibs {
		if lib.id != local.ID && slices.Contains(a.settings.DisabledPlugins, lib.id) {
			enabled[lib.id] = false
		}
	}
	for _, id := range a.settings.DisabledPlugins {
		if id != local.ID {
			enabled[id] = false
		}
	}
	a.pluginPrefs = pluginprefs.NewStore(userDataDir("plugin-preferences"), pluginprefs.Legacy{Enabled: enabled})
	doc, err := a.pluginPrefs.Load()
	if err != nil {
		a.edge.message = err.Error()
		return
	}
	a.applyPluginPreferences(doc)
}
func (a *app) applyPluginPreferences(doc pluginprefs.Document) {
	if a.pluginPrefsDocument.Version == 1 {
		var err error
		doc, err = a.pluginPrefsDocument.Merge(doc)
		if err != nil {
			a.edge.message = err.Error()
			return
		}
	}
	a.pluginPrefsDocument = doc
	wasEnabled := slices.Clone(a.libs)
	for _, lib := range a.allLibs {
		lib.localName = ""
		if name, ok := doc.Names[lib.id]; ok {
			lib.localName = name.Value
		}
	}
	positions := map[string]int{}
	if doc.Order != nil {
		for index, id := range doc.Order.Value {
			positions[id] = index
		}
	}
	slices.SortStableFunc(a.allLibs, func(a, b *library) int {
		if a.id == local.ID {
			return -1
		}
		if b.id == local.ID {
			return 1
		}
		left, ok := positions[a.id]
		if !ok {
			left = len(positions)
		}
		right, ok := positions[b.id]
		if !ok {
			right = len(positions)
		}
		if left < right {
			return -1
		}
		if left > right {
			return 1
		}
		return 0
	})
	for id, entry := range doc.Enabled {
		if id == local.ID {
			continue
		}
		a.settings.DisabledPlugins = slices.DeleteFunc(a.settings.DisabledPlugins, func(value string) bool { return value == id })
		if !entry.Value {
			a.settings.DisabledPlugins = append(a.settings.DisabledPlugins, id)
		}
	}
	a.enabledChanged()
	for _, lib := range wasEnabled {
		if !slices.Contains(a.libs, lib) {
			a.invalidateEdge(lib)
			if closer, ok := lib.src.(interface{ Close() }); ok {
				go closer.Close()
			}
		}
	}
	if a.library != nil && !slices.Contains(a.libs, a.library) && len(a.libs) > 0 {
		a.switchSource(a.libs[0].id)
	}
	a.redraw()
}
func (a *app) editPluginPreferences(change func(pluginprefs.Document, string) (pluginprefs.Document, error)) {
	if a.pluginPrefs == nil || a.pluginPrefsBusy {
		return
	}
	a.pluginPrefsBusy = true
	store := a.pluginPrefs
	go func() {
		doc, err := store.Update(change)
		a.post(func() {
			a.pluginPrefsBusy = false
			if err != nil {
				a.edge.message = err.Error()
			} else {
				a.applyPluginPreferences(doc)
				a.syncPluginPreferences()
			}
			a.redraw()
		})
	}()
}
func (a *app) movePlugin(lib *library, step int) {
	var ids []string
	for _, candidate := range a.allLibs {
		if candidate.id != local.ID {
			ids = append(ids, candidate.id)
		}
	}
	from := slices.Index(ids, lib.id)
	to := from + step
	if from < 0 || to < 0 || to >= len(ids) {
		return
	}
	ids[from], ids[to] = ids[to], ids[from]
	a.editPluginPreferences(func(doc pluginprefs.Document, writer string) (pluginprefs.Document, error) {
		previous := []string{}
		if doc.Order != nil {
			previous = doc.Order.Value
		}
		next := []string{}
		at := 0
		for _, id := range previous {
			if slices.Contains(ids, id) {
				next = append(next, ids[at])
				at++
			} else {
				next = append(next, id)
			}
		}
		next = append(next, ids[at:]...)
		return doc.WithOrder(next, writer)
	})
}
func (a *app) savePluginName(id, name string) {
	name = strings.TrimSpace(name)
	a.editPluginPreferences(func(doc pluginprefs.Document, writer string) (pluginprefs.Document, error) {
		return doc.WithName(id, name, writer)
	})
}
func (a *app) pluginNameEditor(c *ui.Context, lib *library) {
	in := &a.installer
	if in.renamingID != lib.id {
		return
	}
	ui.Row(c).Gap(8).Children(func() {
		field := ui.TextInput(c.Key("local-name"), &in.localName).Label(T("Local Name")).Grow(1).Shrink(1).MinWidth(0).AutoFocus()
		save := ui.Button(c.Key("save-name"), T("Save")).Disabled(a.pluginPrefsBusy)
		if field.Submitted() || save.Clicked() {
			a.savePluginName(lib.id, in.localName)
			in.renamingID = ""
		}
		if ui.Button(c.Key("cancel-name"), T("Cancel")).Clicked() {
			in.renamingID = ""
		}
	})
}
func (a *app) syncPluginPreferences() {
	if a.pluginPrefs == nil || a.edge.service == nil || a.edge.profileContext == nil || a.pluginPrefsSyncing {
		return
	}
	a.pluginPrefsSyncing = true
	service, store, ctx := a.edge.service, a.pluginPrefs, a.edge.profileContext
	go func() {
		err := service.SyncPluginPreferences(ctx, store.Load, func(remote pluginprefs.Document) (pluginprefs.Document, error) {
			if ctx.Err() != nil {
				return pluginprefs.Empty(), context.Canceled
			}
			doc, err := store.Update(func(latest pluginprefs.Document, _ string) (pluginprefs.Document, error) {
				if ctx.Err() != nil {
					return pluginprefs.Empty(), context.Canceled
				}
				return latest.Merge(remote)
			})
			if err == nil {
				a.post(func() {
					if service == a.edge.service && ctx.Err() == nil {
						a.applyPluginPreferences(doc)
					}
				})
			}
			return doc, err
		})
		a.post(func() {
			a.pluginPrefsSyncing = false
			if service == a.edge.service && ctx.Err() == nil && err != nil {
				a.edge.message = edgeError(err)
			}
			if service != a.edge.service {
				a.syncPluginPreferences()
			}
		})
	}()
}
