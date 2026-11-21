// Package pluginprefs defines the portable, source-independent plugin preferences document.
package pluginprefs

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"maps"
	"reflect"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

const MaxClock int64 = 1<<53 - 1
const MaxPlugins = 512

type Register[T any] struct {
	Value  T      `json:"value"`
	Clock  int64  `json:"clock"`
	Writer string `json:"writer"`
}

type Document struct {
	Version int                         `json:"version"`
	Names   map[string]Register[string] `json:"names"`
	Enabled map[string]Register[bool]   `json:"enabled"`
	Order   *Register[[]string]         `json:"order,omitempty"`
}

var ErrInvalid = errors.New("invalid plugin preferences")
var pluginID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`)

func (entry *Register[T]) UnmarshalJSON(data []byte) error {
	type plain Register[T]
	var fields map[string]json.RawMessage
	if json.Unmarshal(data, &fields) != nil || fields["value"] == nil || string(fields["value"]) == "null" {
		return ErrInvalid
	}
	var next plain
	if json.Unmarshal(data, &next) != nil || !validStamp(Register[T](next)) {
		return ErrInvalid
	}
	*entry = Register[T](next)
	return nil
}

func (doc *Document) UnmarshalJSON(data []byte) error {
	type plain Document
	var fields map[string]json.RawMessage
	if json.Unmarshal(data, &fields) != nil || fields["names"] == nil || string(fields["names"]) == "null" || fields["enabled"] == nil || string(fields["enabled"]) == "null" {
		return ErrInvalid
	}
	var next plain
	if json.Unmarshal(data, &next) != nil || Document(next).Validate() != nil {
		return ErrInvalid
	}
	*doc = Document(next)
	return nil
}

func Empty() Document {
	return Document{Version: 1, Names: map[string]Register[string]{}, Enabled: map[string]Register[bool]{}}
}
func NewWriter() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(bytes[:])
}
func validID(id string) bool { return pluginID.MatchString(id) && !strings.Contains(id, "..") }
func validStamp[T any](entry Register[T]) bool {
	bytes, err := base64.RawURLEncoding.DecodeString(entry.Writer)
	return entry.Clock > 0 && entry.Clock <= MaxClock && err == nil && len(bytes) == 16 && base64.RawURLEncoding.EncodeToString(bytes) == entry.Writer
}

func (doc Document) Validate() error {
	if doc.Version != 1 || doc.Names == nil || doc.Enabled == nil || len(doc.Names) > MaxPlugins || len(doc.Enabled) > MaxPlugins {
		return ErrInvalid
	}
	ids := map[string]bool{}
	for id, entry := range doc.Names {
		if !validID(id) || !validStamp(entry) || !utf8.ValidString(entry.Value) || len(entry.Value) > 240 || utf8.RuneCountInString(entry.Value) > 60 || strings.ContainsFunc(entry.Value, unicode.IsControl) {
			return ErrInvalid
		}
		ids[id] = true
	}
	for id, entry := range doc.Enabled {
		if !validID(id) || !validStamp(entry) {
			return ErrInvalid
		}
		ids[id] = true
	}
	if doc.Order != nil {
		if !validStamp(*doc.Order) || len(doc.Order.Value) > MaxPlugins || doc.Order.Value == nil {
			return ErrInvalid
		}
		seen := map[string]bool{}
		for _, id := range doc.Order.Value {
			if !validID(id) || seen[id] {
				return ErrInvalid
			}
			seen[id] = true
			ids[id] = true
		}
	}
	if len(ids) > MaxPlugins {
		return ErrInvalid
	}
	return nil
}

func (doc Document) IsEmpty() bool {
	return len(doc.Names) == 0 && len(doc.Enabled) == 0 && doc.Order == nil
}
func (doc Document) MaxClock() int64 {
	var clock int64
	for _, entry := range doc.Names {
		clock = max(clock, entry.Clock)
	}
	for _, entry := range doc.Enabled {
		clock = max(clock, entry.Clock)
	}
	if doc.Order != nil {
		clock = max(clock, doc.Order.Clock)
	}
	return clock
}
func (doc Document) Equal(other Document) bool {
	return maps.Equal(doc.Names, other.Names) && maps.Equal(doc.Enabled, other.Enabled) && reflect.DeepEqual(doc.Order, other.Order) && doc.Version == other.Version
}
func (doc Document) clone() Document {
	doc.Names, doc.Enabled = maps.Clone(doc.Names), maps.Clone(doc.Enabled)
	if doc.Names == nil {
		doc.Names = map[string]Register[string]{}
	}
	if doc.Enabled == nil {
		doc.Enabled = map[string]Register[bool]{}
	}
	if doc.Order != nil {
		entry := *doc.Order
		entry.Value = append([]string{}, entry.Value...)
		doc.Order = &entry
	}
	return doc
}
func newer[T any](left, right Register[T]) (Register[T], error) {
	if left.Clock == right.Clock && left.Writer == right.Writer && !reflect.DeepEqual(left.Value, right.Value) {
		return left, ErrInvalid
	}
	if right.Clock > left.Clock || right.Clock == left.Clock && right.Writer > left.Writer {
		return right, nil
	}
	return left, nil
}

func (doc Document) Merge(other Document) (Document, error) {
	if doc.Validate() != nil || other.Validate() != nil {
		return Document{}, ErrInvalid
	}
	result := doc.clone()
	for id, right := range other.Names {
		left, ok := result.Names[id]
		if !ok {
			result.Names[id] = right
			continue
		}
		merged, err := newer(left, right)
		if err != nil {
			return Document{}, err
		}
		result.Names[id] = merged
	}
	for id, right := range other.Enabled {
		left, ok := result.Enabled[id]
		if !ok {
			result.Enabled[id] = right
			continue
		}
		merged, err := newer(left, right)
		if err != nil {
			return Document{}, err
		}
		result.Enabled[id] = merged
	}
	if other.Order != nil {
		entry := *other.Order
		if result.Order != nil {
			var err error
			entry, err = newer(*result.Order, entry)
			if err != nil {
				return Document{}, err
			}
		}
		entry.Value = append([]string{}, entry.Value...)
		result.Order = &entry
	}
	return result, result.Validate()
}

func (doc Document) WithName(id, name, writer string) (Document, error) {
	result := doc.clone()
	result.Names[id] = Register[string]{name, doc.MaxClock() + 1, writer}
	return result, result.Validate()
}
func (doc Document) WithEnabled(id string, enabled bool, writer string) (Document, error) {
	result := doc.clone()
	result.Enabled[id] = Register[bool]{enabled, doc.MaxClock() + 1, writer}
	return result, result.Validate()
}
func (doc Document) WithOrder(ids []string, writer string) (Document, error) {
	result := doc.clone()
	result.Order = &Register[[]string]{append([]string{}, ids...), doc.MaxClock() + 1, writer}
	return result, result.Validate()
}
