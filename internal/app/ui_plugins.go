package app

import (
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/local"
)

type pluginDrag struct{ id string }

func (a *app) selectPluginSettings(id string) {
	a.settingsPage, a.settingsQuery = "plugins", ""
	a.installer.detailsID, a.installer.networkOpen, a.installer.editingPlugins = id, false, false
}

func (a *app) installedLibrary(id string) *library {
	for _, lib := range a.allLibs {
		if lib.id == id && id != local.ID {
			return lib
		}
	}
	return nil
}

func (a *app) pluginListRow(c *ui.Context, lib *library, index int, editing bool, p palette) {
	row := ui.Row(c).Padding(6, 12).Gap(8).AlignItems(ui.Center).MinHeight(54)
	if editing && !a.pluginPrefsBusy {
		if drag, over := ui.DragOver[pluginDrag](row); over && drag.id != lib.id {
			row.Background(p.accent.Alpha(0.12))
		}
		if drag, dropped := ui.Drop[pluginDrag](row); dropped {
			a.movePlugin(drag.id, lib.id)
		}
	}
	if a.installer.marked == lib.id {
		row.Background(p.accent.Alpha(0.12)).Border(1, p.accent).Radius(8)
		if a.installer.reveal {
			a.installer.reveal = false
			row.ScrollIntoView(ui.ScrollIntoViewOptions{Block: ui.ScrollCenter, Animation: &ui.Smooth})
		}
	}
	row.Children(func() {
		identity := func() {
			a.pluginTile(c, lib)
			ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(3).Children(func() {
				ui.Text(c, lib.name()).FontSize(13.5).FontWeight(600).SingleLine().Tooltip(lib.name())
				status := T("Plugin Disabled")
				if a.pluginEnabled(lib) {
					status = T("Plugin Enabled")
				}
				if version := lib.src.Info().Version; version != "" {
					status = version + " · " + status
				}
				if version := a.pluginUpdateVersion(lib); !editing && version != "" {
					status += " · " + Tf("Update Available: %s", version)
				}
				ui.Text(c, status).FontSize(11.5).TextColor(p.muted).SingleLine()
			})
		}
		if editing {
			ui.Row(c).Grow(1).Shrink(1).MinWidth(0).PaddingX(4).Gap(10).AlignItems(ui.Center).Children(identity)
			ui.Button(c.Key("rename"), T("Rename Locally")).Label(Tf("Rename %s Locally", lib.name())).
				Disabled(a.pluginPrefsBusy).OnClick(func() { a.openPluginNameDialog(lib) })
			a.pluginReorderHandle(c, lib, index, p)
			return
		}
		open := ui.ButtonBase(c.Key("details")).Grow(1).Shrink(1).MinWidth(0).MinHeight(42).PaddingX(4).Gap(10).
			AlignItems(ui.Center).Justify(ui.Start).Radius(8).Label(Tf("Settings for %s", lib.name()))
		if open.Hovered() {
			open.Background(p.hover)
		}
		open.Children(func() {
			identity()
			ui.Icon(c, iconForward).Size(13, 13).Shrink(0).TextColor(p.faint)
		})
		open.OnClick(func() { a.selectPluginSettings(lib.id) })
		a.pluginSwitch(c, lib)
		ui.Button(c.Key("discovery-sections"), T("Display Settings")).Shrink(0).
			Label(Tf("Display Settings for %s", lib.name())).OnClick(func() { a.openDiscoveryEditor(lib) })
	})
}

func (a *app) pluginReorderHandle(c *ui.Context, lib *library, index int, p palette) {
	up := index > 0 && a.allLibs[index-1].id != local.ID
	down := index+1 < len(a.allLibs) && a.allLibs[index+1].id != local.ID
	handle := iconButton(c.Key("reorder"), iconPluginGrip, 17, p.muted, Tf("Reorder %s", lib.name())).
		Disabled(a.pluginPrefsBusy || !up && !down)
	if !a.pluginPrefsBusy && (up || down) {
		handle.Drag(pluginDrag{lib.id})
	}
	handle.ContextMenu(func(m *ui.Menu) {
		if m.Item(T("Move Up")).Disabled(!up || a.pluginPrefsBusy).Chosen() && up {
			a.movePlugin(lib.id, a.allLibs[index-1].id)
		}
		if m.Item(T("Move Down")).Disabled(!down || a.pluginPrefsBusy).Chosen() && down {
			a.movePlugin(lib.id, a.allLibs[index+1].id)
		}
	})
}

