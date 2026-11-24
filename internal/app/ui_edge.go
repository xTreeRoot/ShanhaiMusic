package app

import (
	"sort"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
	"mygo-music/internal/local"
)

// accountTool gives plugins and the node service the same sidebar treatment.
func (a *app) accountTool(c *ui.Context, icon *ui.SVG, label string, open func()) {
	p := a.pal
	button := ui.ButtonBase(c.Key(label)).Label(label).Height(34).PaddingX(10).Gap(10).Radius(8).AlignItems(ui.Center).Justify(ui.Start)
	if button.Hovered() {
		button.Background(p.hover)
	}
	button.Children(func() {
		ui.Icon(c, icon).Size(17, 17).TextColor(p.muted)
		ui.Text(c, label).FontSize(13.5).FontWeight(500).TextColor(p.text)
	})
	button.OnClick(open)
}

func (a *app) openEdgeSettings() {
	if a.win == nil {
		return
	}
	a.refreshEdgeOverview()
	if a.edge.window != nil {
		a.edge.window.Show()
		a.edge.window.Focus()
		return
	}
	a.edge.window = mygo.NewWindow(mygo.WindowOptions{
		Title: T("Shanhai Service"), Width: 620, Height: 640, MinWidth: 540, MinHeight: 420,
		StateKey: "shanhai-service", Content: ui.View(a.edgeView),
	})
	a.edge.window.OnClosed(func() {
		panel := &a.edge
		panel.window, panel.password, panel.passwordVisible, panel.deleteFor = nil, "", false, ""
		if panel.overviewStop != nil {
			panel.overviewStop()
			panel.overviewStop = nil
		}
	})
}

func (a *app) edgeView(c *ui.Context) {
	p := paletteFor(c.Theme())
	c.Root().Background(p.bg)
	c.OnShortcut(ui.Cmd, ui.KeyW, func() {
		if a.edge.window != nil {
			a.edge.window.Close()
		}
	})
	ui.Scroll(c.Key("node-service")).Fill().Padding(24).Gap(16).Children(func() {
		panel := &a.edge
		if status := panel.activity; status != "" {
			ui.Text(c, status).TextColor(p.muted)
		} else if panel.message != "" {
			ui.Text(c, panel.message).TextColor(p.text)
		}
		connected, authorized := false, false
		if panel.service != nil {
			space, expires := panel.service.Binding()
			connected, authorized = space != "", panel.service.Authorized()
			if connected {
				a.edgeConnection(c, p, expires, authorized)
			}
		}
		if authorized {
			a.edgeDevicesView(c, p)
			a.edgeCloud(c, p)
		}
		if !authorized {
			a.edgeConnectionForm(c, p, connected)
		}
	})
}

func (a *app) edgeConnection(c *ui.Context, p palette, expires int64, authorized bool) {
	panel := &a.edge
	ui.Row(c.Key("edge-status")).AlignItems(ui.Center).Gap(12).Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Gap(4).Children(func() {
			status := T("Node Connected")
			if !authorized {
				status = T("Reconnect to Authorize Sync")
			}
			ui.Text(c, status).Bold()
			ui.Text(c, Tf("%s · Authorization expires %s", panel.node.Address, time.Unix(expires, 0).Format("2006-01-02"))).TextColor(p.muted)
		})
		ui.Button(c.Key("edge-unbind"), T("Unlink This Device")).Disabled(panel.busy).Shrink(0).OnClick(func() {
			a.runEdge("Unlinking this device…", (*edge.Sync).Unbind, func() {
				a.activateNode(nil)
				a.cancelInstall()
				panel.password = ""
				panel.message = T("Device disconnected")
			})
		})
	})
}

