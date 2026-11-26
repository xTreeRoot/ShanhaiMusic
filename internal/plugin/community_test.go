package plugin

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestCommunityUsesMaintainedCatalogAndCachesIt(t *testing.T) {
	wasRepos, wasAPI, wasToken := communityRepositories, githubAPI, githubToken
	communityCache.Lock()
	wasList, wasAt := communityCache.list, communityCache.at
	communityCache.list, communityCache.at = nil, time.Time{}
	communityCache.Unlock()
	t.Cleanup(func() {
		communityRepositories, githubAPI, githubToken = wasRepos, wasAPI, wasToken
		communityCache.Lock()
		communityCache.list, communityCache.at = wasList, wasAt
		communityCache.Unlock()
	})
	calls := 0
	communityRepositories = func(_ context.Context, address string) ([]string, error) {
		calls++
		if address != CommunityCatalog {
			t.Errorf("wrong catalog %q", address)
		}
		return []string{"xTreeRoot/mygo-music-radio", "xTreeRoot/mygo-music-video"}, nil
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/repos/xTreeRoot/mygo-music-radio" && r.URL.Path != "/repos/xTreeRoot/mygo-music-video" {
			t.Errorf("queried outside maintained repositories: %s", r.URL.Path)
		}
		fmt.Fprint(w, `{"description":"maintained plugin","stargazers_count":3}`)
	}))
	defer server.Close()
	githubAPI = server.URL
	githubToken = func() string { return "" }
	page, err := Community("radio", 1)
	if err != nil || len(page.Listings) != 1 || page.Listings[0].Repo != "xTreeRoot/mygo-music-radio" {
		t.Fatalf("page=%+v, err=%v", page, err)
	}
	all, err := Community("", 1)
	if err != nil || len(all.Listings) != 2 || calls != 1 {
		t.Fatalf("list=%+v, calls=%d, err=%v", all, calls, err)
	}
}

func TestCommunityPages(t *testing.T) {
	var list []Listing
	for i := range 32 {
		list = append(list, Listing{Repo: fmt.Sprintf("owner/plugin-%d", i), Name: fmt.Sprintf("Plugin %d", i)})
	}
	first := communityPage(list, "", 1)
	second := communityPage(list, "", 2)
	if len(first.Listings) != 30 || !first.More || len(second.Listings) != 2 || second.More {
		t.Fatal("incorrect maintained-list pagination")
	}
	if got := communityPage(list, "PLUGIN 31", 1); len(got.Listings) != 1 || got.Listings[0].Repo != "owner/plugin-31" {
		t.Fatalf("search: %+v", got)
	}
	if got := communityPage(list, "not-in-list", 1); len(got.Listings) != 0 || got.More {
		t.Fatalf("search escaped catalog: %+v", got)
	}
}

func TestMaintainedCommunityLive(t *testing.T) {
	if os.Getenv("MB_COMMUNITY_LIVE") != "1" {
		t.Skip("explicit public maintained-catalog validation")
	}
	repos, err := StarsRepositories(context.Background(), CommunityCatalog)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Community("", 1)
	if err != nil {
		t.Fatal(err)
	}
	allowed := map[string]bool{}
	for _, repo := range repos {
		allowed[strings.ToLower(repo)] = true
	}
	if len(got.Listings) == 0 {
		t.Fatal("empty maintained catalog")
	}
	for _, item := range got.Listings {
		if !allowed[strings.ToLower(item.Repo)] {
			t.Fatalf("outside catalog: %s", item.Repo)
		}
		t.Log(item.Repo)
	}
}
