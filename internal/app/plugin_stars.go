package app

import (
	"context"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/plugin"
)

var fetchStarsRepositories = plugin.StarsRepositories
var prepareStarsPlugin = plugin.FetchContext

type starsImport struct {
	open, reviewed, done bool
	address, message     string
	cancel               context.CancelFunc
	entries              []starsEntry
}

type starsEntry struct {
	repo    string
	pending *plugin.Pending
	issue   string
}

func discardStars(entries []starsEntry) {
	for _, entry := range entries {
		if entry.pending != nil {
			entry.pending.Discard()
		}
	}
}

func (a *app) closeStars() {
	in := &a.installer
	if stars := in.stars; stars != nil {
		in.stars = nil
		if stars.cancel != nil {
			stars.cancel()
		}
		if len(stars.entries) != 0 {
			go discardStars(stars.entries)
		}
	}
}

func (a *app) openStars() {
	in := &a.installer
	if in.busy || in.batch || in.open || len(in.updating) != 0 {
		return
	}
	in.stars = &starsImport{open: true, address: in.starsAddress}
}

func (a *app) scanStars() {
	in := &a.installer
	stars := in.stars
	if stars == nil || in.busy || in.batch || len(in.updating) != 0 {
		return
	}
	address, directory := stars.address, pluginsDir()
	ctx, cancel := context.WithCancel(context.Background())
	stars.cancel = cancel
	stars.message = T("Reading plugin catalog…")
	in.busy = true
	type owner struct{ id, repo string }
	blocked := map[string]owner{}
	for _, lib := range a.allLibs {
		var repo string
		if current := pluginOf(lib); current != nil {
			if origin := current.Origin(); origin != nil {
				repo = origin.Repo
			}
		}
		blocked[strings.ToLower(lib.id)] = owner{lib.id, repo}
	}
	go func() {
		defer cancel()
		repos, err := fetchStarsRepositories(ctx, address)
		var entries []starsEntry
		if err == nil && len(repos) > 0 && ctx.Err() == nil {
			a.post(func() {
				if in.stars == stars && stars.open {
					stars.message = Tf("Found %d repositories. Checking plugin packages…", len(repos))
				}
			})
			entries = reviewStarsPackages(ctx, repos, directory)
			for index, entry := range entries {
				pending := entry.pending
				if pending == nil {
					continue
				}
				id := strings.ToLower(pending.Manifest.ID)
				if source, exists := blocked[id]; exists && (source.id != pending.Manifest.ID || !strings.EqualFold(source.repo, entry.repo)) {
					entries[index].issue = T("A plugin from another source already uses this ID")
					entries[index].pending = nil
					pending.Discard()
				} else {
					blocked[id] = owner{pending.Manifest.ID, entry.repo}
				}
			}
		}
		a.post(func() {
			in.busy = false
			if in.stars != stars || !stars.open {
				go discardStars(entries)
				return
			}
			stars.cancel = nil
			if err != nil {
				stars.message = Tf("Cannot read the plugin catalog: %s", err.Error())
				return
			}
			in.starsAddress = address
			stars.entries, stars.reviewed, stars.message = entries, true, ""
			if len(entries) == 0 {
				stars.message = T("No plugin repositories found in this list")
			}
		})
	}()
}

func reviewStarsPackages(ctx context.Context, repos []string, directory string) []starsEntry {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	entries := make([]starsEntry, len(repos))
	workers := min(4, len(repos))
	var group sync.WaitGroup
	for worker := range workers {
		group.Go(func() {
			for index := worker; index < len(repos); index += workers {
				entry := starsEntry{repo: repos[index]}
				err := ctx.Err()
				if err == nil {
					request, stop := context.WithTimeout(ctx, 25*time.Second)
					entry.pending, err = prepareStarsPlugin(request, entry.repo, directory)
					stop()
				}
				if err != nil {
					entry.issue = installError(err)
				}
				entries[index] = entry
			}
		})
	}
	group.Wait()
	return entries
}

