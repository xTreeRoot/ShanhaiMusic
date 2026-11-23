package app

import (
	"fmt"

	"github.com/egoist/mygo/ui"
)

var skinAccents = []struct {
	id, title string
	color     ui.Color
	ink       ui.Color
}{
	{"", "Default Theme Color", ui.Color{}, ui.Color{}},
	{"peach", "Peach", ui.Hex("#e0a691"), ui.Hex("#1d1d1f")},
	{"rose", "Rose", ui.Hex("#c5526b"), ui.Hex("#151517")},
	{"silver", "Silver", ui.Hex("#6e7887"), ui.Hex("#151517")},
	{"forest", "Forest", ui.Hex("#337a63"), white},
	{"brick", "Brick", ui.Hex("#b03d30"), white},
	{"red", "Crimson", ui.Hex("#db303b"), ui.Hex("#151517")},
	{"orange", "Orange", ui.Hex("#de5e29"), ui.Hex("#151517")},
}

func skinPalette(p palette, id string) palette {
	for _, choice := range skinAccents[1:] {
		if choice.id == id {
			p.accent, p.onAccent = choice.color, choice.ink
			break
		}
	}
	return p
}

func (a *app) appPalette(theme *ui.Theme) palette {
	p := paletteFor(theme)
	if a.skin != nil {
		p = skinPalette(p, a.skin.Style.Accent)
	}
	return p
}

func (a *app) skinEditorView(c *ui.Context, p palette) {
	if a.skinDraft == nil {
		return
	}
	ui.DialogBase(c.Key("skin-editor"), &a.skinEditorOpen, func(backdrop, panel ui.Element) {
		if panel.Dismissed() {
			a.cancelSkinEditor()
			return
		}
		draft := a.skinDraft
		style := &draft.Style
		selected := skinPalette(paletteFor(c.Theme()), style.Accent)
		_, height := c.Size()
		panelHeight := max(150, min(550, height-28))
		padding, gap, controlGap := float32(17), float32(12), float32(9)
		previewHeight := min(206, max(84, panelHeight-344))
		if panelHeight < 460 {
			padding, gap, controlGap = 12, 8, 6
		}
		backdrop.Background(ui.RGBA(0, 0, 0, 0.4))
		panel.Label(T("Custom Skin")).Width(440).Height(panelHeight).Padding(padding, 22).
			Gap(gap).Radius(16).Background(p.sidebar)
		ui.Row(c.Key("heading")).AlignItems(ui.Center).Children(func() {
			ui.Box(c).Size(30, 30)
			ui.Text(c, T("Custom Skin")).FontSize(17).Bold().TextAlign(ui.Center).Grow(1)
			iconButton(c.Key("close"), iconX, 16, p.muted, T("Close")).Disabled(a.skinBusy).OnClick(a.cancelSkinEditor)
		})
		ui.Scroll(c.Key("skin-controls")).Grow(1).MinHeight(0).Gap(controlGap).Children(func() {
			preview := ui.Box(c.Key("skin-preview")).Height(previewHeight).Shrink(0).Clip().Radius(10).
				Background(p.bg).Label(T("Background Preview")).Cursor(ui.CursorGrab)
			deltaX, deltaY, dragged := preview.Dragged()
			if dragged && !a.skinBusy {
				preview.Cursor(ui.CursorGrabbing)
				bounds := preview.Bounds()
				style.Horizontal = skinClamp(style.Horizontal+float64(deltaX/max(1, bounds.W/2)), -1, 1)
				style.Vertical = skinClamp(style.Vertical+float64(deltaY/max(1, bounds.H/2)), -1, 1)
			}
			preview.Children(func() { paintSkin(c, draft.bitmap, *style) })
			ui.Row(c.Key("image-actions")).AlignItems(ui.Center).Gap(6).Children(func() {
				ui.Text(c, T("Drag the preview to move the image")).FontSize(12).TextColor(p.muted).Grow(1)
				ui.Button(c, T("Replace Image…")).Disabled(a.skinBusy).OnClick(func() { a.chooseSkin(p.dark) })
			})
			skinSlider(c.Key("zoom"), p, selected.accent, T("Image Zoom"), &style.Zoom, 1, 4, 0.05, fmt.Sprintf("%.2f×", style.Zoom), a.skinBusy)
			ui.Row(c.Key("colors")).Height(30).Gap(7).AlignItems(ui.Center).Children(func() {
				ui.Text(c, T("Theme Color")).Width(96).FontSize(13)
				for _, choice := range skinAccents {
					color := choice.color
					if choice.id == "" {
						color = paletteFor(c.Theme()).accent
					}
					active := choice.id == style.Accent
					swatch := ui.ButtonBase(c.Key("color-"+choice.id)).Label(Tf("Theme Color: %s", T(choice.title))).AccessSelected(active).
						Size(27, 27).Center().Radius(14).Disabled(a.skinBusy).OnClick(func() { style.Accent = choice.id })
					if active || swatch.Focused() {
						swatch.Border(1, p.text)
					}
					swatch.Children(func() { ui.Box(c).Size(20, 20).Radius(10).Background(color).IgnoreInput(true).AccessHidden(true) })
				}
			})
			skinSlider(c.Key("transparency"), p, selected.accent, T("Transparency"), &style.Transparency, 0, 1, 0.01, fmt.Sprintf("%.0f%%", style.Transparency*100), a.skinBusy)
			skinSlider(c.Key("blur"), p, selected.accent, T("Blur"), &style.Blur, 0, 30, 1, fmt.Sprintf("%.0f", style.Blur), a.skinBusy)
			ui.Checkbox(c.Key("use-player-skin"), &style.ApplyToPlayer, T("Apply to the Music Player Background")).Disabled(a.skinBusy)
			if a.skinError {
				ui.Text(c, T("Could not update the background image")).TextColor(paletteFor(c.Theme()).accent).FontSize(12)
			}
		})
		ui.Row(c.Key("skin-actions")).Gap(10).Children(func() {
			ui.Button(c.Key("cancel"), T("Cancel")).Grow(1).Height(36).Radius(18).Disabled(a.skinBusy).OnClick(a.cancelSkinEditor)
			save := ui.Button(c.Key("save"), T("Save")).Grow(1).Height(36).Radius(18).TextColor(selected.onAccent).
				Disabled(a.skinBusy).OnClick(a.saveSkin)
			color := selected.accent
			if save.Hovered() {
				color = color.MixOklab(p.text, 0.12)
			}
			save.Background(color)
		})
	})
}

