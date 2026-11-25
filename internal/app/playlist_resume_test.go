package app

import (
	"os"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

func TestPlaylistResumeReopensSavedTrackAndPosition(t *testing.T) {
	mygo.App.SetPath(mygo.PathUserData, t.TempDir())
	t.Cleanup(func() { mygo.App.SetPath(mygo.PathUserData, "") })
	a := editTestApp()
	tt := ui.NewTester(a.view, 1200, 800)
	settled(tt)
	if err := tt.Click("宝船"); err != nil {
		t.Fatal(err)
	}
	a.pl.Seek(23 * time.Second)
	a.saveSession()

	reopened := editTestApp()
	reopened.loadPlaylistResumes()
	view := ui.NewTester(reopened.view, 1200, 800)
	settled(view)
	name := T("Last played")
	if err := view.Click(name); err != nil {
		t.Fatal(err)
	}
	if track := reopened.pl.Current(); track == nil || track.ID != "2" || reopened.pl.Position() != 23*time.Second {
		t.Fatalf("resume did not restore track and position: %+v at %v", track, reopened.pl.Position())
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "playlist-resume.png", view.Image())
	}
}
