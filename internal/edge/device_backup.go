package edge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

const backupObjectLimit = 4 << 20
const backupChunkBytes = 2 << 20
const backupArchiveLimit int64 = 16 << 30

// DeviceBackupReference is published with device settings after the complete
// encrypted archive has been uploaded. It never contains local paths or secrets.
type DeviceBackupReference struct {
	Index    string `json:"index"`
	Platform string `json:"platform"`
	Created  int64  `json:"created"`
	Bytes    int64  `json:"bytes"`
	SHA256   string `json:"sha256"`
	Plugins  int    `json:"plugins"`
}

type backupIndex struct {
	Version int      `json:"version"`
	Parts   []string `json:"parts"`
	Bytes   int64    `json:"bytes"`
	SHA256  string   `json:"sha256"`
}

func validBackupKey(key string) bool {
	b, err := hex.DecodeString(key)
	return err == nil && len(b) == 16 && hex.EncodeToString(b) == key
}

func (ref DeviceBackupReference) valid() bool {
	hash, err := hex.DecodeString(ref.SHA256)
	return validBackupKey(ref.Index) && err == nil && len(hash) == sha256.Size &&
		ref.SHA256 == hex.EncodeToString(hash) && ref.Bytes > 0 && ref.Bytes <= backupArchiveLimit &&
		(ref.Platform == "desktop" || ref.Platform == "ios" || ref.Platform == "android") && ref.Plugins >= 0
}

func backupObjectPath(owner, key string) (string, error) {
	if !identifier.MatchString(owner) || !validBackupKey(key) {
		return "", errors.New("invalid backup object")
	}
	return "/v1/backups/" + owner + "/" + key, nil
}

func (s *Sync) putBackupObject(ctx context.Context, op operation, owner string, value []byte) (string, error) {
	key := hex.EncodeToString(randBytes(16))
	env, err := sealData(op.recovery, op.grant.SpaceID, "backup-"+owner, key, 1, struct {
		Data string `json:"data"`
	}{encoding.EncodeToString(value)})
	if err != nil {
		return "", err
	}
	body, err := json.Marshal(env)
	if err != nil || len(body) > backupObjectLimit {
		return "", errors.New("backup object exceeds size limit")
	}
	path, err := backupObjectPath(owner, key)
	if err != nil {
		return "", err
	}
	_, _, err = s.client.request(ctx, http.MethodPut, path, op.grant.Token, "", body, 0, 201, 204)
	return key, err
}

func (s *Sync) getBackupObject(ctx context.Context, op operation, owner, key string) ([]byte, error) {
	path, err := backupObjectPath(owner, key)
	if err != nil {
		return nil, err
	}
	body, _, err := s.client.request(ctx, http.MethodGet, path, op.grant.Token, "", nil, backupObjectLimit, 200)
	if err != nil {
		return nil, err
	}
	var envelope Envelope
	var value struct {
		Data string `json:"data"`
	}
	if json.Unmarshal(body, &envelope) != nil || envelope.Format != 1 ||
		openDataLimit(op.recovery, op.grant.SpaceID, "backup-"+owner, key, envelope, &value, backupObjectLimit) != nil {
		return nil, errors.New("cannot authenticate device backup")
	}
	data, err := encoding.DecodeString(value.Data)
	if err != nil || len(data) > backupChunkBytes || encoding.EncodeToString(data) != value.Data {
		return nil, errors.New("invalid backup chunk")
	}
	return data, nil
}

func (s *Sync) deleteBackupObjects(ctx context.Context, op operation, owner string, keys []string) error {
	var failure error
	for _, key := range keys {
		path, err := backupObjectPath(owner, key)
		if err == nil {
			_, _, err = s.client.request(ctx, http.MethodDelete, path, op.grant.Token, "", nil, 0, 204)
		}
		failure = errors.Join(failure, err)
		if ctx.Err() != nil {
			break
		}
	}
	return failure
}