func (a *app) edgeCloud(c *ui.Context, p palette) {
	panel := &a.edge
	overview := edge.Overview{}
	if panel.overview != nil {
		overview = *panel.overview
	}
	known := panel.overview != nil && overview.InventoryError == nil
	ui.Column(c.Key("edge-cloud")).Gap(10).Children(func() {
		ui.Row(c).AlignItems(ui.Center).Gap(12).Children(func() {
			ui.Text(c, T("App preferences")).Grow(1)
			ui.Button(c.Key("edge-refresh"), T("Refresh")).Disabled(panel.busy || panel.overviewStop != nil).OnClick(a.refreshEdgeOverview)
		})
		if panel.overviewStop != nil {
			ui.Text(c, T("Checking cloud records…")).TextColor(p.muted)
		}
		if panel.overviewError != "" {
			ui.Text(c, panel.overviewError)
		} else if overview.InventoryError != nil {
			ui.Text(c, edgeError(overview.InventoryError))
		}
		ui.Column(c).Radius(10).Border(1, p.hairline).Dividers(1, p.hairline).Children(func() {
			edgeRecordRow(c, p, T("Plugin Preferences"), edgeRecordText(overview.PluginPreferences))
			edgeRecordRow(c, p, T("Discovery Preferences"), edgeRecordText(overview.Discovery))
			for _, record := range overview.Records {
				if record.Platform == "app" && record.Kind == "data" && record.Key != "discovery" && record.Key != "plugins" {
					edgeRecordRow(c, p, record.Key, T("Stored in Cloud"))
				}
			}
		})
		names := map[string]string{}
		for _, lib := range a.allLibs {
			if lib.id != local.ID {
				names[lib.id] = lib.name()
			}
		}
		for _, record := range overview.Records {
			if _, exists := names[record.Platform]; !exists && record.Platform != "app" {
				names[record.Platform] = record.Platform
			}
		}
		ids := make([]string, 0, len(names))
		for id := range names {
			ids = append(ids, id)
		}
		sort.Slice(ids, func(i, j int) bool {
			if names[ids[i]] == names[ids[j]] {
				return ids[i] < ids[j]
			}
			return names[ids[i]] < names[ids[j]]
		})
		for _, id := range ids {
			lib := a.libOf(id)
			ui.Column(c.Key("node-source-" + id)).Gap(5).Children(func() {
				row := ui.ButtonBase(c.Key("expand")).Label(names[id]).MinHeight(44).AlignItems(ui.Center).Gap(8).PaddingX(12).Radius(8).Background(p.hover)
				row.Children(func() {
					ui.Text(c, names[id]).Grow(1)
					count := 0
					for _, record := range overview.Records {
						if record.Platform == id {
							count += record.Count
						}
					}
					if known {
						ui.Text(c, Tf("%d stored", count)).TextColor(p.muted)
					}
					arrow := ui.Icon(c, iconDown).Size(14, 14)
					if panel.managedSource != id {
						arrow.Rotate(-90)
					}
				})
				row.OnClick(func() {
					if panel.managedSource == id {
						panel.managedSource = ""
					} else {
						panel.managedSource = id
					}
					panel.deleteFor = ""
				})
				if panel.managedSource != id {
					return
				}
				profile, sessions := T("Not Checked"), T("Not Checked")
				if known {
					profile, sessions = T("Not Stored"), T("Not Stored")
				}
				for _, record := range overview.Records {
					if record.Platform != id {
						continue
					}
					switch {
					case record.Kind == "sessions":
						sessions = Tf("%d in Cloud · Not Yet Verified", record.Count)
					case record.Key == "profile":
						profile = T("Stored in Cloud")
					default:
						edgeRecordRow(c, p, record.Key, T("Stored in Cloud"))
					}
				}
				ui.Row(c).AlignItems(ui.Center).Gap(8).Padding(6, 10).Children(func() {
					ui.Text(c, T("Account profile")).Grow(1)
					ui.Text(c, profile).TextColor(p.muted)
					if lib != nil && lib.can("account") {
						label := T("Sync profile")
						if panel.profileJobs[lib] != nil {
							label = T("Syncing account profile…")
						}
						ui.Button(c.Key("profile"), label).Disabled(panel.busy || lib.accountBusy || panel.profileJobs[lib] != nil).OnClick(func() { a.syncEdgeProfile(lib, true) })
					}
				})
				edgeRecordRow(c, p, T("Sign-in Sessions"), sessions)
				if lib != nil {
					a.edgeDeleteCloud(c, p, lib)
				}
			})
		}
	})
}