func skinSlider(c *ui.Context, p palette, accent ui.Color, label string, value *float64, lo, hi, step float64, display string, disabled bool) {
	ui.Row(c).Height(24).Gap(7).AlignItems(ui.Center).Children(func() {
		ui.Text(c, label).Width(96).FontSize(13)
		slider := ui.SliderBase(c, value, lo, hi).Step(step).Grow(1).MinWidth(20).Height(24).PaddingX(7).Label(label).Disabled(disabled)
		progress, focused := float32((*value-lo)/(hi-lo)), slider.Focused()
		slider.Draw(func(painter *ui.Painter, r ui.Rect) {
			center := r.Y + r.H/2
			width := r.W - 14
			painter.Fill(ui.Rect{X: r.X + 7, Y: center - 3, W: width, H: 6}, p.faint, 3)
			painter.Fill(ui.Rect{X: r.X + 7, Y: center - 3, W: width * progress, H: 6}, accent, 3)
			thumb := r.X + width*progress
			if focused {
				painter.Fill(ui.Rect{X: thumb - 2, Y: center - 9, W: 18, H: 18}, accent, 9)
			}
			painter.Fill(ui.Rect{X: thumb, Y: center - 7, W: 14, H: 14}, p.text, 7)
		})
		ui.Text(c, display).Width(45).TextAlign(ui.End).FontSize(12).TextColor(p.muted)
	})
}
