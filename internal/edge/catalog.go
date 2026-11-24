package edge

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sync"
)

const packageLimit = 32 << 20

var digestPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var versionPattern = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+([-+][a-zA-Z0-9.-]+)?$`)

type Package struct {
	ID          string          `json:"id"`
	Version     string          `json:"version"`
	MinHost     string          `json:"minAppVersion"`
	Permissions json.RawMessage `json:"permissions"`
	SHA256      string          `json:"sha256"`
	ContentHash string          `json:"content_sha256,omitempty"`
	Size        int64           `json:"size"`
	Path        string          `json:"path"`
}

type Catalog struct {
	client *Client
	file   string
	key    ed25519.PublicKey
	mu     sync.Mutex
}

func newCatalog(client *Client, directory, publicKey string) *Catalog {
	key, _ := encoding.DecodeString(publicKey)
	return &Catalog{client: client, file: filepath.Join(directory, "catalog-"+publicKey+".json"), key: key}
}

type index struct {
	Protocol int
	Revision int64
	Plugins  []Package
}

func (catalog *Catalog) verify(data []byte) (index, []byte, error) {
	var signed struct{ Payload, Signature string }
	var result index
	if json.Unmarshal(data, &signed) != nil || !validBase64(signed.Signature, ed25519.SignatureSize) {
		return result, nil, errors.New("invalid MB catalog signature")
	}
	payload, err := encoding.DecodeString(signed.Payload)
	signature, _ := encoding.DecodeString(signed.Signature)
	if err != nil || encoding.EncodeToString(payload) != signed.Payload || len(catalog.key) != ed25519.PublicKeySize || !ed25519.Verify(catalog.key, payload, signature) {
		return result, nil, errors.New("invalid MB catalog signature")
	}
	if json.Unmarshal(payload, &result) != nil || result.Protocol != 1 || result.Revision < 1 || result.Plugins == nil {
		return index{}, nil, errors.New("invalid MB catalog")
	}
	seen := map[string]bool{}
	for _, entry := range result.Plugins {
		var permissions map[string]json.RawMessage
		if !entry.valid() || entry.ContentHash != "" && !digestPattern.MatchString(entry.ContentHash) || !versionPattern.MatchString(entry.Version) || entry.MinHost != "" && !versionPattern.MatchString(entry.MinHost) ||
			json.Unmarshal(entry.Permissions, &permissions) != nil || permissions == nil || seen[entry.ID] {
			return index{}, nil, errors.New("invalid MB catalog package")
		}
		seen[entry.ID] = true
	}
	return result, payload, nil
}

func (entry Package) valid() bool {
	return identifier.MatchString(entry.ID) && digestPattern.MatchString(entry.SHA256) && entry.Path == "/v1/packages/"+entry.SHA256 && entry.Size > 0 && entry.Size <= packageLimit
}

func (catalog *Catalog) Fetch(ctx context.Context) ([]Package, error) {
	data, _, err := catalog.client.request(ctx, http.MethodGet, "/v1/catalog", "", "", nil, 1<<20, http.StatusOK)
	if err != nil {
		return nil, err
	}
	current, payload, err := catalog.verify(data)
	if err != nil {
		return nil, err
	}
	catalog.mu.Lock()
	defer catalog.mu.Unlock()
	previous, err := os.ReadFile(catalog.file)
	if err == nil {
		old, oldPayload, err := catalog.verify(previous)
		if err != nil || old.Revision > current.Revision || old.Revision == current.Revision && !bytes.Equal(oldPayload, payload) {
			return nil, errors.New("MB catalog revision conflict")
		}
		if old.Revision == current.Revision {
			return current.Plugins, nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	return current.Plugins, writePrivate(catalog.file, data)
}

func (catalog *Catalog) Download(ctx context.Context, entry Package) ([]byte, error) {
	if !entry.valid() {
		return nil, errors.New("invalid MB package address")
	}
	signed, err := os.ReadFile(catalog.file)
	if err != nil {
		return nil, err
	}
	current, _, err := catalog.verify(signed)
	if err != nil || !slices.ContainsFunc(current.Plugins, func(signed Package) bool {
		return signed.ID == entry.ID && signed.Version == entry.Version && signed.MinHost == entry.MinHost &&
			signed.Size == entry.Size && signed.SHA256 == entry.SHA256 && signed.ContentHash == entry.ContentHash && signed.Path == entry.Path && bytes.Equal(signed.Permissions, entry.Permissions)
	}) {
		return nil, errors.New("package is not in the verified MB catalog")
	}
	data, _, err := catalog.client.request(ctx, http.MethodGet, entry.Path, "", "", nil, entry.Size, http.StatusOK)
	if err != nil {
		return nil, err
	}
	digest := sha256.Sum256(data)
	if int64(len(data)) != entry.Size || hex.EncodeToString(digest[:]) != entry.SHA256 {
		return nil, errors.New("MB package does not match catalog")
	}
	return data, nil
}

func writePrivate(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	file, err := os.CreateTemp(dir, ".mb-edge-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	_, err = file.Write(data)
	if err = errors.Join(err, file.Sync(), file.Close()); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
