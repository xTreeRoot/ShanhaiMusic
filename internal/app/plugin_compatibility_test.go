package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
	"mygo-music/internal/plugin"
)

// Both community and MB links download a plugin for confirmation. Development
// and installed apps accept only their own pair of schemes.
func TestCommunityPluginInstallLinks(t *testing.T) {
	was := fetchPlugin
	t.Cleanup(func() { fetchPlugin = was })
	for _, mode := range []struct {
		env, suffix, otherSuffix string
	}{
		{"production", "", "-dev"},
		{"development", "-dev", ""},
	} {
		t.Run(mode.env, func(t *testing.T) {
			t.Setenv("MYGO_ENV", mode.env)
			for _, scheme := range []string{"mb", "mygo-music"} {
				for _, separator := range []string{"://", ":"} {
					t.Run(scheme+separator, func(t *testing.T) {
						asked := make(chan string, 1)
						fetchPlugin = func(repo, dir string) (*plugin.Pending, error) {
							asked <- repo
							return &plugin.Pending{Manifest: plugin.Manifest{Main: "index.js"}}, nil
						}
						a := newTestApp()
						post := posts(a)
						libs := len(a.allLibs)
						a.openLink(strings.ToUpper(scheme+mode.suffix) + separator + "install-plugin?repo=owner/repo")
						if got := wait(t, asked, "community plugin download"); got != "owner/repo" {
							t.Fatalf("downloaded %q", got)
						}
						runPost(t, post)
						if a.route.page != pagePlugins || !a.installer.open || a.installer.pending == nil {
							t.Fatal("link did not show the pending plugin for confirmation")
						}
						if len(a.allLibs) != libs {
							t.Fatal("link installed a plugin without confirmation")
						}
					})
				}
			}
			for _, link := range []string{
				"mb" + mode.otherSuffix + "://install-plugin?repo=owner/repo",
				"mygo-music" + mode.otherSuffix + "://install-plugin?repo=owner/repo",
				"https://example.com/install-plugin?repo=owner/repo",
				"mygo-music" + mode.suffix + "://settings?repo=owner/repo",
				"mygo-music" + mode.suffix + "://install-plugin",
				"mygo-music" + mode.suffix + "://install-plugin?repo=%zz",
			} {
				t.Run(link, func(t *testing.T) {
					fetchPlugin = func(repo, dir string) (*plugin.Pending, error) {
						t.Error("download started for an unsupported link")
						return nil, errors.New("unexpected download")
					}
					a := newTestApp()
					posts(a)
					a.openLink(link)
					if a.installer.open || a.installer.busy || a.route.page == pagePlugins {
						t.Fatal("unsupported link opened the installer")
					}
				})
			}
		})
	}
}

// The community website's Qishui URL must queue its repository for confirmation.
func TestQishuiCommunityInstallLink(t *testing.T) {
	t.Setenv("MYGO_ENV", "production")
	was := fetchPlugin
	t.Cleanup(func() { fetchPlugin = was })
	asked := make(chan string, 1)
	fetchPlugin = func(repo, dir string) (*plugin.Pending, error) {
		asked <- repo
		return &plugin.Pending{
			Release: plugin.Release{Repo: repo},
			Manifest: plugin.Manifest{
				Info: music.Info{ID: "qishui-app-login", Name: "Qishui"},
				Main: "index.js",
			},
		}, nil
	}
	a := newTestApp()
	post := posts(a)
	a.openLink("mygo-music://install-plugin?repo=lane128/mygo-music-qishui")
	if got := wait(t, asked, "Qishui plugin download"); got != "lane128/mygo-music-qishui" {
		t.Fatalf("downloaded %q", got)
	}
	runPost(t, post)
	if a.route.page != pagePlugins || !a.installer.open || a.installer.pending == nil {
		t.Fatal("Qishui download did not open the install confirmation")
	}
	tt := ui.NewTester(a.view, 1200, 800)
	settled(tt)
	if _, ok := tt.AccessByRole(ui.RoleButton, Tf("Install %s", "Qishui")); !ok {
		t.Fatal("download did not expose its installation action")
	}
	if err := tt.Click(T("Cancel")); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.installer.open || a.installer.pending != nil {
		t.Fatal("cancelling the linked plugin did not dismiss the pending installation")
	}
}

// Community minimum versions are compared with the adopted MyGo Music host
// contract, while plugins needing a newer contract remain blocked.
func TestCommunityPluginVersionGate(t *testing.T) {
	plugin.SetAppVersion(pluginCompatibilityVersion)
	t.Cleanup(func() { plugin.SetAppVersion("") })
	for _, version := range []string{"0.1.8", "0.1.21", "0.1.25", "0.1.26", "0.1.27"} {
		t.Run(version, func(t *testing.T) {
			dir := t.TempDir()
			manifest := map[string]string{"id": "community", "name": "Community", "minAppVersion": version}
			b, err := json.Marshal(manifest)
			if err != nil {
				t.Fatal(err)
			}
			for name, contents := range map[string][]byte{
				"plugin.json": b,
				"index.js":    []byte(`export function suggest() { return [host.platform]; }`),
			} {
				if err := os.WriteFile(filepath.Join(dir, name), contents, 0o600); err != nil {
					t.Fatal(err)
				}
			}
			installed := t.TempDir()
			pending, err := plugin.Open(dir, installed)
			if version == "0.1.27" {
				var needs *plugin.NeedsNewerApp
				if !errors.As(err, &needs) || needs.Version != version {
					t.Fatalf("newer host requirement was not enforced: %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer pending.Discard()
			if err := pending.Install(installed); err != nil {
				t.Fatal(err)
			}
			p, err := plugin.Load(filepath.Join(installed, "community"), t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			defer p.Close()
			if suggestions, err := p.Suggest(""); err != nil || len(suggestions) != 1 || suggestions[0] == "" {
				t.Fatalf("installed plugin cannot use its host: %v, %v", suggestions, err)
			}
		})
	}
}

// Optional live archive validation compiles downloaded community plugins without
// executing their code or accessing an account. Normal tests require no network.
func TestCommunityPluginArchives(t *testing.T) {
	root := os.Getenv("MB_COMMUNITY_PLUGIN_ROOT")
	if root == "" {
		t.Skip("set MB_COMMUNITY_PLUGIN_ROOT to unpacked community plugin folders")
	}
	plugin.SetAppVersion(pluginCompatibilityVersion)
	t.Cleanup(func() { plugin.SetAppVersion("") })
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	checked := 0
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		checked++
		t.Run(entry.Name(), func(t *testing.T) {
			pending, err := plugin.Open(filepath.Join(root, entry.Name()), t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			defer pending.Discard()
			t.Logf("compiled %s %s (minimum host %s)", pending.Manifest.ID, pending.Manifest.Version, pending.Manifest.MinAppVersion)
		})
	}
	if checked == 0 {
		t.Fatal("no community plugin folders found")
	}
}
