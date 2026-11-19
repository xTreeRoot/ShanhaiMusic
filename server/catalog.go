package main

import (
	"archive/zip"
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

const maxArchive = 32 << 20
const maxCatalog = 1 << 20

var packageDigest = regexp.MustCompile(`^[a-f0-9]{64}$`)
var releaseVersion = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+([-+][a-zA-Z0-9.-]+)?$`)

type packageInfo struct {
	ID          string          `json:"id"`
	Version     string          `json:"version"`
	MinHost     string          `json:"minAppVersion"`
	Permissions json.RawMessage `json:"permissions"`
	SHA256      string          `json:"sha256"`
	Size        int             `json:"size"`
	Path        string          `json:"path"`
}

type catalogIndex struct {
	Protocol int           `json:"protocol"`
	Revision int64         `json:"revision"`
	Plugins  []packageInfo `json:"plugins"`
}

type signedCatalog struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

type publishedCatalog struct {
	body     []byte
	dir      string
	packages map[string]struct{}
}

func readLimited(path string, limit int64) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err == nil && int64(len(data)) > limit {
		err = errors.New("file exceeds limit")
	}
	return data, err
}

func loadKey(path string, size int) ([]byte, error) {
	data, err := readLimited(path, 256)
	if err != nil {
		return nil, err
	}
	value := string(bytes.TrimSpace(data))
	if !validToken(value, size) {
		return nil, errors.New("invalid catalog key")
	}
	return base64.RawURLEncoding.DecodeString(value)
}

func createKeys(privatePath, publicPath string) error {
	public, private, err := ed25519.GenerateKey(nil)
	if err != nil {
		return err
	}
	if err := writeNew(privatePath, []byte(base64.RawURLEncoding.EncodeToString(private.Seed())+"\n"), 0600); err != nil {
		return err
	}
	return writeNew(publicPath, []byte(base64.RawURLEncoding.EncodeToString(public)+"\n"), 0644)
}

func writeNew(path string, data []byte, mode os.FileMode) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	_, writeErr := file.Write(data)
	return errors.Join(writeErr, file.Close())
}

func inspectPackage(data []byte) (packageInfo, error) {
	var info packageInfo
	zipFile, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return info, err
	}
	var found bool
	for _, file := range zipFile.File {
		if file.Name != "plugin.json" {
			continue
		}
		if found || !file.Mode().IsRegular() || file.UncompressedSize64 > 64<<10 {
			return info, errors.New("invalid archive manifest")
		}
		content, err := file.Open()
		if err != nil {
			return info, err
		}
		manifest, err := io.ReadAll(io.LimitReader(content, (64<<10)+1))
		content.Close()
		if err != nil || len(manifest) > 64<<10 {
			return info, errors.New("cannot read archive manifest")
		}
		if err := json.Unmarshal(manifest, &info); err != nil {
			return info, err
		}
		found = true
	}
	if !found || !identifier.MatchString(info.ID) || !releaseVersion.MatchString(info.Version) || !releaseVersion.MatchString(info.MinHost) ||
		len(info.Permissions) == 0 || info.Permissions[0] != '{' {
		return info, errors.New("missing or invalid plugin id, version, minAppVersion, or permissions")
	}
	sum := sha256.Sum256(data)
	info.SHA256 = hex.EncodeToString(sum[:])
	info.Size = len(data)
	info.Path = "/v1/packages/" + info.SHA256
	return info, nil
}

func signCatalog(dir, keyPath string, revision int64, archives []string) error {
	seed, err := loadKey(keyPath, ed25519.SeedSize)
	if err != nil {
		return err
	}
	if revision < 1 || len(archives) == 0 || len(archives) > 64 {
		return errors.New("expected a positive revision and 1–64 archives")
	}
	if err := os.Mkdir(dir, 0755); err != nil {
		return err
	}
	packages := filepath.Join(dir, "packages")
	if err := os.Mkdir(packages, 0755); err != nil {
		return err
	}
	index := catalogIndex{Protocol: 1, Revision: revision}
	seen := make(map[string]bool)
	for _, path := range archives {
		data, err := readLimited(path, maxArchive)
		if err != nil {
			return err
		}
		info, err := inspectPackage(data)
		if err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
		if seen[info.ID] {
			return fmt.Errorf("duplicate plugin %s", info.ID)
		}
		seen[info.ID] = true
		if err := writeNew(filepath.Join(packages, info.SHA256+".zip"), data, 0644); err != nil {
			return err
		}
		index.Plugins = append(index.Plugins, info)
	}
	payload, err := json.Marshal(index)
	if err != nil {
		return err
	}
	signature := ed25519.Sign(ed25519.NewKeyFromSeed(seed), payload)
	encoding := base64.RawURLEncoding
	body, err := json.Marshal(signedCatalog{Payload: encoding.EncodeToString(payload), Signature: encoding.EncodeToString(signature)})
	if err != nil {
		return err
	}
	if len(body) > maxCatalog {
		return errors.New("signed catalog exceeds limit")
	}
	return writeNew(filepath.Join(dir, "catalog.json"), body, 0644)
}

func loadCatalog(dir, keyPath string) (*publishedCatalog, error) {
	key, err := loadKey(keyPath, ed25519.PublicKeySize)
	if err != nil {
		return nil, err
	}
	body, err := readLimited(filepath.Join(dir, "catalog.json"), maxCatalog)
	if err != nil {
		return nil, err
	}
	var signed signedCatalog
	if err := json.Unmarshal(body, &signed); err != nil {
		return nil, err
	}
	encoding := base64.RawURLEncoding.Strict()
	payload, err := encoding.DecodeString(signed.Payload)
	if err != nil {
		return nil, err
	}
	signature, err := encoding.DecodeString(signed.Signature)
	if err != nil || !ed25519.Verify(ed25519.PublicKey(key), payload, signature) {
		return nil, errors.New("invalid catalog signature")
	}
	var index catalogIndex
	if err := json.Unmarshal(payload, &index); err != nil {
		return nil, err
	}
	if index.Protocol != 1 || index.Revision < 1 || len(index.Plugins) == 0 || len(index.Plugins) > 64 {
		return nil, errors.New("invalid catalog index")
	}
	result := &publishedCatalog{body: body, dir: dir, packages: make(map[string]struct{})}
	seen := make(map[string]bool)
	for _, info := range index.Plugins {
		if !identifier.MatchString(info.ID) || seen[info.ID] || !packageDigest.MatchString(info.SHA256) ||
			info.Size <= 0 || info.Size > maxArchive || info.Path != "/v1/packages/"+info.SHA256 {
			return nil, errors.New("invalid catalog package metadata")
		}
		seen[info.ID] = true
		archive, err := readLimited(filepath.Join(dir, "packages", info.SHA256+".zip"), maxArchive)
		if err != nil {
			return nil, err
		}
		actual, err := inspectPackage(archive)
		permissions, _ := json.Marshal(actual.Permissions)
		if err != nil || actual.SHA256 != info.SHA256 || actual.Size != info.Size || actual.ID != info.ID || actual.Version != info.Version ||
			actual.MinHost != info.MinHost || !bytes.Equal(permissions, info.Permissions) {
			return nil, fmt.Errorf("catalog package mismatch: %s", info.ID)
		}
		result.packages[info.SHA256] = struct{}{}
	}
	return result, nil
}

func (catalog *publishedCatalog) serveCatalog(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Content-Type", "application/json")
	http.ServeContent(writer, request, "catalog.json", time.Time{}, bytes.NewReader(catalog.body))
}

func (catalog *publishedCatalog) servePackage(writer http.ResponseWriter, request *http.Request) {
	digest := request.PathValue("digest")
	if _, ok := catalog.packages[digest]; !ok {
		fail(writer, http.StatusNotFound, "package_not_found")
		return
	}
	file, err := os.Open(filepath.Join(catalog.dir, "packages", digest+".zip"))
	if err != nil {
		fail(writer, http.StatusServiceUnavailable, "package_unavailable")
		return
	}
	defer file.Close()
	writer.Header().Set("Content-Type", "application/zip")
	writer.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	writer.Header().Set("ETag", `"`+digest+`"`)
	http.ServeContent(writer, request, digest+".zip", time.Time{}, file)
}
