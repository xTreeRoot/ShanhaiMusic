package app

import (
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"mygo-music/internal/i18n"
)

func TestBackgroundSkinEditsSaveCancelAndReopen(t *testing.T) {
	before := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	t.Cleanup(func() { i18n.SetLanguage(before) })
	directory := t.TempDir()
	input := filepath.Join(directory, "background.png")
	file, err := os.Create(input)
	if err != nil {
		t.Fatal(err)
	}
	canvas := image.NewRGBA(image.Rect(0, 0, 480, 270))
	for row := range canvas.Bounds().Dy() {
		for column := range canvas.Bounds().Dx() {
			canvas.SetRGBA(column, row, color.RGBA{R: uint8(column / 2), G: uint8(row / 2), B: 175, A: 255})
		}
	}
	if err := png.Encode(file, canvas); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	originalPick := pickSkin
	t.Cleanup(func() { pickSkin = originalPick })
	pickSkin = func(*mygo.Window) ([]string, error) { return []string{input}, nil }
	a := newTestApp()
	a.settings.path = filepath.Join(directory, "settings.json")
	callbacks := posts(a)
	view := ui.NewTester(a.settingsView, 780, 640)
	view.SetDark(true)
	view.Frame()
	click := func(label string) {
		t.Helper()
		if err := view.Click(label); err != nil {
			t.Fatal(err)
		}
	}
	click(T("Choose Background Image…"))
	runPost(t, callbacks)
	view.Frame()
	if a.skin != nil || a.skinDraft == nil || a.skinError {
		t.Fatal("initial import did not open a draft without applying it")
	}
	click(T("Cancel"))
	if _, err := os.Stat(filepath.Join(directory, skinFile)); !os.IsNotExist(err) {
		t.Fatalf("canceled import wrote the skin: %v", err)
	}
	click(T("Choose Background Image…"))
	runPost(t, callbacks)
	view.Frame()
	adjust := func(label string) {
		t.Helper()
		bounds, ok := view.Find(label)
		if !ok {
			t.Fatalf("slider label %q not found", label)
		}
		view.ClickAt(bounds.X+bounds.W+70, bounds.Y+bounds.H/2)
		if !view.Focused(label) {
			t.Fatalf("slider %q did not take focus", label)
		}
	}
	adjust(T("Image Zoom"))
	preview, ok := view.Find(T("Background Preview"))
	if !ok {
		t.Fatal("background preview not accessible")
	}
	x, y := preview.X+preview.W/2, preview.Y+preview.H/2
	view.Press(x, y)
	view.Move(x+36, y-24)
	view.Release(x+36, y-24)
	view.Frame()
	click(Tf("Theme Color: %s", T("Peach")))
	adjust(T("Transparency"))
	adjust(T("Blur"))
	click(T("Apply to the Music Player Background"))
	want := a.skinDraft.Style
	if want.Zoom <= 1 || want.Horizontal <= 0 || want.Vertical >= 0 || want.Transparency <= 0 || want.Blur <= 0 || want.Accent != "peach" || !want.ApplyToPlayer {
		t.Fatalf("editor did not capture user inputs: %+v", want)
	}
	if a.skin != nil {
		t.Fatal("editing affected the applied skin before saving")
	}
	compact := ui.NewTester(a.settingsView, 640, 420)
	compact.Frame()
	if frames := os.Getenv("MM_FRAMES"); frames != "" {
		saveFrame(t, frames, "desktop-background-editor.png", view.Image())
		saveFrame(t, frames, "desktop-background-editor-compact.png", compact.Image())
	}
	if err := compact.Click(T("Apply to the Music Player Background")); err != nil {
		t.Fatalf("player toggle is hidden at the minimum window size: %v", err)
	}
	if a.skinDraft.Style.ApplyToPlayer {
		t.Fatal("compact player toggle did not edit the draft")
	}
	if err := compact.Click(T("Apply to the Music Player Background")); err != nil {
		t.Fatal(err)
	}
	view.Frame()
	click(T("Save"))
	runPost(t, callbacks)
	view.Frame()
	if a.skin == nil || a.skin.Style != want || a.skinError || a.skinDraft != nil {
		t.Fatal("valid skin did not load")
	}
	imported := a.skin
	click(T("Customize…"))
	click(Tf("Theme Color: %s", T("Orange")))
	view.Key(0, ui.KeyEscape)
	view.Frame()
	if a.skinDraft != nil || a.skin.Style != want {
		t.Fatal("dismissing the editor applied its draft")
	}
	click(T("Customize…"))
	if err := os.WriteFile(input, []byte("not an image"), 0o600); err != nil {
		t.Fatal(err)
	}
	click(T("Replace Image…"))
	runPost(t, callbacks)
	if a.skin != imported || a.skinDraft == nil || a.skinDraft.Style != want || !a.skinError {
		t.Fatal("invalid input replaced the current skin or did not report failure")
	}
	reopened := newTestApp()
	reopened.settings.path = a.settings.path
	reopenedCallbacks := posts(reopened)
	reopened.restoreSkin()
	runPost(t, reopenedCallbacks)
	if reopened.skin == nil || reopened.skin.Style != want || reopened.skinError {
		t.Fatal("reopen did not restore the valid skin")
	}
	main := ui.NewTester(reopened.view, 1200, 800)
	main.Frame()
	if reopened.pal.accent != skinPalette(paletteFor(ui.LightTheme()), want.Accent).accent {
		t.Fatal("saved theme color was not applied")
	}
	if err := main.Click(T("Video Mode")); err != nil {
		t.Fatal(err)
	}
	if reopened.settings.PlaybackMode != playbackVideo {
		t.Fatal("skin intercepted the mode switch")
	}
	if directory := os.Getenv("MM_FRAMES"); directory != "" {
		saveFrame(t, directory, "desktop-background-skin.png", main.Image())
	}
	settings := ui.NewTester(reopened.settingsView, 780, 640)
	settings.Frame()
	if err := settings.Click(T("Restore Default Background")); err != nil {
		t.Fatal(err)
	}
	runPost(t, reopenedCallbacks)
	reopened.restoreSkin()
	runPost(t, reopenedCallbacks)
	if reopened.skin != nil || reopened.skinError {
		t.Fatal("cleared skin returned after reloading")
	}
}

