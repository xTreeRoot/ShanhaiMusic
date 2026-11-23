package plugin

import (
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/MoeForge/moejs"
)

const (
	maxPluginModules     = 256
	maxPluginModuleBytes = 16 << 20
)

func loadPluginModule(dir, entry string) (*moejs.Module, error) {
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	modules := make(map[string]*moejs.Module)
	remaining := int64(maxPluginModuleBytes)
	load := func(name string) (*moejs.Module, error) {
		if module := modules[name]; module != nil {
			return module, nil
		}
		if len(modules) >= maxPluginModules {
			return nil, fmt.Errorf("plugin has more than %d modules", maxPluginModules)
		}
		file, err := root.Open(filepath.FromSlash(name))
		if err != nil {
			return nil, err
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			return nil, err
		}
		if !info.Mode().IsRegular() {
			return nil, fmt.Errorf("plugin module %q is not a regular file", name)
		}
		if info.Size() > remaining {
			return nil, fmt.Errorf("plugin module source exceeds %d bytes", maxPluginModuleBytes)
		}
		source, err := io.ReadAll(io.LimitReader(file, remaining+1))
		if err != nil {
			return nil, err
		}
		remaining -= int64(len(source))
		if remaining < 0 {
			return nil, fmt.Errorf("plugin module source exceeds %d bytes", maxPluginModuleBytes)
		}
		compile := moejs.Compile
		if path.Ext(name) == ".ts" {
			compile = moejs.CompileTS
		}
		module, err := compile(name, string(source))
		if err == nil {
			modules[name] = module
		}
		return module, err
	}
	module, err := load(filepath.ToSlash(filepath.Clean(entry)))
	if err != nil {
		return nil, err
	}
	return moejs.Link(module, func(referrer moejs.Referrer, specifier string) (*moejs.Module, error) {
		if (!strings.HasPrefix(specifier, "./") && !strings.HasPrefix(specifier, "../")) || strings.Contains(specifier, "\\") {
			return nil, fmt.Errorf("expected a relative module path in the plugin")
		}
		name := path.Join(path.Dir(referrer.Name()), specifier)
		if !filepath.IsLocal(filepath.FromSlash(name)) {
			return nil, fmt.Errorf("module path leaves the plugin")
		}
		switch path.Ext(name) {
		case ".js", ".mjs", ".ts":
			return load(name)
		default:
			return nil, fmt.Errorf("expected a .js, .mjs, or .ts module")
		}
	})
}
