package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"image"
	"io"
	"io/fs"
	"math"
	"os"
	"path/filepath"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

const (
	skinFile       = "background-skin.json"
	legacySkinFile = "background-skin.jpg"
)

type skinStyle struct {
	Zoom          float64 `json:"zoom"`
	Horizontal    float64 `json:"horizontal"`
	Vertical      float64 `json:"vertical"`
	Transparency  float64 `json:"transparency"`
	Blur          float64 `json:"blur"`
	Accent        string  `json:"accent,omitempty"`
	ApplyToPlayer bool    `json:"applyToPlayer,omitempty"`
}

type backgroundSkin struct {
	Style skinStyle `json:"style"`
	Image []byte    `json:"image"`

	bitmap *ui.Bitmap
	legacy bool
}

func skinClamp(value, low, high float64) float64 {
	return min(high, max(low, value))
}

func (s *backgroundSkin) loadBitmap() error {
	bitmap, err := ui.DecodeBitmap(s.Image)
	if err != nil {
		return err
	}
	s.bitmap = bitmap
	s.Style.Zoom = skinClamp(s.Style.Zoom, 1, 4)
	s.Style.Horizontal = skinClamp(s.Style.Horizontal, -1, 1)
	s.Style.Vertical = skinClamp(s.Style.Vertical, -1, 1)
	s.Style.Transparency = skinClamp(s.Style.Transparency, 0, 1)
	s.Style.Blur = skinClamp(s.Style.Blur, 0, 30)
	return nil
}

func prepareSkin(source string, style skinStyle) (*backgroundSkin, error) {
	const maxBytes = 32 << 20
	const maxPixels = 50_000_000
	file, err := os.Open(source)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxBytes {
		return nil, errors.New("skin file too large")
	}
	data = stillCover(data)
	config, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	if config.Width <= 0 || config.Height <= 0 || config.Width > maxPixels/config.Height {
		return nil, errors.New("skin dimensions too large")
	}
	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	_, encoded, err := shrinkArt(img, min(2560, max(config.Width, config.Height)))
	if err != nil {
		return nil, err
	}
	skin := &backgroundSkin{Image: encoded, Style: style}
	if err := skin.loadBitmap(); err != nil {
		return nil, err
	}
	return skin, nil
}

func readSkin(dir string) (*backgroundSkin, error) {
	skin := &backgroundSkin{Style: skinStyle{Zoom: 1}}
	data, err := os.ReadFile(filepath.Join(dir, skinFile))
	if errors.Is(err, fs.ErrNotExist) {
		skin.legacy = true
		skin.Image, err = os.ReadFile(filepath.Join(dir, legacySkinFile))
	} else if err == nil {
		err = json.Unmarshal(data, skin)
	}
	if err != nil {
		return nil, err
	}
	if err := skin.loadBitmap(); err != nil {
		return nil, err
	}
	return skin, nil
}

func writeSkin(dir string, skin backgroundSkin) error {
	encoded, err := json.Marshal(skin)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".background-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	_, writeErr := tmp.Write(encoded)
	if err := errors.Join(writeErr, tmp.Close()); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), filepath.Join(dir, skinFile))
}

func (a *app) restoreSkin() {
	if a.settings.path == "" {
		return
	}
	dir := filepath.Dir(a.settings.path)
	a.skinBusy = true
	go func() {
		skin, err := readSkin(dir)
		a.post(func() {
			a.skin, a.skinBusy = skin, false
			a.skinError = err != nil && !errors.Is(err, fs.ErrNotExist)
		})
	}()
}

var pickSkin = func(parent *mygo.Window) ([]string, error) {
	return mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent:  parent,
		Title:   T("Choose Background Image…"),
		Filters: []mygo.FileFilter{{Name: T("Images"), Extensions: []string{"jpg", "jpeg", "png", "webp"}}},
	})
}

