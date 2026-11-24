package app

import (
	"context"
	"errors"
	"net/http"
	"path/filepath"
	"time"

	"github.com/egoist/mygo"
	"mygo-music/internal/edge"
	"mygo-music/internal/plugin"
)

type edgePanel struct {
	devices                                 []edge.SettingsDevice
	devicesError, previewKey, settingsGroup string
	initialSettingsBackup                   bool
	preview                                 *edge.DeviceSettings
	window                                  *mygo.Window
	passwordVisible                         bool
	nodes                                   *edge.Nodes
	node                                    *edge.Node
	service                                 *edge.Sync
	catalog                                 *edge.Catalog
	address, password                       string
	keyApproval                             string
	keyAddress                              string
	message                                 string
	busy                                    bool
	activity                                string
	deleteFor                               string
	owner                                   *library
	choices                                 []edge.Candidate
	fresh                                   map[*library]edge.Ticket
	context                                 context.Context
	stop                                    context.CancelFunc
	profileContext                          context.Context
	profileJobs                             map[*library]context.CancelFunc
	overview                                *edge.Overview
	managedSource                           string
	overviewError                           string
	overviewStop                            context.CancelFunc
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
	if panel.overviewStop != nil {
		panel.overviewStop()
	}
	panel.overview, panel.managedSource, panel.overviewError, panel.overviewStop = nil, "", "", nil
	panel.devices, panel.devicesError, panel.previewKey, panel.preview = nil, "", "", nil
	panel.initialSettingsBackup = false
	panel.settingsGroup = ""
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
	a.syncDiscoveryPreferences()
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
					a.syncDiscoveryPreferences()
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
	if panel.service == nil || lib.account == nil && !publish {
		return
	}
	if cancel := panel.profileJobs[lib]; cancel != nil {
		cancel()
	}
	ctx, cancel := context.WithCancel(panel.profileContext)
	panel.profileJobs[lib] = cancel
	service, platform := panel.service, lib.id
	ticket := service.Ticket(platform)
	account := lib.account
	go func() {
		var err error
		if publish {
			account, err = lib.src.Account()
		}
		if err == nil && account != nil && ctx.Err() == nil {
			err = service.SyncProfile(ctx, platform, ticket, edge.Profile{AccountID: account.ID, Name: account.Name}, publish)
		}
		a.post(func() {
			if ctx.Err() != nil || service != panel.service || service.Ticket(platform) != ticket {
				return
			}
			cancel()
			delete(panel.profileJobs, lib)
			if err != nil {
				panel.message = edgeError(err)
			} else if publish {
				if account == nil {
					panel.message = Tf("%s · Sign in on this device first", lib.name())
				} else if service.Authorized() {
					panel.message = Tf("%s · Profile synced", lib.name())
					a.refreshEdgeOverview()
				} else {
					panel.message = edgeError(&edge.StatusError{Code: http.StatusUnauthorized})
				}
			}
		})
	}()
}

func (a *app) refreshEdgeOverview() {
	panel := &a.edge
	service := panel.service
	if service == nil || !service.Authorized() {
		return
	}
	if panel.overviewStop != nil {
		panel.overviewStop()
	}
	parent, stop := context.WithCancel(panel.profileContext)
	panel.overviewError, panel.overviewStop = "", stop
	go func() {
		ctx, cancel := context.WithTimeout(parent, 12*time.Second)
		defer cancel()
		overview, err := service.Inspect(ctx, "", "")
		devices, devicesErr := service.SettingsDevices(ctx)
		a.post(func() {
			if parent.Err() != nil || service != panel.service {
				return
			}
			stop()
			panel.overviewStop = nil
			panel.devices = devices
			panel.devicesError = ""
			if devicesErr != nil {
				panel.devicesError = edgeError(devicesErr)
			}
			if err != nil {
				panel.overviewError = edgeError(err)
				return
			}
			panel.overview = &overview
			if devicesErr == nil && !panel.initialSettingsBackup && !panel.busy {
				for _, device := range devices {
					if device.Current {
						panel.initialSettingsBackup = true
						if device.Updated == 0 {
							a.backupDeviceSettings()
						}
						break
					}
				}
			}
		})
	}()
}

