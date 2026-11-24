package edge

import (
	"context"
	"encoding/json"
	"errors"
	"maps"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"mygo-music/internal/music"
)

type Migrator interface {
	ExportSession() (Session, int, error)
	StageSession(int, Session) (func() (*music.Account, error), error)
}

type Candidate struct {
	Ref   string
	Label string
}

type upload struct {
	Plugin   string
	Ref      string
	Previous string
	Envelope Envelope
}

type syncState struct {
	Grant       Grant
	Recovery    string
	Key         string
	KeyMismatch bool
	Blocked     map[string]bool
	Versions    map[string]string
	Refs        map[string]string
	Pending     map[string]upload
	Profiles    map[string]Profile `json:",omitempty"`
}

type Sync struct {
	client *Client
	file   string
	mu     sync.Mutex
	state  syncState
	epoch  map[string]uint64
	serial uint64
	flush  sync.Mutex
}

func OpenSync(client *Client, directory string) (*Sync, error) {
	service := &Sync{client: client, file: filepath.Join(directory, "sync.json"), epoch: map[string]uint64{}}
	data, err := os.ReadFile(service.file)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err == nil && json.Unmarshal(data, &service.state) != nil {
		return nil, errors.New("invalid local MB sync state")
	}
	return service, nil
}

func (service *Sync) saveLocked(next syncState) error {
	data, err := json.Marshal(next)
	if err != nil {
		return err
	}
	if err := writePrivate(service.file, data); err != nil {
		return err
	}
	service.state = next
	return nil
}

func cloned[Value any](current map[string]Value) map[string]Value {
	if current == nil {
		return map[string]Value{}
	}
	return maps.Clone(current)
}

func (service *Sync) Binding() (space string, expires int64) {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.state.Grant.SpaceID, service.state.Grant.ExpiresAt
}

func (service *Sync) HasRemote(plugin string) bool {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.state.Refs[plugin] != ""
}

func (service *Sync) setGrant(grant nodeGrant, approvedKey string) error {
	service.mu.Lock()
	defer service.mu.Unlock()
	next := service.state
	if next.Key != "" && next.Key != grant.Key && approvedKey != grant.Key {
		next.KeyMismatch = true
		service.serial++
		if err := service.saveLocked(next); err != nil {
			return err
		}
		return &KeyChanged{Key: grant.Key}
	}
	if next.Grant.SpaceID != grant.SpaceID || next.Recovery != grant.Recovery {
		next = syncState{Blocked: next.Blocked}
	}
	next.Grant, next.Recovery, next.Key, next.KeyMismatch = grant.Grant, grant.Recovery, grant.Key, false
	if err := service.saveLocked(next); err != nil {
		return err
	}
	service.serial++
	return nil
}

func (service *Sync) Unbind() error {
	service.mu.Lock()
	defer service.mu.Unlock()
	if err := service.saveLocked(syncState{Blocked: service.state.Blocked, Key: service.state.Key, KeyMismatch: service.state.KeyMismatch}); err != nil {
		return err
	}
	service.serial++
	return nil
}

func (service *Sync) suspend() {
	service.mu.Lock()
	defer service.mu.Unlock()
	service.serial++
}

func (service *Sync) Invalidate(plugin string) {
	service.mu.Lock()
	defer service.mu.Unlock()
	service.epoch[plugin]++
}

type Ticket struct{ serial, epoch uint64 }

func (service *Sync) Ticket(plugin string) Ticket {
	service.mu.Lock()
	defer service.mu.Unlock()
	return Ticket{service.serial, service.epoch[plugin]}
}

func (service *Sync) Block(plugin string) error {
	service.mu.Lock()
	defer service.mu.Unlock()
	service.epoch[plugin]++
	next := service.state
	next.Blocked = cloned(next.Blocked)
	next.Pending = cloned(next.Pending)
	next.Blocked[plugin] = true
	for key, entry := range next.Pending {
		if entry.Plugin == plugin {
			delete(next.Pending, key)
		}
	}
	return service.saveLocked(next)
}

type operation struct {
	grant    Grant
	recovery string
	serial   uint64
	epoch    uint64
}

