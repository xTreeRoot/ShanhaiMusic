package plugin

import (
	"errors"
	"time"

	"mygo-music/internal/music"
)

// A read-only plugin may list likes without exporting isLiked or like. Resolve
// that standard paged list, never inventing a platform API or a write action.
func (p *Plugin) likesFromList(ids []string) (map[string]bool, error) {
	if !p.exports["liked"] {
		return nil, music.ErrUnsupported
	}
	p.likesRead.Lock()
	defer p.likesRead.Unlock()
	p.likesMu.Lock()
	epoch := p.likesEpoch
	if time.Since(p.likesAt) < time.Minute {
		result := make(map[string]bool, len(ids))
		for _, id := range ids {
			result[id] = p.likesIDs[id]
		}
		p.likesMu.Unlock()
		return result, nil
	}
	p.likesMu.Unlock()
	liked := map[string]bool{}
	offset := 0
	for page := 0; page < 512; page++ {
		var value struct {
			music.Page
			NextOffset *int `json:"nextOffset"`
		}
		if err := p.call("liked", map[string]int{"offset": offset}, &value); err != nil {
			return nil, err
		}
		if value.Tracks == nil {
			return nil, errors.New("invalid liked list")
		}
		fresh := 0
		for _, track := range value.Tracks {
			if track.ID != "" && !liked[track.ID] {
				liked[track.ID] = true
				fresh++
			}
		}
		if !value.More {
			p.likesMu.Lock()
			defer p.likesMu.Unlock()
			if epoch != p.likesEpoch {
				return nil, errors.New("liked account changed")
			}
			p.likesIDs, p.likesAt = liked, time.Now()
			result := make(map[string]bool, len(ids))
			for _, id := range ids {
				result[id] = liked[id]
			}
			return result, nil
		}
		next := offset + len(value.Tracks)
		if value.NextOffset != nil {
			next = *value.NextOffset
		}
		if fresh == 0 || next <= offset || len(liked) > 25000 {
			break
		}
		offset = next
	}
	return nil, errors.New("liked list is incomplete")
}

func (p *Plugin) resetLikeSnapshot(who string) {
	p.likesMu.Lock()
	defer p.likesMu.Unlock()
	p.likesEpoch++
	p.likesWho, p.likesIDs, p.likesAt = who, nil, time.Time{}
}

func (p *Plugin) invalidateLikeSnapshot() {
	p.likesMu.Lock()
	defer p.likesMu.Unlock()
	p.likesEpoch++
	p.likesAt = time.Time{}
	p.likesIDs = nil
}
