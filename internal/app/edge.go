package app

import (
	"context"
	"errors"
	"net/http"
	"path/filepath"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
)

type edgePanel struct {
	window            *mygo.Window
	passwordVisible   bool
	nodes             *edge.Nodes
	node              *edge.Node
	service           *edge.Sync
	catalog           *edge.Catalog
	address, password string
	keyApproval       string
	keyAddress        string
	message           string
	busy              bool
	deleteFor         string
	owner             *library
	choices           []edge.Candidate
	fresh             map[*library]edge.Ticket
	context           context.Context
	stop              context.CancelFunc
	profileContext    context.Context
	profileJobs       map[*library]context.CancelFunc
}

func (a *app) startEdge(ctx context.Context) {
	dir, err := mygo.App.Path(mygo.PathUserData)
	if err != nil {
		return
	}
	dir = filepath.Join(dir, "edge")
	a.edge.nodes, a.edge.context = edge.NewNodes(dir), ctx
	node, err := a.edge.nodes.Resume()
	if err != nil {
		a.edge.message = err.Error()
		return
	}
	a.activateNode(node)
}

func (a *app) activateNode(node *edge.Node) {
	panel := &a.edge
	if panel.stop != nil {
		panel.stop()
	}
	panel.node = node
	panel.owner, panel.choices, panel.deleteFor = nil, nil, ""
	panel.fresh = make(map[*library]edge.Ticket)
	panel.profileJobs = make(map[*library]context.CancelFunc)
	panel.service, panel.catalog = nil, nil
	if node == nil {
		return
	}
	panel.address, panel.service, panel.catalog = node.Address, node.Sync, node.Catalog()
	ctx, cancel := context.WithCancel(panel.context)
	panel.stop = cancel
	panel.profileContext = ctx
	a.syncPluginPreferences()
	for _, lib := range a.libs {
		a.syncEdgeProfile(lib, false)
	}
	service := node.Sync
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			_ = service.Flush(ctx)
			a.post(func() {
				if a.edge.service == service && ctx.Err() == nil {
					a.syncPluginPreferences()
				}
			})
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
}

func (a *app) invalidateEdge(lib *library) {
	if cancel := a.edge.profileJobs[lib]; cancel != nil {
		cancel()
		delete(a.edge.profileJobs, lib)
	}
	delete(a.edge.fresh, lib)
	if a.edge.service != nil {
		a.edge.service.Invalidate(lib.id)
	}
	if a.edge.owner == lib {
		a.edge.owner, a.edge.choices = nil, nil
	}
}

func (a *app) syncEdgeProfile(lib *library, publish bool) {
	panel := &a.edge
	if panel.service == nil || lib.account == nil {
		return
	}
	if cancel := panel.profileJobs[lib]; cancel != nil {
		cancel()
	}
	ctx, cancel := context.WithCancel(panel.profileContext)
	panel.profileJobs[lib] = cancel
	service, platform := panel.service, lib.id
	ticket := service.Ticket(platform)
	profile := edge.Profile{AccountID: lib.account.ID, Name: lib.account.Name}
	go func() {
		err := service.SyncProfile(ctx, platform, ticket, profile, publish)
		a.post(func() {
			if ctx.Err() != nil || service != panel.service || service.Ticket(platform) != ticket {
				return
			}
			cancel()
			delete(panel.profileJobs, lib)
			if err != nil {
				panel.message = edgeError(err)
			} else if publish {
				panel.message = T("Platform profile synced.")
			}
		})
	}()
}

func edgeError(err error) string {
	if status, ok := errors.AsType[*edge.StatusError](err); ok {
		switch status.Code {
		case http.StatusUnauthorized:
			return T("Reconnect to renew this device’s authorization. You are still signed in locally.")
		case http.StatusForbidden:
			return T("Incorrect node password.")
		case http.StatusNotFound:
			return T("This node does not support the requested sync feature.")
		case http.StatusGone:
			return T("The cloud record was deleted and was not recreated.")
		case http.StatusTooManyRequests:
			return Tf("Too many requests. Try again in at least %d seconds.", int(status.RetryAfter.Round(time.Second)/time.Second))
		case http.StatusPreconditionFailed:
			return T("The cloud session changed and was not overwritten.")
		}
	}
	return T(err.Error())
}

func (a *app) joinEdge() {
	panel := &a.edge
	if panel.busy || panel.nodes == nil {
		return
	}
	address, err := edge.NormalizeAddress(panel.address)
	if err != nil {
		panel.message = edgeError(err)
		return
	}
	approved := ""
	if panel.keyAddress == address {
		approved = panel.keyApproval
	}
	panel.busy, panel.message = true, ""
	password, nodes, current := panel.password, panel.nodes, panel.node
	a.cancelInstall()
	go func() {
		node, err := nodes.Join(panel.context, current, address, password, approved)
		a.post(func() {
			panel.busy = false
			a.cancelInstall()
			if err != nil {
				panel.message = edgeError(err)
				if changed, ok := errors.AsType[*edge.KeyChanged](err); ok {
					panel.keyAddress, panel.keyApproval = address, changed.Key
					if current != nil && current.Address == address {
						panel.catalog = nil
					}
				}
				return
			}
			panel.password, panel.keyAddress, panel.keyApproval = "", "", ""
			a.activateNode(node)
			a.retryEdgeRestore()
		})
	}()
}