func (a *app) applyStars() {
	in := &a.installer
	stars := in.stars
	if stars == nil || !stars.reviewed || stars.done || in.busy || in.batch || len(in.updating) != 0 {
		return
	}
	entries, directory := stars.entries, pluginsDir()
	stars.entries, stars.message = nil, T("Importing plugins…")
	in.busy, in.batch = true, true
	go func() {
		var installed []string
		var issues []starsEntry
		for _, entry := range entries {
			if entry.pending == nil {
				issues = append(issues, entry)
				continue
			}
			if err := entry.pending.Install(directory); err != nil {
				issues = append(issues, starsEntry{repo: entry.repo, issue: installError(err)})
			} else {
				installed = append(installed, entry.pending.Manifest.ID)
			}
		}
		a.post(func() {
			in.busy, in.batch = false, false
			count := 0
			for _, id := range installed {
				if err := a.pluginInstalled(id); err != nil {
					issues = append(issues, starsEntry{repo: id, issue: installError(err)})
				} else {
					count++
				}
			}
			stars.entries, stars.done = issues, true
			stars.message = Tf("Imported or replaced %d plugins · %d failed or skipped", count, len(issues))
			in.batchStatus = stars.message
		})
	}()
}

func (a *app) starsDialog(c *ui.Context, p palette) {
	in := &a.installer
	stars := in.stars
	if stars == nil {
		return
	}
	if !stars.open {
		a.closeStars()
		return
	}
	ui.DialogBase(c.Key("stars-import-dialog"), &stars.open, func(backdrop, panel ui.Element) {
		backdrop.Background(ui.RGBA(0, 0, 0, 0.35))
		panel.Width(450).Padding(24).Radius(18).Gap(10).Background(p.bg).Border(1, p.hairline)
		ui.Text(c, T("Import / Replace Plugins")).FontSize(17).FontWeight(700)
		action := T("Read List")
		ready := false
		switch {
		case !stars.reviewed:
			label := T("GitHub repository or catalog page URL")
			field := ui.TextInput(c.Key("stars-url"), &stars.address).Label(label).Placeholder(label).AutoFocus().Disabled(in.busy)
			ready = !in.busy && strings.TrimSpace(stars.address) != ""
			if ready && field.Submitted() {
				a.scanStars()
				ready = false
			}
		default:
			action = T("Confirm Import / Replace")
			ready = !in.busy && !stars.done && slices.ContainsFunc(stars.entries, func(entry starsEntry) bool { return entry.pending != nil })
			if len(stars.entries) > 0 {
				ui.Scroll(c.Key("stars-review")).Height(float32(min(230, 64*len(stars.entries)))).Gap(8).Children(func() {
					for _, entry := range stars.entries {
						ui.Column(c.Key(entry.repo)).Gap(2).Children(func() {
							if pending := entry.pending; pending != nil {
								ui.Text(c, pending.Manifest.Name+" · "+entry.repo).FontSize(13).FontWeight(600)
								network := pending.Manifest.Permissions.Network
								if len(network) == 0 {
									ui.Text(c, T("Asks for no network")).FontSize(12).TextColor(p.muted)
								} else {
									ui.Text(c, Tf("Can reach %s", strings.Join(network, ", "))).FontSize(12).TextColor(p.muted)
								}
								if programs := pending.Manifest.Permissions.Process; len(programs) > 0 {
									ui.Text(c, programsWarning(programs)).FontSize(12).TextColor(p.muted)
								}
							} else {
								ui.Text(c, entry.repo).FontSize(13).FontWeight(600)
								ui.Text(c, entry.issue).FontSize(12).TextColor(p.accent)
							}
						})
					}
				})
			}
		}
		if stars.message != "" {
			ui.Text(c, stars.message).FontSize(13).TextColor(p.muted)
		}
		ui.Row(c).Gap(8).Justify(ui.End).Children(func() {
			if ui.Button(c.Key("close-stars"), T("Close")).Clicked() {
				stars.open = false
			}
			if !stars.done && ui.Button(c.Key("confirm-stars"), action).Disabled(!ready).Clicked() {
				if stars.reviewed {
					a.applyStars()
				} else {
					a.scanStars()
				}
			}
		})
	})
}
