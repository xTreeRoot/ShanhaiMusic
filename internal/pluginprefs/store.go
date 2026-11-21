package pluginprefs

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"sync"
)

// Legacy is copied from the previous settings before any node is contacted.
type Legacy struct {
	Names   map[string]string
	Enabled map[string]bool
	Order   []string
}
type snapshot struct {
	Writer   string   `json:"writer"`
	Document Document `json:"document"`
}
type Store struct {
	mu     sync.Mutex
	dir    string
	legacy Legacy
}

func NewStore(dir string, legacy Legacy) *Store { return &Store{dir: dir, legacy: legacy} }
func (s *Store) Load() (Document, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, e := s.load()
	return v.Document, e
}
func (s *Store) Update(change func(Document, string) (Document, error)) (Document, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, err := s.load()
	if err != nil {
		return Empty(), err
	}
	next, err := change(v.Document, v.Writer)
	if err != nil {
		return Empty(), err
	}
	if err = next.Validate(); err != nil {
		return Empty(), err
	}
	if !next.Equal(v.Document) {
		v.Document = next
		err = s.save(v)
	}
	return next, err
}
func (s *Store) Merge(remote Document) (Document, error) {
	return s.Update(func(local Document, _ string) (Document, error) { return local.Merge(remote) })
}
func (s *Store) load() (snapshot, error) {
	var copies []snapshot
	var failure error
	for _, name := range []string{"preferences.json", "recovery/preferences.json"} {
		bytes, err := os.ReadFile(filepath.Join(s.dir, name))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		var v snapshot
		if err == nil {
			if len(bytes) > 1<<20 {
				err = ErrInvalid
			} else {
				err = json.Unmarshal(bytes, &v)
			}
		}
		if err == nil {
			err = v.Document.Validate()
		}
		if err == nil {
			writer, e := base64.RawURLEncoding.DecodeString(v.Writer)
			if e != nil || len(writer) != 16 || base64.RawURLEncoding.EncodeToString(writer) != v.Writer {
				err = ErrInvalid
			}
		}
		if err != nil {
			failure = err
			continue
		}
		copies = append(copies, v)
	}
	if len(copies) > 0 {
		v := copies[0]
		for _, copy := range copies[1:] {
			merged, err := v.Document.Merge(copy.Document)
			if err != nil {
				return snapshot{}, err
			}
			v.Document = merged
		}
		repair := len(copies) != 2
		for _, copy := range copies {
			repair = repair || copy.Writer != v.Writer || !copy.Document.Equal(v.Document)
		}
		if repair {
			if err := s.save(v); err != nil {
				return snapshot{}, err
			}
		}
		return v, nil
	}
	if failure != nil {
		return snapshot{}, failure
	}
	v := snapshot{Writer: NewWriter(), Document: Empty()}
	names := make([]string, 0, len(s.legacy.Names))
	for id := range s.legacy.Names {
		names = append(names, id)
	}
	slices.Sort(names)
	for _, id := range names {
		var err error
		v.Document, err = v.Document.WithName(id, s.legacy.Names[id], v.Writer)
		if err != nil {
			return snapshot{}, err
		}
	}
	ids := make([]string, 0, len(s.legacy.Enabled))
	for id := range s.legacy.Enabled {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	for _, id := range ids {
		var err error
		v.Document, err = v.Document.WithEnabled(id, s.legacy.Enabled[id], v.Writer)
		if err != nil {
			return snapshot{}, err
		}
	}
	if s.legacy.Order != nil {
		var err error
		v.Document, err = v.Document.WithOrder(slices.Clone(s.legacy.Order), v.Writer)
		if err != nil {
			return snapshot{}, err
		}
	}
	return v, s.save(v)
}
func (s *Store) save(v snapshot) error {
	bytes, err := json.Marshal(v)
	if err != nil {
		return err
	}
	for _, name := range []string{"preferences.json", "recovery/preferences.json"} {
		file := filepath.Join(s.dir, name)
		if err = os.MkdirAll(filepath.Dir(file), 0700); err != nil {
			return err
		}
		pending, err := os.CreateTemp(filepath.Dir(file), ".preferences-*")
		if err != nil {
			return err
		}
		tmp := pending.Name()
		err = pending.Chmod(0600)
		if err == nil {
			_, err = pending.Write(bytes)
		}
		if err == nil {
			err = pending.Sync()
		}
		closeErr := pending.Close()
		if err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmp, file)
		}
		if err != nil {
			os.Remove(tmp)
			return err
		}
	}
	return nil
}
