package app

import (
	"math"
	"time"

	"github.com/egoist/mygo"
	"mygo-music/internal/lyrics"
)

const (
	menuBarDefaultWidth  = 240
	menuBarMinWidth      = 80
	menuBarMaxWidth      = 600
	menuBarHeightPoints  = 18
	menuBarIconGap       = 6
	menuBarVideoPoints   = 32
	menuBarFontSize      = 13
	menuBarScale         = 2
	menuBarScrollHold    = time.Second
	menuBarScrollSpeed   = 26.0 // Logical points per second, independent of glyph widths.
	menuBarScrollGap     = 32
	menuBarFrameInterval = 50 * time.Millisecond
)

type menuBarContent struct {
	text    string
	elapsed time.Duration
	tip     string
}

func menuBarWidth(width float64) int {
	if width <= 0 || math.IsNaN(width) {
		return menuBarDefaultWidth
	}
	return int(math.Round(max(menuBarMinWidth, min(menuBarMaxWidth, width))))
}

// menuBarOffset moves pixels rather than characters, so punctuation and mixed
// scripts keep one speed. It only depends on audio time, including paused seeks.
func menuBarOffset(textWidth, viewport int, elapsed time.Duration) int {
	if textWidth <= viewport || elapsed <= menuBarScrollHold {
		return 0
	}
	distance := textWidth + menuBarScrollGap*menuBarScale
	return int((elapsed-menuBarScrollHold).Seconds()*menuBarScrollSpeed*menuBarScale) % distance
}

// menuBarContent follows each subtitle's start, even for consecutive repeated
// lines. Keeping the full text lets the renderer preserve its exact dimensions.
func (a *app) menuBarContent() menuBarContent {
	content := menuBarContent{tip: mygo.App.Name()}
	if !a.settings.MenuBarLyrics || a.pl == nil {
		return content
	}
	track := a.pl.Current()
	if track == nil {
		return content
	}
	position := a.pl.Position()
	lines, _ := a.pl.Lyrics()
	content.text = currentLyric(lines, position)
	content.elapsed = position
	if content.text == "" {
		content.text = track.Name
	} else {
		content.elapsed -= lines[lyrics.Index(lines, position)].At
	}
	content.tip = content.text + "\n" + track.Name + " · " + track.ArtistNames()
	return content
}
