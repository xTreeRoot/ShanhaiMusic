package app

import (
	"github.com/egoist/mygo/ui"
)

// openPlugins opens the host's plugin manager without changing the playing source.
func (a *app) openPlugins() {
	a.showMain()
	a.installer.main = true
	a.open(route{page: pagePlugins})
}

func (a *app) pluginsPage(c *ui.Context) {
	p := a.pal
	top, bottom := a.insets(c)
	ui.Scroll(c.Key("plugins")).TrackScroll(&a.pageScroll).Grow(1).
		Padding(top+pageGap, pagePad, bottom+48, pagePad).Gap(12).
		ScrollbarInsets(top, 0, bottom, 0).Children(func() {
		a.startMark(c)
		defer a.endMark(c)
		in := &a.installer
		details := a.installedLibrary(in.detailsID)
		ui.Row(c).AlignItems(ui.Center).Gap(10).Children(func() {
			if details != nil {
				iconButton(c.Key("back-to-plugins"), iconBack, 16, p.muted, T("Back to Plugins")).
					OnClick(func() { in.detailsID = "" })
				ui.Text(c, details.name()).FontSize(24).FontWeight(700).Grow(1)
				return
			}
			ui.Text(c, T("Plugins")).FontSize(26).FontWeight(700).Grow(1)
			tab := 0
			if a.route.id == communityTab {
				tab = 1
			}
			if segmented(c.Key("tab"), &tab, T("Installed"), T("Community")) {
				a.choosePluginsTab(tab == 1)
			}
			a.installMenu(c, p, true)
		})
		if details != nil {
			a.pluginDetails(c, details, p)
			return
		}
		if a.route.id == communityTab {
			a.communityPlugins(c, p)
			return
		}

		a.settingsGroup(c, p, settingsSection{id: "plugins-list", build: func(row settingsRow, block settingsBlock) {
			a.sourceSettings(c, row, block, p)
		}}, "", "")
	})
}
