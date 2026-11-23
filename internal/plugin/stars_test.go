package plugin

import (
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
)

func TestStarsRepositoriesIgnoresOtherPageLinksAndHost(t *testing.T) {
	markup := `<h2 class="h3"><a href="/someone/outside">Outside</a></h2>
<div id="user-list-repositories">
  <h2 class="other h3"><a href="/xTreeRoot/mygo-music-soda"><span>owner / </span>soda</a></h2>
  <h2 class="h3"><a href="/xtreeroot/MYGO-MUSIC-SODA">Duplicate</a></h2>
  <h2 class="h3"><a href="/XTreeroot/mb">Host app</a></h2>
  <h2 class="h3"><a href="/XTReeRoot/MB-PLUGIN-TEMPLATE">开发模板</a></h2>
  <h2 class="h3"><a href="/other/mb-plugin-template">Same name, different owner</a></h2>
  <h2 class="h3"><a href="/xTreeRoot/mb-plugin-template-extra">Distinct plugin</a></h2>
  <p><a href="/owner/not-a-heading">Mention</a></p>
  <h2 class="h3"><a href="//other.test/foo">External</a></h2>
  <h2 class="h3"><a href="/xTreeRoot/mygo-ximalaya">Ximalaya</a></h2>
</div><h2 class="h3"><a href="/xTreeRoot/other">Other</a></h2>`
	want := []string{"xTreeRoot/mygo-music-soda", "other/mb-plugin-template", "xTreeRoot/mb-plugin-template-extra", "xTreeRoot/mygo-ximalaya"}
	if page := os.Getenv("MB_STARS_HTML_FIXTURE"); page != "" {
		bytes, err := os.ReadFile(page)
		if err != nil {
			t.Fatal(err)
		}
		markup = string(bytes)
		want = []string{"xTreeRoot/mygo-music-soda", "xTreeRoot/mygo-ximalaya", "xTreeRoot/mygo-music-youtube-music", "xTreeRoot/mygo-music-netease"}
	}
	address, err := starsListURL("https://github.com/stars/example/lists/fixture")
	if err != nil {
		t.Fatal(err)
	}
	repos, err := parseStarsRepositories(strings.NewReader(markup), address)
	if err != nil || !slices.Equal(repos, want) {
		t.Fatalf("list parsed as %v, err %v", repos, err)
	}
	if _, err := parseStarsRepositories(strings.NewReader(`<h1>Sign in</h1>`), address); err == nil {
		t.Fatal("a non-list page reported success")
	}
}

func TestPluginCatalogAcceptsWebAddressesWithoutDroppingQuery(t *testing.T) {
	address, err := starsListURL(" catalog.test/plugins/?group=audio#details ")
	if err != nil || address.String() != "https://catalog.test/plugins/?group=audio" {
		t.Fatalf("address %v, error %v", address, err)
	}
	for _, input := range []string{"https://github.com@other.test/stars/a/lists/b", "file:///tmp/plugin.zip", "not a URL"} {
		if _, err := starsListURL(input); err == nil {
			t.Fatalf("accepted %q", input)
		}
	}
}

func TestPluginCatalogReadsRedirectedPageAndDirectLinks(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path == "/start" {
			http.Redirect(writer, request, "/plugins", http.StatusFound)
			return
		}
		_, _ = writer.Write([]byte(`<a href="mb://install-plugin?repo=Owner%2FAudio&amp;from=catalog">Install</a>
<a href="//github.com/owner/audio/releases">Duplicate</a>
<a href="https://github.com/Other/Video/releases/download/v1/plugin-universal.zip">Download</a>
<a href="https://github.com.fake.test/Other/Fake">Fake</a>`))
	}))
	defer server.Close()
	result, err := StarsRepositories(t.Context(), server.URL+"/start")
	if err != nil || !slices.Equal(result, []string{"Owner/Audio", "Other/Video"}) {
		t.Fatalf("catalog parsed as %v, err %v", result, err)
	}
	for _, input := range []string{"Owner/Audio", " github.com/Owner/Audio/releases ", "mb://install-plugin?repo=Owner%2FAudio"} {
		result, err := StarsRepositories(t.Context(), input)
		if err != nil || !slices.Equal(result, []string{"Owner/Audio"}) {
			t.Fatalf("%q parsed as %v, err %v", input, result, err)
		}
	}
}