func TestBackgroundSkinRestoresLegacyImage(t *testing.T) {
	directory := t.TempDir()
	_, encoded, err := shrinkArt(image.NewRGBA(image.Rect(0, 0, 20, 20)), 20)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, legacySkinFile), encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	a := newTestApp()
	a.settings.path = filepath.Join(directory, "settings.json")
	callbacks := posts(a)
	a.restoreSkin()
	runPost(t, callbacks)
	if a.skin == nil || !a.skin.legacy || a.skin.editableStyle(true).Transparency != 0.65 {
		t.Fatal("old image did not keep its original appearance")
	}
	a.openSkinEditor(true)
	if a.skinDraft == nil || a.skinDraft.legacy || a.skinDraft.Style.Zoom != 1 || a.skinDraft.Style.Transparency != 0.65 {
		t.Fatal("old image could not be edited without changing its appearance")
	}
	a.saveSkin()
	runPost(t, callbacks)
	a.clearSkin()
	runPost(t, callbacks)
	a.restoreSkin()
	runPost(t, callbacks)
	if a.skin != nil || a.skinError {
		t.Fatal("restoring defaults left an old image behind")
	}
}

func TestBackgroundSkinIgnoresPickerAfterSettingsCloses(t *testing.T) {
	directory := t.TempDir()
	input := filepath.Join(directory, "background.jpg")
	_, encoded, err := shrinkArt(image.NewRGBA(image.Rect(0, 0, 20, 20)), 20)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(input, encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	original := pickSkin
	t.Cleanup(func() { pickSkin = original })
	picked := make(chan struct{})
	pickSkin = func(*mygo.Window) ([]string, error) {
		<-picked
		return []string{input}, nil
	}
	a := newTestApp()
	a.settings.path = filepath.Join(directory, "settings.json")
	callbacks := posts(a)
	a.openSkinEditor(false)
	a.cancelSkinEditor()
	close(picked)
	runPost(t, callbacks)
	if a.skin != nil || a.skinDraft != nil || a.skinEditorOpen || a.skinBusy || a.skinError {
		t.Fatal("a late picker result reopened a dismissed editor")
	}
}
