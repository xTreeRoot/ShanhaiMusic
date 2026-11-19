package main

import (
	"archive/zip"
	"bytes"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSignedCatalogAndPackages(t *testing.T) {
	dir := t.TempDir()
	privatePath, publicPath := filepath.Join(dir, "private"), filepath.Join(dir, "public")
	if err := createKeys(privatePath, publicPath); err != nil {
		t.Fatal(err)
	}
	var archive bytes.Buffer
	zipWriter := zip.NewWriter(&archive)
	manifest, err := zipWriter.Create("plugin.json")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(manifest, `{"id":"demo","version":"0.1.0","minAppVersion":"0.1.25","permissions": {"network": ["example.org"]}}`); err != nil {
		t.Fatal(err)
	}
	if err := zipWriter.Close(); err != nil {
		t.Fatal(err)
	}
	archivePath := filepath.Join(dir, "demo.zip")
	if err := os.WriteFile(archivePath, archive.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	publication := filepath.Join(dir, "published")
	if err := signCatalog(publication, privatePath, 1, []string{archivePath}); err != nil {
		t.Fatal(err)
	}
	catalog, err := loadCatalog(publication, publicPath)
	if err != nil {
		t.Fatal(err)
	}
	handler := (&api{catalog: catalog}).handler()
	info, err := inspectPackage(archive.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", info.Path, nil))
	if response.Code != 200 || !bytes.Equal(response.Body.Bytes(), archive.Bytes()) {
		t.Fatal("incorrect package response")
	}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", "/v1/packages/not-published", nil))
	if response.Code != 404 {
		t.Fatal("served unlisted package")
	}
	if err := os.WriteFile(filepath.Join(publication, "packages", info.SHA256+".zip"), []byte("corrupted"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadCatalog(publication, publicPath); err == nil {
		t.Fatal("accepted a corrupted archive")
	}
	if err := createKeys(filepath.Join(dir, "other-private"), filepath.Join(dir, "other-public")); err != nil {
		t.Fatal(err)
	}
	if _, err := loadCatalog(publication, filepath.Join(dir, "other-public")); err == nil || err.Error() != "invalid catalog signature" {
		t.Fatalf("did not reject wrong signer: %v", err)
	}
}
