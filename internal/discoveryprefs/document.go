// Package discoveryprefs stores source-independent discovery section choices.
package discoveryprefs

import (
	"maps"
	"regexp"
	"slices"
	"strings"

	"mygo-music/internal/pluginprefs"
)

// Document uses a separate app/discovery node record. Old clients cannot erase
// section choices while updating their app/plugins preferences.
type Document struct {
	Version int                             `json:"version"`
	Plugins map[string]pluginprefs.Document `json:"plugins"`
}

type Section struct {
	ID             string
	DefaultEnabled bool
}
type Selection struct {
	Order   []string
	Enabled map[string]bool
}

var validID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`)

func Empty() Document { return Document{Version: 1, Plugins: map[string]pluginprefs.Document{}} }
func (doc Document) Validate() error {
	if doc.Version != 1 || doc.Plugins == nil || len(doc.Plugins) > 512 {
		return pluginprefs.ErrInvalid
	}
	total := 0
	for id, prefs := range doc.Plugins {
		if !validID.MatchString(id) || strings.Contains(id, "..") || prefs.Validate() != nil || len(prefs.Names) > 0 || len(prefs.Enabled) > 128 || prefs.Order != nil && len(prefs.Order.Value) > 128 {
			return pluginprefs.ErrInvalid
		}
		total += len(prefs.Enabled)
	}
	if total > 4096 {
		return pluginprefs.ErrInvalid
	}
	return nil
}
func (doc Document) IsEmpty() bool { return len(doc.Plugins) == 0 }
func (doc Document) Equal(other Document) bool {
	return doc.Version == other.Version && maps.EqualFunc(doc.Plugins, other.Plugins, pluginprefs.Document.Equal)
}
func (doc Document) Merge(other Document) (Document, error) {
	if doc.Validate() != nil || other.Validate() != nil {
		return Empty(), pluginprefs.ErrInvalid
	}
	result := Empty()
	result.Plugins = maps.Clone(doc.Plugins)
	for id, right := range other.Plugins {
		left, ok := result.Plugins[id]
		if !ok {
			left = pluginprefs.Empty()
		}
		merged, err := left.Merge(right)
		if err != nil {
			return Empty(), err
		}
		result.Plugins[id] = merged
	}
	return result, result.Validate()
}

// Resolve returns only declared IDs. After an explicit configuration, a newly
// introduced section stays off until the listener enables it or restores defaults.
func (doc Document) Resolve(plugin string, sections []Section) Selection {
	prefs, ok := doc.Plugins[plugin]
	configured := ok && prefs.Order != nil
	result := Selection{Order: []string{}, Enabled: map[string]bool{}}
	declared := map[string]bool{}
	for _, s := range sections {
		declared[s.ID] = true
		result.Enabled[s.ID] = s.DefaultEnabled && !configured
		if entry, ok := prefs.Enabled[s.ID]; ok {
			result.Enabled[s.ID] = entry.Value
		}
	}
	if prefs.Order != nil {
		for _, id := range prefs.Order.Value {
			if declared[id] {
				result.Order = append(result.Order, id)
				delete(declared, id)
			}
		}
	}
	for _, s := range sections {
		if declared[s.ID] {
			result.Order = append(result.Order, s.ID)
			delete(declared, s.ID)
		}
	}
	return result
}

// Edit applies the draft's actual edits to the latest document. A remote update
// received while the sheet was open does not get overwritten by untouched fields.
func (doc Document) Edit(plugin string, before, draft Selection, writer string) (Document, error) {
	if doc.Validate() != nil || !slices.Equal(sortedKeys(before.Enabled), sortedKeys(draft.Enabled)) || len(draft.Order) != len(draft.Enabled) {
		return Empty(), pluginprefs.ErrInvalid
	}
	seen := map[string]bool{}
	for _, id := range draft.Order {
		if _, ok := draft.Enabled[id]; !ok || seen[id] {
			return Empty(), pluginprefs.ErrInvalid
		}
		seen[id] = true
	}
	prefs, ok := doc.Plugins[plugin]
	if !ok {
		prefs = pluginprefs.Empty()
	}
	initialize := prefs.Order == nil
	var err error
	for _, id := range draft.Order {
		if initialize || before.Enabled[id] != draft.Enabled[id] {
			prefs, err = prefs.WithEnabled(id, draft.Enabled[id], writer)
			if err != nil {
				return Empty(), err
			}
		}
	}
	if initialize || !slices.Equal(before.Order, draft.Order) {
		order := slices.Clone(draft.Order)
		if prefs.Order != nil {
			for _, id := range prefs.Order.Value {
				if !seen[id] {
					order = append(order, id)
				}
			}
		}
		prefs, err = prefs.WithOrder(order, writer)
		if err != nil {
			return Empty(), err
		}
	}
	result := Empty()
	result.Plugins = maps.Clone(doc.Plugins)
	result.Plugins[plugin] = prefs
	return result, result.Validate()
}
func sortedKeys(values map[string]bool) []string {
	ids := make([]string, 0, len(values))
	for id := range values {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	return ids
}