func (a *app) runEdge(action func(*edge.Sync) error, finished func()) {
	panel := &a.edge
	if panel.service == nil || panel.busy {
		return
	}
	panel.busy, panel.message = true, ""
	service := panel.service
	go func() {
		err := action(service)
		a.post(func() {
			panel.busy = false
			if err != nil {
				panel.message = edgeError(err)
			} else {
				finished()
			}
		})
	}()
}

func (a *app) retryEdgeRestore() {
	for _, lib := range a.libs {
		if source := pluginOf(lib); source != nil && source.SessionMigrator() != nil && lib.account == nil {
			a.loadAccount(lib)
		}
	}
}

func (a *app) chooseEdgeAccount(lib *library, ref string) {
	source := pluginOf(lib)
	if source == nil || lib.account != nil || lib.accountBusy || a.libOf(lib.id) != lib {
		return
	}
	migrator, service := source.SessionMigrator(), a.edge.service
	if migrator == nil || service == nil {
		return
	}
	ticket, generation := service.Ticket(lib.id), lib.accountGen
	lib.accountBusy = true
	go func() {
		account, _, err := service.Restore(context.Background(), lib.id, ticket, migrator, ref)
		a.post(func() {
			lib.accountBusy = false
			if generation != lib.accountGen || service.Ticket(lib.id) != ticket {
				return
			}
			if err != nil {
				a.edge.message = edgeError(err)
			}
			if account != nil {
				a.edge.owner, a.edge.choices = nil, nil
				lib.account = account
				a.afterAccount(lib)
			}
		})
	}()
}

func (a *app) edgeSettings(c *ui.Context, p palette) {
	panel := &a.edge
	ui.Column(c).Gap(12).Children(func() {
		if panel.message != "" {
			ui.Text(c, panel.message).TextColor(p.muted)
		}
		space, expires := "", int64(0)
		if panel.service != nil {
			space, expires = panel.service.Binding()
		}
		if space != "" {
			ui.Text(c, Tf("%s · Authorization expires %s", panel.node.Address, time.Unix(expires, 0).Format("2006-01-02")))
			if ui.Button(c.Key("edge-unbind"), T("Unlink This Device")).Disabled(panel.busy).Clicked() {
				a.runEdge((*edge.Sync).Unbind, func() {
					a.activateNode(panel.node)
					a.cancelInstall()
				})
			}
			if ui.Button(c.Key("edge-restore"), T("Check for Sessions to Restore")).Disabled(panel.busy).Clicked() {
				a.retryEdgeRestore()
			}
		}
		for _, choice := range panel.choices {
			if ui.Button(c.Key(choice.Ref), Tf("Restore %s", choice.Label)).Disabled(panel.owner.accountBusy).Clicked() {
				a.chooseEdgeAccount(panel.owner, choice.Ref)
			}
		}
		if lib := a.library; space != "" && lib != nil && lib.account != nil {
			if ui.Button(c.Key("edge-profile"), T("Sync This Platform Profile")).Disabled(panel.busy).Clicked() {
				a.syncEdgeProfile(lib, true)
			}
			if source := pluginOf(lib); source != nil && source.SessionMigrator() != nil {
				ticket, account := panel.service.Ticket(lib.id), lib.account.ID
				migrator := source.SessionMigrator()
				publish := func(recreate bool) {
					a.runEdge(func(service *edge.Sync) error {
						action := service.Publish
						if recreate {
							action = service.Recreate
						}
						if err := action(context.Background(), lib.id, account, ticket, migrator); err != nil {
							return err
						}
						return service.Flush(context.Background())
					}, func() {})
				}
				if ui.Button(c.Key("edge-publish"), T("Sync This Verified Account")).Disabled(panel.busy).Clicked() {
					publish(false)
				}
				if fresh, ok := panel.fresh[lib]; ok && fresh == ticket && ui.Button(c.Key("edge-recreate"), T("Confirm Recreating the Deleted Session")).Disabled(panel.busy).Clicked() {
					publish(true)
				}
			}
		}
		if lib := a.library; space != "" && lib != nil && panel.service.HasRemote(lib.id) {
			label := T("Delete This Source’s Cloud Session…")
			if panel.deleteFor == lib.id {
				label = T("Delete Cloud Session and Stay Signed In Locally")
			}
			if ui.Button(c.Key("edge-delete"), label).Disabled(panel.busy).Clicked() {
				if panel.deleteFor == lib.id {
					a.runEdge(func(service *edge.Sync) error { return service.Delete(context.Background(), lib.id) }, func() { panel.deleteFor = "" })
				} else {
					panel.deleteFor = lib.id
				}
			}
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
		ui.Text(c, T("Node administrators can decrypt synced sessions. Only connect to a node you trust.")).TextColor(p.muted)
		label := T("Connect to Node")
		address, _ := edge.NormalizeAddress(panel.address)
		if panel.keyApproval != "" && panel.keyAddress == address {
			label = T("Trust the New Signing Key and Reconnect")
		}
		if ui.PrimaryButton(c.Key("edge-pair"), label).Height(44).WidthPercent(100).Label(label + " MB Server").Disabled(panel.busy || panel.address == "" || panel.password == "").Clicked() {
			a.joinEdge()
		}
	})
}