func (a *app) pluginDetails(c *ui.Context, lib *library, p palette) {
	in := &a.installer
	info := lib.src.Info()
	enabled := a.pluginEnabled(lib)
	a.settingsGroup(c, p, settingsSection{id: "plugin-details", build: func(row settingsRow, block settingsBlock) {
		row(T("Local Name"), lib.name(), func() {
			ui.Button(c.Key("rename"), T("Rename Locally")).Disabled(a.pluginPrefs == nil || a.pluginPrefsBusy).
				OnClick(func() { a.openPluginNameDialog(lib) })
		})
		if from, url := pluginFrom(lib); from != "" {
			row(T("Install Source"), "", func() {
				if url != "" {
					ui.Link(c, from, url).FontSize(12.5).SingleLine().Shrink(1).MinWidth(0).Tooltip(T("Open on GitHub"))
				} else {
					ui.Text(c, from).FontSize(12.5).TextColor(p.muted)
				}
			})
		}
		setup := ""
		if enabled && lib.needsSetup {
			setup = setupNotice(lib, info)
		}
		row(T("Enable Source"), setup, func() { a.pluginSwitch(c, lib) })
		if enabled {
			a.pluginAccountRow(c, lib, row)
			for _, setting := range info.Settings {
				block(setting.Label, func() {
					ui.Column(c.Key(setting.Key)).Padding(10, 16).Children(func() { a.sourceSetting(c, lib, setting, p) })
				})
			}
		}
		if g, ok := lib.src.(guarded); ok {
			if perms := g.Permissions(); len(perms.Network) != 0 || enabled && len(perms.Process) != 0 {
				block("permissions", func() {
					ui.Column(c.Key("permissions")).Padding(8, 16).Gap(8).Children(func() { a.pluginPermissions(c, lib, g, perms, p) })
				})
			}
		}
		if plug := pluginOf(lib); plug != nil {
			if plug.Origin() != nil {
				block("updates", func() {
					ui.Column(c.Key("updates")).Padding(10, 16).Gap(8).Children(func() {
						ui.Row(c).Gap(8).Children(func() {
							busy := in.batch || in.checked[lib.id] == checking || in.updating[lib.id]
							ui.Button(c.Key("check"), T("Check for Updates")).Disabled(busy).OnClick(func() { a.checkPluginUpdate(lib) })
							a.pluginUpdateButton(c, lib)
						})
						a.pluginUpdateError(c, lib, p)
					})
				})
			}
			block("manage", func() {
				ui.Row(c.Key("manage")).Padding(10, 16).Gap(8).Children(func() {
					ui.Button(c.Key("show-folder"), T("Show Folder")).OnClick(func() { revealFolder(plug.Dir()) })
					ui.Button(c.Key("remove"), T("Remove…")).Disabled(len(a.allLibs) <= 1 || in.batch || in.updating[lib.id]).OnClick(func() {
						confirmRemovePlugin(a, lib.name(), func() { a.removePlugin(lib) })
					})
				})
			})
		}
	}}, "", "")
}

func (a *app) pluginAccountRow(c *ui.Context, lib *library, row settingsRow) {
	switch {
	case lib.account == nil && lib.accountUnknown():
		a.loadAccount(lib)
	case lib.account != nil:
		row(T("Account"), Tf("Signed in as %s", lib.account.Name), func() {
			if lib.can("signOut") {
				ui.Button(c.Key("sign-out"), T("Sign Out")).OnClick(func() { a.signOut(lib) })
			}
		})
	case lib.can("signIn"):
		row(T("Account"), "", func() {
			ui.PrimaryButton(c.Key("sign-in"), T("Sign In")).OnClick(func() {
				if a.installer.main {
					a.promptSignIn(lib)
				} else {
					a.promptSignInInSettings(lib)
				}
			})
		})
	}
}

func (a *app) openPluginNameDialog(lib *library) {
	a.installer.renamingID, a.installer.localName = lib.id, lib.localName
	a.installer.renaming = true
}

func (a *app) pluginNameDialog(c *ui.Context, p palette) {
	in := &a.installer
	ui.DialogBase(c.Key("plugin-name-dialog"), &in.renaming, func(backdrop, panel ui.Element) {
		backdrop.Background(ui.RGBA(0, 0, 0, 0.35))
		panel.Width(360).Padding(22).Gap(12).Radius(16).Background(p.bg).Border(1, p.hairline)
		ui.Text(c, T("Local Name")).FontSize(16).FontWeight(700)
		field := ui.TextInput(c.Key("local-name"), &in.localName).Label(T("Local Name")).
			Placeholder(T("Leave blank to restore the default name")).AutoFocus()
		busy := a.pluginPrefs == nil || a.pluginPrefsBusy || a.installedLibrary(in.renamingID) == nil
		ui.Row(c).Gap(8).Justify(ui.End).Children(func() {
			ui.Button(c.Key("cancel"), T("Cancel")).OnClick(func() { in.renaming = false })
			save := ui.PrimaryButton(c.Key("save"), T("Save")).Disabled(busy)
			if !busy && (field.Submitted() || save.Clicked()) {
				a.savePluginName(in.renamingID, in.localName)
				in.renaming = false
			}
		})
	})
}
