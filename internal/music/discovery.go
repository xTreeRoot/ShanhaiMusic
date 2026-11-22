package music

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
)

// DiscoverySection declares an independent loader. Page and Builtin are opaque
// plugin routes; the host never needs to fetch home to discover these sections.
type DiscoverySection struct {
	ID             string   `json:"id"`
	Title          string   `json:"title"`
	Page           string   `json:"page,omitempty"`
	Builtin        string   `json:"builtin,omitempty"`
	DefaultEnabled *bool    `json:"defaultEnabled,omitempty"`
	Categories     []string `json:"categories,omitempty"`
}

func (s DiscoverySection) EnabledByDefault() bool {
	return s.DefaultEnabled == nil || *s.DefaultEnabled
}
func ValidateDiscovery(sections []DiscoverySection) error {
	if len(sections) > 128 {
		return fmt.Errorf("too many discovery sections")
	}
	ids := map[string]bool{}
	pattern := regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`)
	for _, s := range sections {
		if !pattern.MatchString(s.ID) || strings.Contains(s.ID, "..") || ids[s.ID] || strings.TrimSpace(s.Title) == "" || len(s.Title) > 240 || (s.Page == "") == (s.Builtin == "") {
			return fmt.Errorf("invalid discovery section %q", s.ID)
		}
		ids[s.ID] = true
		if s.Page != "" && (len(s.Page) > 4096 || strings.TrimSpace(s.Page) == "") {
			return fmt.Errorf("invalid discovery page")
		}
		if s.Builtin != "" && !slices.Contains([]string{"playlists", "liked", "recent", "savedAlbums", "shows", "stations"}, s.Builtin) {
			return fmt.Errorf("invalid discovery collection")
		}
		if len(s.Categories) > 64 {
			return fmt.Errorf("too many discovery categories")
		}
		categories := map[string]bool{}
		for _, category := range s.Categories {
			if category == "" || len(category) > 4096 || categories[category] {
				return fmt.Errorf("invalid discovery category")
			}
			categories[category] = true
		}
	}
	return nil
}
