package plugin

import (
	"context"
	"encoding/json"
	"errors"
	"mygo-music/internal/devicebackup"
	"os"
	"path/filepath"
)

// ReplaceBackupData replaces one explicitly selected plugin's private directory.
// The complete replacement is prepared before moving the existing data.
func ReplaceBackupData(ctx context.Context, source, target string, settings map[string]string) error {
	parent := filepath.Dir(target)
	if err := devicebackup.EnsureDirectory(parent, ""); err != nil {
		return err
	}
	if info, err := os.Lstat(target); err == nil {
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("invalid plugin data directory")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	next, err := os.MkdirTemp(parent, ".plugin-restore-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(next)
	if err := RestoreBackupData(source, next); err != nil {
		return err
	}
	body, err := json.Marshal(settings)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(next, "settings.json"), body, 0o600); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	previous, moved := next+"-previous", false
	if err := os.Rename(target, previous); err == nil {
		moved = true
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.Rename(next, target); err != nil {
		if moved {
			return errors.Join(err, os.Rename(previous, target))
		}
		return err
	}
	if moved {
		return os.RemoveAll(previous)
	}
	return nil
}
