package app

import (
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
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
		ui.Text(c, T("Shanhai Service")).FontSize(22).Bold()
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
			a.edgeCloud(c, p)
		}
		a.edgeConnectionForm(c, p, connected)
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
				a.activateNode(panel.node)
				a.cancelInstall()
				panel.message = T("Device unlinked. Local sign-ins and cloud records were not deleted.")
			})
		})
	})
}

func (a *app) edgeCloud(c *ui.Context, p palette) {
	panel, lib := &a.edge, a.library
	overview := edge.Overview{}
	if panel.overview != nil && panel.overviewSource == lib {
		overview = *panel.overview
	}
	ui.Column(c.Key("edge-cloud")).Gap(12).Children(func() {
		ui.Row(c).AlignItems(ui.Center).Gap(12).Children(func() {
			ui.Text(c, T("What’s on This Node")).Bold().Grow(1)
			ui.Button(c.Key("edge-refresh"), T("Refresh Cloud Status")).Disabled(panel.busy || panel.overviewStop != nil).OnClick(a.refreshEdgeOverview)
		})
		if panel.overviewStop != nil {
			ui.Text(c, T("Checking cloud records…")).TextColor(p.muted)
		} else if panel.overviewError != "" {
			ui.Text(c, panel.overviewError).TextColor(p.text)
		}
		ui.Column(c).Radius(12).Border(1, p.hairline).Dividers(1, p.hairline).Children(func() {
			edgeRecordRow(c, p, T("Plugin Preferences"), edgeRecordText(overview.PluginPreferences))
			edgeRecordRow(c, p, T("Discovery Preferences"), edgeRecordText(overview.Discovery))
			for _, record := range overview.Records {
				if lib != nil && record.Platform == lib.id && lib.can("account") || record.Platform == "app" {
					continue
				}
				name := record.Platform
				if other := a.libOf(record.Platform); other != nil {
					name = other.name()
				}
				switch {
				case record.Kind == "sessions":
					edgeRecordRow(c, p, name+" · "+T("Sign-in Sessions"), Tf("%d in Cloud · Not Yet Verified", record.Count))
				case record.Key == "profile":
					edgeRecordRow(c, p, name+" · "+T("Account Profile · ID and Name Only"), T("Stored in Cloud"))
				}
			}
		})
		ui.Text(c, T("Plugin and Discovery preferences sync automatically.")).TextColor(p.muted)
		if lib != nil && lib.can("account") {
			a.edgeSourceCloud(c, p, lib, overview)
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

func (a *app) edgeSourceCloud(c *ui.Context, p palette, lib *library, overview edge.Overview) {
	panel := &a.edge
	sessions := T("Not Checked")
	if overview.SessionsError != nil {
		sessions = T("Could Not Check")
	} else if overview.Profile != edge.RecordUnchecked {
		sessions = T("Not Stored")
		if overview.Sessions > 0 {
			sessions = Tf("%d in Cloud · Not Yet Verified", overview.Sessions)
		}
	}
	ui.Column(c.Key("edge-source")).Gap(8).Children(func() {
		ui.Text(c, Tf("Current Source: %s", lib.name())).Bold()
		local := T("This device is signed out.")
		if lib.account != nil {
			local = Tf("Signed in as %s on this device.", lib.account.Name)
		} else if lib.accountUnknown() {
			local = T("Sign-in status on this device is not known yet.")
		}
		ui.Text(c, local).TextColor(p.muted)
		ui.Column(c).Radius(12).Border(1, p.hairline).Dividers(1, p.hairline).Children(func() {
			edgeRecordRow(c, p, T("Account Profile · ID and Name Only"), edgeRecordText(overview.Profile))
			edgeRecordRow(c, p, T("Sign-in Sessions"), sessions)
		})
		if overview.SessionsError != nil {
			ui.Text(c, edgeError(overview.SessionsError)).TextColor(p.muted)
		}
		if lib.account != nil {
			profileBusy := panel.profileJobs[lib] != nil
			label := T("Sync Account Profile Only")
			if profileBusy {
				label = T("Syncing account profile…")
			}
			ui.Button(c.Key("edge-profile"), label).Disabled(panel.busy || profileBusy).OnClick(func() { a.syncEdgeProfile(lib, true) })
		}
		source := pluginOf(lib)
		if source == nil || source.SessionMigrator() == nil {
			ui.Text(c, T("This plugin cannot sync or restore sign-ins.")).TextColor(p.muted)
		} else if lib.account != nil {
			ui.PrimaryButton(c.Key("edge-publish"), T("Sync Sign-in to Cloud")).Disabled(panel.busy || lib.accountBusy).OnClick(func() { a.publishEdgeSession(lib, source, false) })
			if fresh, ok := panel.fresh[lib]; ok && overview.AccountDeleted && fresh == panel.service.Ticket(lib.id) {
				ui.Button(c.Key("edge-recreate"), T("Confirm Recreating the Deleted Session")).Disabled(panel.busy).OnClick(func() { a.publishEdgeSession(lib, source, true) })
			}
		} else if overview.RestoreBlocked {
			ui.Text(c, T("You signed out on this device. Sign in here to re-enable cloud restore.")).TextColor(p.muted)
		} else {
			ui.PrimaryButton(c.Key("edge-restore"), T("Restore Sign-in from Cloud")).Disabled(panel.busy || lib.accountBusy || lib.accountUnknown()).OnClick(func() { a.chooseEdgeAccount(lib, "") })
			if panel.owner == lib {
				for _, choice := range panel.choices {
					ui.Button(c.Key(choice.Ref), Tf("Restore %s", choice.Label)).Disabled(panel.busy || lib.accountBusy).OnClick(func() { a.chooseEdgeAccount(lib, choice.Ref) })
				}
			}
		}
		if panel.service.HasRemote(lib.id) {
			if panel.deleteFor == lib.id {
				ui.Text(c, T("This removes the cloud copy, not any existing device sign-ins.")).TextColor(p.muted)
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
	})
}

func (a *app) edgeConnectionForm(c *ui.Context, p palette, connected bool) {
	panel := &a.edge
	ui.Column(c.Key("edge-connection-form")).Gap(10).Children(func() {
		label := T("Connect to Node")
		if connected {
			label = T("Reconnect to Node")
		}
		ui.Text(c, label).Bold()
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
		ui.Text(c, T("Node administrators can decrypt synced sessions. Only connect to a node you trust.")).TextColor(p.muted)
		address, _ := edge.NormalizeAddress(panel.address)
		if panel.keyApproval != "" && panel.keyAddress == address {
			label = T("Trust the New Signing Key and Reconnect")
		}
		ui.PrimaryButton(c.Key("edge-pair"), label).Height(44).WidthPercent(100).Label(label + " MB Server").Disabled(panel.busy || panel.address == "" || panel.password == "").OnClick(a.joinEdge)
	})
}
