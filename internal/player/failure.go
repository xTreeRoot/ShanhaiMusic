package player

import "mygo-music/internal/music"

func (p *Player) markFailed(track music.Track) {
	if p.failedTracks == nil {
		p.failedTracks = make(map[problemKey]bool)
	}
	p.failedTracks[problemKey{track.Source, track.ID}] = true
}

// nextPlayable scans the actual shuffled order without deleting queue items.
// Repeat-one never traps failure recovery on the failed current track.
func (p *Player) nextPlayable(at, dir int, wrap bool) (int, bool) {
	if dir == stay || len(p.order) == 0 {
		return 0, false
	}
	for steps := 0; steps < len(p.order); steps++ {
		at += dir
		if at < 0 || at >= len(p.order) {
			if !wrap {
				break
			}
			at = (at + len(p.order)) % len(p.order)
		}
		t := p.queue[p.order[at]]
		if !t.Unavailable && !p.failedTracks[problemKey{t.Source, t.ID}] {
			return at, true
		}
	}
	return 0, false
}