func (a *app) chooseSkin(dark bool) {
	if a.skinBusy || a.settings.path == "" {
		return
	}
	a.skinBusy, a.skinError = true, false
	style := skinStyle{Zoom: 1, Transparency: defaultSkinTransparency(dark)}
	if a.skinDraft != nil {
		style = a.skinDraft.Style
	} else if a.skin != nil {
		style = a.skin.editableStyle(dark)
	}
	style.Zoom, style.Horizontal, style.Vertical = 1, 0, 0
	parent, pickID := a.settingsWin, a.skinPickID
	go func() {
		paths, err := pickSkin(parent)
		var draft *backgroundSkin
		if err == nil && len(paths) > 0 {
			draft, err = prepareSkin(paths[0], style)
		}
		a.post(func() {
			a.skinBusy = false
			if pickID != a.skinPickID {
				return
			}
			a.skinError = err != nil
			if draft != nil {
				a.skinDraft, a.skinEditorOpen = draft, true
			}
		})
	}()
}

func (a *app) openSkinEditor(dark bool) {
	if a.skinBusy || a.settings.path == "" {
		return
	}
	if a.skin == nil {
		a.chooseSkin(dark)
		return
	}
	draft := *a.skin
	draft.Style, draft.legacy = a.skin.editableStyle(dark), false
	a.skinDraft, a.skinEditorOpen, a.skinError = &draft, true, false
}

func (a *app) cancelSkinEditor() {
	a.skinPickID++
	a.skinDraft, a.skinEditorOpen = nil, false
}

func (a *app) saveSkin() {
	if a.skinBusy || a.skinDraft == nil || a.settings.path == "" {
		return
	}
	a.skinBusy, a.skinError = true, false
	dir, draft := filepath.Dir(a.settings.path), *a.skinDraft
	go func() {
		err := writeSkin(dir, draft)
		a.post(func() {
			a.skinBusy, a.skinError = false, err != nil
			if err == nil {
				a.skin = &draft
				a.cancelSkinEditor()
			}
		})
	}()
}

func (a *app) clearSkin() {
	if a.skinBusy || a.settings.path == "" {
		return
	}
	a.skinBusy = true
	dir := filepath.Dir(a.settings.path)
	go func() {
		var err error
		for _, name := range []string{legacySkinFile, skinFile} {
			err = os.Remove(filepath.Join(dir, name))
			if err != nil && !errors.Is(err, fs.ErrNotExist) {
				break
			}
		}
		a.post(func() {
			ok := err == nil || errors.Is(err, fs.ErrNotExist)
			if ok {
				a.skin = nil
			}
			a.skinBusy, a.skinError = false, !ok
		})
	}()
}

func (s *backgroundSkin) editableStyle(dark bool) skinStyle {
	style := s.Style
	if s.legacy {
		style.Transparency = defaultSkinTransparency(dark)
	}
	return style
}

func defaultSkinTransparency(dark bool) float64 {
	if dark {
		return 0.65
	}
	return 0.78
}

func (a *app) skinBackdrop(c *ui.Context) {
	ui.Box(c.Key("background-skin")).Absolute().Top(0).Left(0).Right(0).Bottom(0).ZIndex(-1).Background(a.pal.bg).
		AccessHidden(true).IgnoreInput(true).Children(func() {
		paintSkin(c, a.skin.bitmap, a.skin.editableStyle(a.pal.dark))
	})
}

func paintSkin(c *ui.Context, bitmap *ui.Bitmap, style skinStyle) {
	opacity := float32(1 - style.Transparency)
	if opacity == 0 {
		return
	}
	ui.Box(c.Key("skin-viewport")).Fill().Clip().AccessHidden(true).IgnoreInput(true).Children(func() {
		ui.Box(c).Fill().Opacity(opacity).Blur(float32(style.Blur)).Draw(func(painter *ui.Painter, area ui.Rect) {
			painter.ImageRegion(bitmap, ui.Rect{}, skinImageRect(bitmap, area, style), ui.ImageOptions{})
		})
	})
}

func skinImageRect(bitmap *ui.Bitmap, area ui.Rect, style skinStyle) ui.Rect {
	imageWidth, imageHeight := ui.ImageSize(bitmap)
	scale := max(area.W/imageWidth, area.H/imageHeight) * float32(style.Zoom)
	bleed := float32(math.Ceil(style.Blur * 3))
	extraWidth, extraHeight := (imageWidth*scale-area.W)/2, (imageHeight*scale-area.H)/2
	return ui.Rect{
		X: area.X - extraWidth*(1-float32(style.Horizontal)) - bleed,
		Y: area.Y - extraHeight*(1-float32(style.Vertical)) - bleed,
		W: imageWidth*scale + 2*bleed,
		H: imageHeight*scale + 2*bleed,
	}
}
