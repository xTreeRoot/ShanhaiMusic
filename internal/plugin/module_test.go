package plugin

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestPluginLinksPackagedModules(t *testing.T) {
	dir, _ := write(t, plain, `export { suggest } from "./lib/suggest.js";`)
	for name, source := range map[string]string{
		"state.ts":       `let calls: number = 0; export const next = () => ++calls;`,
		"lib/suggest.js": `import { next } from "../state.ts"; import { alias } from "./alias.mjs"; export const suggest = () => [String(next()), String(alias())];`,
		"lib/alias.mjs":  `export { next as alias } from "../state.ts";`,
	} {
		file := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, []byte(source), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	installed := t.TempDir()
	pending, err := Open(dir, installed)
	if err != nil {
		t.Fatal(err)
	}
	defer pending.Discard()
	if err := pending.Install(installed); err != nil {
		t.Fatal(err)
	}
	plugin, err := Load(filepath.Join(installed, "x"), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer plugin.Close()
	if got, err := plugin.Suggest(""); err != nil || !slices.Equal(got, []string{"1", "2"}) {
		t.Fatalf("linked exports did not share one module instance: %v, %v", got, err)
	}
}

func TestPluginModuleResolutionStaysInPackage(t *testing.T) {
	for _, specifier := range []string{"../outside.js", "./missing.js", "./linked.js", "https://example.com/other.js", "other-package", "./plugin.json"} {
		t.Run(specifier, func(t *testing.T) {
			dir, data := write(t, plain, `import "`+specifier+`";`)
			outside := filepath.Join(filepath.Dir(dir), "outside.js")
			if err := os.WriteFile(outside, []byte(`export const value = 1;`), 0o600); err != nil {
				t.Fatal(err)
			}
			if specifier == "./linked.js" {
				if err := os.Symlink(outside, filepath.Join(dir, "linked.js")); err != nil {
					t.Skipf("cannot create symlink: %v", err)
				}
			}
			if _, err := Load(dir, data); err == nil || !strings.Contains(err.Error(), specifier) {
				t.Fatalf("expected the import to be rejected while loading: %v", err)
			}
		})
	}
}