func (service *Sync) snapshot(plugin string) operation {
	if service.state.KeyMismatch {
		return operation{serial: service.serial, epoch: service.epoch[plugin]}
	}
	return operation{service.state.Grant, service.state.Recovery, service.serial, service.epoch[plugin]}
}

func (op operation) usable() bool {
	return op.grant.ExpiresAt > time.Now().Unix() && validBase64(op.grant.Token, 32) && validBase64(op.recovery, 32)
}

func (service *Sync) current(ctx context.Context, plugin string, op operation) bool {
	return ctx.Err() == nil && service.serial == op.serial && service.epoch[plugin] == op.epoch
}

type candidate struct {
	Candidate
	Session
	format int
	tag    string
}

func (service *Sync) Restore(ctx context.Context, plugin string, ticket Ticket, migrator Migrator, ref string) (*music.Account, []Candidate, error) {
	service.mu.Lock()
	op := service.snapshot(plugin)
	blocked := service.state.Blocked[plugin]
	service.mu.Unlock()
	if !op.usable() || blocked || ticket != (Ticket{op.serial, op.epoch}) {
		return nil, nil, nil
	}
	entries, err := service.client.list(ctx, op.grant, plugin)
	if err != nil {
		return nil, nil, err
	}
	var candidates []candidate
	for _, entry := range entries {
		if entry.Deleted || ref != "" && entry.Ref != ref {
			continue
		}
		envelope, tag, err := service.client.get(ctx, op.grant, plugin, entry.Ref)
		if err != nil {
			return nil, nil, err
		}
		if tag != entry.ETag || envelope.Format != entry.Format {
			return nil, nil, errors.New("MB session changed; retry restore")
		}
		session, err := openSession(op.recovery, op.grant.SpaceID, plugin, entry.Ref, envelope)
		if err != nil {
			return nil, nil, err
		}
		label := session.Label
		if label == "" {
			label = session.AccountID
		}
		candidates = append(candidates, candidate{Candidate{entry.Ref, label}, session, envelope.Format, tag})
	}
	if len(candidates) != 1 {
		choices := make([]Candidate, 0, len(candidates))
		for _, choice := range candidates {
			choices = append(choices, choice.Candidate)
		}
		return nil, choices, nil
	}
	selected := candidates[0]
	commit, err := migrator.StageSession(selected.format, selected.Session)
	if err != nil || commit == nil {
		return nil, nil, err
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	if !service.current(ctx, plugin, op) || service.state.Blocked[plugin] {
		return nil, nil, context.Canceled
	}
	account, err := commit()
	if err != nil || account == nil {
		return nil, nil, err
	}
	next := service.state
	next.Versions, next.Refs = cloned(next.Versions), cloned(next.Refs)
	next.Versions[plugin+"/"+selected.Ref], next.Refs[plugin] = selected.tag, selected.Ref
	return account, nil, service.saveLocked(next)
}

func (service *Sync) Publish(ctx context.Context, plugin, account string, ticket Ticket, migrator Migrator) error {
	return service.publish(ctx, plugin, account, ticket, migrator, false, false)
}

func (service *Sync) PublishCurrent(ctx context.Context, plugin, account string, ticket Ticket, migrator Migrator) error {
	return service.publish(ctx, plugin, account, ticket, migrator, true, false)
}

func (service *Sync) Recreate(ctx context.Context, plugin, account string, ticket Ticket, migrator Migrator) error {
	return service.publish(ctx, plugin, account, ticket, migrator, false, true)
}

func (service *Sync) publish(ctx context.Context, plugin, account string, ticket Ticket, migrator Migrator, readRemote, recreate bool) error {
	service.mu.Lock()
	op := service.snapshot(plugin)
	if ticket != (Ticket{op.serial, op.epoch}) || ctx.Err() != nil {
		service.mu.Unlock()
		return context.Canceled
	}
	if service.state.Blocked[plugin] {
		next := service.state
		next.Blocked = cloned(next.Blocked)
		delete(next.Blocked, plugin)
		if err := service.saveLocked(next); err != nil {
			service.mu.Unlock()
			return err
		}
	}
	service.mu.Unlock()
	if !op.usable() {
		return nil
	}
	session, format, err := migrator.ExportSession()
	if err != nil {
		return err
	}
	if session.AccountID != account {
		return music.ErrUnsupported
	}
	envelope, ref, err := seal(op.recovery, op.grant.SpaceID, plugin, format, session)
	if err != nil {
		return err
	}
	var observed string
	if recreate {
		_, tag, err := service.client.get(ctx, op.grant, plugin, ref)
		if status, ok := errors.AsType[*StatusError](err); !ok || status.Code != http.StatusGone || !validTag(tag) {
			return errors.New("There is no deleted cloud session to recreate")
		}
		observed = tag
	} else if readRemote {
		entries, err := service.client.list(ctx, op.grant, plugin)
		if err != nil {
			return err
		}
		observed = "*"
		for _, entry := range entries {
			if entry.Ref == ref {
				if entry.Deleted {
					return &StatusError{Code: http.StatusGone}
				}
				observed = entry.ETag
				break
			}
		}
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	if !service.current(ctx, plugin, op) || service.state.Blocked[plugin] {
		return context.Canceled
	}
	key := plugin + "/" + ref
	previous := service.state.Versions[key]
	if observed != "" {
		previous = observed
	}
	if previous == "" {
		previous = "*"
	}
	next := service.state
	next.Pending = cloned(next.Pending)
	next.Pending[key] = upload{plugin, ref, previous, envelope}
	return service.saveLocked(next)
}

func (service *Sync) Flush(ctx context.Context) error {
	service.flush.Lock()
	defer service.flush.Unlock()
	service.mu.Lock()
	op, pending := service.snapshot(""), cloned(service.state.Pending)
	service.mu.Unlock()
	if !op.usable() {
		return nil
	}
	var result error
	for key, queued := range pending {
		service.mu.Lock()
		current := service.current(ctx, "", op) && service.state.Pending[key] == queued
		service.mu.Unlock()
		if !current {
			continue
		}
		tag, err := service.client.change(ctx, op.grant, queued.Plugin, queued.Ref, queued.Previous, &queued.Envelope)
		if err != nil {
			remote, remoteTag, readErr := service.client.get(ctx, op.grant, queued.Plugin, queued.Ref)
			if readErr == nil && remote == queued.Envelope {
				tag = remoteTag
			} else if status, ok := errors.AsType[*StatusError](err); !ok || status.Code != http.StatusPreconditionFailed || readErr != nil && !terminalRead(readErr) {
				result = errors.Join(result, err)
				continue
			} else {
				result = errors.Join(result, err)
			}
		}
		service.mu.Lock()
		if service.current(ctx, "", op) && service.state.Pending[key] == queued {
			next := service.state
			next.Pending, next.Versions, next.Refs = cloned(next.Pending), cloned(next.Versions), cloned(next.Refs)
			delete(next.Pending, key)
			if tag != "" {
				next.Versions[key], next.Refs[queued.Plugin] = tag, queued.Ref
			}
			result = errors.Join(result, service.saveLocked(next))
		}
		service.mu.Unlock()
	}
	return result
}

func terminalRead(err error) bool {
	status, ok := errors.AsType[*StatusError](err)
	return ok && (status.Code == http.StatusNotFound || status.Code == http.StatusGone)
}

func (service *Sync) Delete(ctx context.Context, plugin string) error {
	service.flush.Lock()
	defer service.flush.Unlock()
	service.mu.Lock()
	op, ref := service.snapshot(plugin), service.state.Refs[plugin]
	tag := service.state.Versions[plugin+"/"+ref]
	service.mu.Unlock()
	if !op.usable() || tag == "" {
		return errors.New("no known MB cloud session to delete")
	}
	if _, err := service.client.change(ctx, op.grant, plugin, ref, tag, nil); err != nil {
		return err
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	if !service.current(ctx, plugin, op) {
		return context.Canceled
	}
	next := service.state
	next.Pending, next.Versions, next.Refs = cloned(next.Pending), cloned(next.Versions), cloned(next.Refs)
	delete(next.Pending, plugin+"/"+ref)
	delete(next.Versions, plugin+"/"+ref)
	delete(next.Refs, plugin)
	return service.saveLocked(next)
}
