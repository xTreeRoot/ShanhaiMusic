package discoveryprefs

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"mygo-music/internal/pluginprefs"
)

type snapshot struct {
	Writer   string   `json:"writer"`
	Document Document `json:"document"`
}

// Store serializes edits and node merges, and keeps both snapshots outside plugin code.
type Store struct {
	mu  sync.Mutex
	dir string
}

func NewStore(dir string) *Store { return &Store{dir: dir} }
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
		raw, err := os.ReadFile(filepath.Join(s.dir, name))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		var v snapshot
		if err == nil {
			if len(raw) > 1<<20 {
				err = pluginprefs.ErrInvalid
			} else {
				err = json.Unmarshal(raw, &v)
			}
		}
		if err == nil {
			err = v.Document.Validate()
		}
		if err == nil {
			_, err = pluginprefs.Empty().WithOrder([]string{}, v.Writer)
		}
		if err != nil {
			failure = err
			continue
		}
		copies = append(copies, v)
	}
	if len(copies) > 0 {
		v := copies[0]
		for _, other := range copies[1:] {
			next, err := v.Document.Merge(other.Document)
			if err != nil {
				return snapshot{}, err
			}
			v.Document = next
		}
		repair := len(copies) != 2
		for _, other := range copies {
			repair = repair || other.Writer != v.Writer || !other.Document.Equal(v.Document)
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
	v := snapshot{pluginprefs.NewWriter(), Empty()}
	return v, s.save(v)
}
func (s *Store) save(v snapshot) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if len(raw) > 1<<20 {
		return pluginprefs.ErrInvalid
	}
	for _, name := range []string{"preferences.json", "recovery/preferences.json"} {
		file := filepath.Join(s.dir, name)
		if err = os.MkdirAll(filepath.Dir(file), 0700); err != nil {
			return err
		}
		pending, err := os.CreateTemp(filepath.Dir(file), ".discovery-*")
		if err != nil {
			return err
		}
		tmp := pending.Name()
		err = pending.Chmod(0600)
		if err == nil {
			_, err = pending.Write(raw)
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
