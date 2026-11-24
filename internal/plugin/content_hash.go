package plugin

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"regexp"
	"sort"
)

var portableContentPath = regexp.MustCompile(`^[A-Za-z0-9_./@+ -]+$`)

func pluginContentHash(directory string) string {
	root, err := os.OpenRoot(directory)
	if err != nil {
		return ""
	}
	defer root.Close()
	files := make(map[string][]byte)
	remaining := int64(32 << 20)
	err = fs.WalkDir(root.FS(), ".", func(name string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || name == ".origin.json" || name == ".mb-origin.json" {
			return nil
		}
		if len(files) >= 1024 || !portableContentPath.MatchString(name) || !entry.Type().IsRegular() {
			return fs.ErrInvalid
		}
		file, err := root.Open(name)
		if err != nil {
			return err
		}
		digest := sha256.New()
		size, err := io.Copy(digest, io.LimitReader(file, remaining+1))
		if err := errors.Join(err, file.Close()); err != nil {
			return err
		}
		if size > remaining {
			return fs.ErrInvalid
		}
		remaining -= size
		files[name] = digest.Sum(nil)
		return nil
	})
	if err != nil || len(files) == 0 {
		return ""
	}
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	hash := sha256.New()
	for _, name := range names {
		io.WriteString(hash, name+"\x00")
		hash.Write(files[name])
	}
	return hex.EncodeToString(hash.Sum(nil))
}
