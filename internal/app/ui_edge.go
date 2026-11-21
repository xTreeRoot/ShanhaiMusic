package app

import (
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
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
	if a.edge.window != nil {
		a.edge.window.Show()
		a.edge.window.Focus()
		return
	}
	a.edge.window = mygo.NewWindow(mygo.WindowOptions{
		Title: T("Shanhai Service"), Width: 620, Height: 640, MinWidth: 540, MinHeight: 420,
		StateKey: "shanhai-service", Content: ui.View(a.edgeView),
	})
	a.edge.window.OnClosed(func() { a.edge.window = nil; a.edge.password = "" })
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
		a.edgeSettings(c, p)
	})
}