func edgeRecordText(status edge.RecordStatus) string {
	switch status {
	case edge.RecordStored:
		return T("Stored in Cloud")
	case edge.RecordMissing:
		return T("Not Stored")
	case edge.RecordUnavailable:
		return T("Could Not Check")
	default:
		return T("Not Checked")
	}
}

func edgeRecordRow(c *ui.Context, p palette, label, value string) {
	ui.Row(c).AlignItems(ui.Center).Gap(12).Padding(10, 14).Children(func() {
		ui.Text(c, label).Grow(1).MinWidth(0)
		ui.Text(c, value).TextColor(p.muted).Shrink(0)
	})
}

func (a *app) edgeDeleteCloud(c *ui.Context, p palette, lib *library) {
	panel := &a.edge
	if panel.service.HasRemote(lib.id) {
		if panel.deleteFor == lib.id {
			ui.Row(c).Gap(8).Children(func() {
				ui.Button(c.Key("edge-delete-confirm"), T("Confirm Cloud Deletion")).Disabled(panel.busy).OnClick(func() {
					ctx := panel.profileContext
					a.runEdge("Deleting cloud session…", func(service *edge.Sync) error { return service.Delete(ctx, lib.id) }, func() {
						panel.deleteFor, panel.message = "", T("Cloud session deleted. Existing device sign-ins were not changed.")
					})
				})
				ui.Button(c.Key("edge-delete-cancel"), T("Cancel")).Disabled(panel.busy).OnClick(func() { panel.deleteFor = "" })
			})
		} else {
			ui.Button(c.Key("edge-delete"), T("Delete This Source’s Cloud Session…")).Disabled(panel.busy).OnClick(func() { panel.deleteFor = lib.id })
		}
	}
}

func (a *app) edgeConnectionForm(c *ui.Context, p palette, connected bool) {
	panel := &a.edge
	ui.Column(c.Key("edge-connection-form")).Gap(10).Children(func() {
		label := T("Connect to Node")
		if connected {
			label = T("Reconnect to Node")
		}

		ui.Column(c).Radius(12).Border(1, p.hairline).Dividers(1, p.hairline).Children(func() {
			ui.Row(c).Padding(12, 16).Gap(12).AlignItems(ui.Center).Children(func() {
				ui.Text(c, T("Node Address")).Width(104).Shrink(0).SingleLine()
				ui.TextInput(c.Key("edge-address"), &panel.address).Grow(1).MinWidth(0).Label(T("Server Domain or HTTPS Address")).Placeholder(T("Server Domain or HTTPS Address")).Disabled(panel.busy)
				ui.Button(c.Key("edge-clear"), T("Clear")).Label(T("Clear Node Address")).Disabled(panel.busy || panel.address == "").OnClick(func() { panel.address = "" })
			})
			ui.Row(c).Padding(12, 16).Gap(12).AlignItems(ui.Center).Children(func() {
				ui.Text(c, T("Node Password")).Width(104).Shrink(0).SingleLine()
				input := ui.TextInput(c.Key("edge-password"), &panel.password).Grow(1).MinWidth(0).Label(T("Password")).Placeholder(T("Password")).Disabled(panel.busy)
				if !panel.passwordVisible {
					input.Password()
				}
				label := T("Show Password")
				if panel.passwordVisible {
					label = T("Hide Password")
				}
				ui.Button(c.Key("edge-password-visibility"), label).Disabled(panel.busy).OnClick(func() { panel.passwordVisible = !panel.passwordVisible })
			})
		})

		address, _ := edge.NormalizeAddress(panel.address)
		if panel.keyApproval != "" && panel.keyAddress == address {
			label = T("Trust the New Signing Key and Reconnect")
		}
		ui.PrimaryButton(c.Key("edge-pair"), label).Height(44).WidthPercent(100).Label(label + " MB Server").Disabled(panel.busy || panel.address == "" || panel.password == "").OnClick(a.joinEdge)
	})
}