// UploadDeviceBackup streams a ZIP archive in bounded encrypted chunks. The
// caller publishes the returned reference only after this method succeeds.
func (s *Sync) UploadDeviceBackup(ctx context.Context, archive io.Reader, platform string, plugins int) (ref DeviceBackupReference, err error) {
	op, err := s.settingsOperation()
	if err != nil {
		return ref, err
	}
	owner := hex.EncodeToString([]byte(op.grant.DeviceID))
	var keys []string
	defer func() {
		if err != nil {
			cleanup, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			_ = s.deleteBackupObjects(cleanup, op, owner, keys)
		}
	}()
	hash := sha256.New()
	index := backupIndex{Version: 1, Parts: []string{}}
	buffer := make([]byte, backupChunkBytes)
	for {
		if err = ctx.Err(); err != nil {
			return ref, err
		}
		n, readErr := io.ReadFull(archive, buffer)
		if readErr != nil && !errors.Is(readErr, io.EOF) && !errors.Is(readErr, io.ErrUnexpectedEOF) {
			return ref, readErr
		}
		if n == 0 {
			break
		}
		index.Bytes += int64(n)
		if index.Bytes > backupArchiveLimit {
			return ref, errors.New("device backup exceeds 16 GiB")
		}
		hash.Write(buffer[:n])
		key, uploadErr := s.putBackupObject(ctx, op, owner, buffer[:n])
		if key != "" {
			keys = append(keys, key)
		}
		if uploadErr != nil {
			return ref, uploadErr
		}
		index.Parts = append(index.Parts, key)
		if readErr != nil {
			break
		}
	}
	index.SHA256 = hex.EncodeToString(hash.Sum(nil))
	encoded, err := json.Marshal(index)
	if err != nil || index.Bytes == 0 {
		return ref, errors.New("empty device backup")
	}
	key, err := s.putBackupObject(ctx, op, owner, encoded)
	if key != "" {
		keys = append(keys, key)
	}
	if err != nil {
		return ref, err
	}
	ref = DeviceBackupReference{Index: key, Platform: platform, Created: time.Now().Unix(), Bytes: index.Bytes, SHA256: index.SHA256, Plugins: plugins}
	if !ref.valid() {
		return ref, errors.New("invalid device backup reference")
	}
	return ref, nil
}

func (s *Sync) readBackupIndex(ctx context.Context, op operation, owner string, ref DeviceBackupReference) (backupIndex, error) {
	var index backupIndex
	if !ref.valid() {
		return index, errors.New("invalid device backup reference")
	}
	data, err := s.getBackupObject(ctx, op, owner, ref.Index)
	if err != nil {
		return index, err
	}
	if json.Unmarshal(data, &index) != nil || index.Version != 1 || index.Bytes != ref.Bytes || index.SHA256 != ref.SHA256 ||
		len(index.Parts) != int((ref.Bytes+backupChunkBytes-1)/backupChunkBytes) {
		return index, errors.New("invalid device backup index")
	}
	seen := map[string]bool{}
	for _, key := range index.Parts {
		if !validBackupKey(key) || seen[key] || key == ref.Index {
			return index, errors.New("invalid backup part")
		}
		seen[key] = true
	}
	return index, nil
}

// DownloadDeviceBackup writes only to a staging destination. Callers must not
// import it before the complete length, digest and authenticated chunks pass.
func (s *Sync) DownloadDeviceBackup(ctx context.Context, owner string, ref DeviceBackupReference, target io.Writer) error {
	op, err := s.settingsOperation()
	if err != nil {
		return err
	}
	index, err := s.readBackupIndex(ctx, op, owner, ref)
	if err != nil {
		return err
	}
	hash := sha256.New()
	var total int64
	for i, key := range index.Parts {
		data, err := s.getBackupObject(ctx, op, owner, key)
		if err != nil {
			return err
		}
		expected := min(int64(backupChunkBytes), ref.Bytes-total)
		if int64(len(data)) != expected || (i+1 < len(index.Parts) && len(data) != backupChunkBytes) {
			return errors.New("incomplete backup chunk")
		}
		if _, err := io.MultiWriter(target, hash).Write(data); err != nil {
			return err
		}
		total += int64(len(data))
	}
	if total != ref.Bytes || hex.EncodeToString(hash.Sum(nil)) != ref.SHA256 {
		return errors.New("device backup checksum mismatch")
	}
	return ctx.Err()
}

func (s *Sync) DeleteDeviceBackup(ctx context.Context, ref DeviceBackupReference) error {
	op, err := s.settingsOperation()
	if err != nil {
		return err
	}
	owner := hex.EncodeToString([]byte(op.grant.DeviceID))
	index, err := s.readBackupIndex(ctx, op, owner, ref)
	if err != nil {
		return fmt.Errorf("read old backup: %w", err)
	}
	return s.deleteBackupObjects(ctx, op, owner, append(index.Parts, ref.Index))
}