func edgeError(err error) string {
	if errors.Is(err, plugin.ErrUntrustedSessionPlugin) {
		return T("Install the plugin version verified by this node before syncing sign-ins.")
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return T("The node did not respond in time. Try again.")
	}
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
	panel.busy, panel.activity, panel.message = true, T("Connecting to the node…"), ""
	password, nodes, current := panel.password, panel.nodes, panel.node
	a.cancelInstall()
	go func() {
		node, err := nodes.Join(panel.context, current, address, password, approved)
		a.post(func() {
			panel.busy, panel.activity = false, ""
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
			panel.message = T("Node connected. This device is authorized to sync.")
			a.retryEdgeRestore()
			a.refreshEdgeOverview()
		})
	}()
}

func (a *app) runEdge(activity string, action func(*edge.Sync) error, finished func()) {
	panel := &a.edge
	if panel.service == nil || panel.busy {
		return
	}
	panel.busy, panel.activity, panel.message = true, T(activity), ""
	service := panel.service
	go func() {
		err := action(service)
		a.post(func() {
			panel.busy, panel.activity = false, ""
			if service != panel.service {
				return
			}
			if err != nil {
				panel.message = edgeError(err)
			} else {
				finished()
			}
			a.refreshEdgeOverview()
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
	panel := &a.edge
	if source == nil || lib.account != nil || lib.accountBusy || a.libOf(lib.id) != lib || panel.busy {
		return
	}
	migrator, service := source.VerifiedSessionMigrator(panel.profileContext, panel.catalog), panel.service
	if migrator == nil || service == nil || !service.Authorized() {
		return
	}
	ticket, generation := service.Ticket(lib.id), lib.accountGen
	lib.accountBusy = true
	panel.busy, panel.activity, panel.message = true, T("Verifying cloud sign-in…"), ""
	ctx := panel.profileContext
	go func() {
		account, choices, err := service.Restore(ctx, lib.id, ticket, migrator, ref)
		a.post(func() {
			lib.accountBusy = false
			panel.busy, panel.activity = false, ""
			if generation != lib.accountGen || service != panel.service || service.Ticket(lib.id) != ticket {
				return
			}
			panel.owner, panel.choices = nil, nil
			switch {
			case err != nil:
				panel.message = edgeError(err)
			case account != nil:
				panel.message = Tf("Signed in as %s on this device.", account.Name)
				lib.account = account
				lib.accountAsked = true
				a.afterAccount(lib)
			case len(choices) > 1:
				panel.owner, panel.choices = lib, choices
				panel.message = T("Choose which cloud account to restore.")
			default:
				panel.message = T("No sign-in was restored. If the session expired or you signed out here, sign in again on this device.")
			}
			a.refreshEdgeOverview()
		})
	}()
}

func (a *app) publishEdgeSession(lib *library, source *plugin.Plugin, recreate bool) {
	panel := &a.edge
	migrator := source.VerifiedSessionMigrator(panel.profileContext, panel.catalog)
	if migrator == nil {
		panel.message = T("Install the plugin version verified by this node before syncing sign-ins.")
		return
	}
	ctx, ticket, account := panel.profileContext, panel.service.Ticket(lib.id), lib.account.ID
	a.runEdge("Syncing sign-in session…", func(service *edge.Sync) error {
		action := service.PublishCurrent
		if recreate {
			action = service.Recreate
		}
		if err := action(ctx, lib.id, account, ticket, migrator); err != nil {
			return err
		}
		if err := service.Flush(ctx); err != nil {
			return err
		}
		if !service.Authorized() {
			return &edge.StatusError{Code: http.StatusUnauthorized}
		}
		if ctx.Err() != nil || service.Ticket(lib.id) != ticket {
			return context.Canceled
		}
		return nil
	}, func() {
		panel.message = T("Sign-in session saved to the node. Compatible devices can try to restore it.")
	})
}
