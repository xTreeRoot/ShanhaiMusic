package plugin

import (
	"os"
	"path/filepath"
	"testing"
)

func TestContentHashIgnoresInstallerMetadataButRejectsChangedCode(t *testing.T) {
	directory := t.TempDir()
	write := func(name, value string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(directory, name), []byte(value), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write("plugin.json", "{}")
	write("index.js", "example")
	write(".origin.json", "installer metadata")
	const expected = "390039247f1da0ab6446e475561ec319f02d20fe8fca759719d02e75358c4bee"
	if got := pluginContentHash(directory); got != expected {
		t.Fatalf("got %q, want %q", got, expected)
	}
	write("index.js", "changed")
	if got := pluginContentHash(directory); got == "" || got == expected {
		t.Fatal("content change did not produce a new digest")
	}
}
