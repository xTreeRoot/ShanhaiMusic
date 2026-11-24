package plugin

import (
	"archive/zip"
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"

	"mygo-music/internal/devicebackup"
)

// ArchiveForBackup copies the installed package, including platform modules,
// without executing it or mixing its separate private data directory into code.
func (p *Plugin) ArchiveForBackup(ctx context.Context, output string) (err error) {
	files, err := devicebackup.Tree(p.dir, "package")
	if err != nil {
		return err
	}
	w, err := os.OpenFile(output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer func() {
		closeErr := w.Close()
		if err == nil {
			err = closeErr
		}
		if err != nil {
			_ = os.Remove(output)
		}
	}()
	archive := zip.NewWriter(w)
	var size int64
	for _, file := range files {
		if err = ctx.Err(); err != nil {
			return err
		}
		name, relErr := filepath.Rel(p.dir, file.Source)
		if relErr != nil {
			return relErr
		}
		if name == originFile || name == ".mb-origin.json" {
			continue
		}
		info, statErr := os.Lstat(file.Source)
		if statErr != nil {
			return statErr
		}
		size += info.Size()
		if !info.Mode().IsRegular() || size > maxUnpacked || len(files) > maxFiles {
			return errors.New("invalid backup plugin package")
		}
		header := &zip.FileHeader{Name: filepath.ToSlash(name), Method: zip.Deflate}
		header.SetMode(0o600)
		dst, writeErr := archive.CreateHeader(header)
		if writeErr != nil {
			return writeErr
		}
		src, openErr := os.Open(file.Source)
		if openErr != nil {
			return openErr
		}
		_, copyErr := io.Copy(dst, src)
		src.Close()
		if copyErr != nil {
			return copyErr
		}
	}
	return archive.Close()
}

// RestoreBackupData fills missing private files. Existing sessions on this
// device remain authoritative; restoring a blank device copies all files.
func RestoreBackupData(stage, target string) error {
	files, err := devicebackup.Tree(stage, "data")
	if err != nil {
		return err
	}
	for _, file := range files {
		rel, err := filepath.Rel(stage, file.Source)
		if err != nil {
			return err
		}
		to := filepath.Join(target, rel)
		if _, err := os.Lstat(to); err == nil {
			continue
		} else if !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		parent, err := filepath.Rel(target, filepath.Dir(to))
		if err != nil {
			return err
		}
		if parent == "." {
			parent = ""
		}
		if err := devicebackup.EnsureDirectory(target, parent); err != nil {
			return err
		}
		input, err := os.Open(file.Source)
		if err != nil {
			return err
		}
		output, err := os.OpenFile(to, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.Copy(output, input)
		closeErr := output.Close()
		input.Close()
		if copyErr != nil || closeErr != nil {
			_ = os.Remove(to)
			return errors.Join(copyErr, closeErr)
		}
	}
	return nil
}
