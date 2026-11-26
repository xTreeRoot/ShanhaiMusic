package plugin

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeSearch is GitHub's repository search with total repositories found, of
// which it answers a page; it keeps what was asked.
func fakeSearch(t *testing.T, total int, items string) *[]string {
	t.Helper()
	var asked []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.Query().Get("q")+" page="+r.URL.Query().Get("page"))
		fmt.Fprintf(w, `{"total_count":%d,"items":[%s]}`, total, items)
	}))
	t.Cleanup(srv.Close)
	was, wasToken := githubAPI, githubToken
	githubAPI, githubToken = srv.URL, func() string { return "" }
	t.Cleanup(func() { githubAPI, githubToken = was, wasToken })
	return &asked
}

// Community asks GitHub's search for repositories with the plugins' topic, and
// lists them by the names they're known by, without the app's in front.
func TestCommunity(t *testing.T) {
	asked := fakeSearch(t, 3, `
		{"full_name":"a/mygo-music-plugin-radio","name":"mygo-music-plugin-radio","description":null,"html_url":"https://github.com/a/mygo-music-plugin-radio","stargazers_count":7,"owner":{"login":"a","avatar_url":"https://avatars/a"}},
		{"full_name":"b/MyGo-Music-podcasts","name":"MyGo-Music-podcasts","description":"Podcasts","stargazers_count":2,"owner":{"login":"b"}},
		{"full_name":"c/mygo-music-","name":"mygo-music-","owner":{"login":"c"}}`)

	page, err := Community("", 1)
	if err != nil {
		t.Fatal(err)
	}
	if want := "topic:" + Topic + " archived:false page=1"; (*asked)[0] != want {
		t.Errorf("searched for %q", (*asked)[0])
	}
	list := page.Listings
	if len(list) != 3 || page.More {
		t.Fatalf("got %d plugins, more: %v", len(list), page.More)
	}
	if l := list[0]; l.Repo != "a/mygo-music-plugin-radio" || l.Name != "radio" || l.Owner != "a" || l.Avatar != "https://avatars/a" || l.Stars != 7 {
		t.Errorf("first is %+v", l)
	}
	if list[1].Name != "podcasts" || list[1].Description != "Podcasts" {
		t.Errorf("second is %+v", list[1])
	}
	if list[2].Name != "mygo-music-" {
		t.Errorf("a name that's only the app's became %q", list[2].Name)
	}
}

// A search goes in front of the topic, and a full page has more after it while
// GitHub found more, up to the thousand its search goes to.
func TestCommunityPages(t *testing.T) {
	item := `{"full_name":"a/p","name":"p","owner":{"login":"a"}}`
	full := strings.TrimSuffix(strings.Repeat(item+",", CommunityPerPage), ",")
	for _, tc := range []struct {
		total, page int
		more        bool
	}{
		{CommunityPerPage + 1, 1, true},
		{CommunityPerPage, 1, false},
		{5000, 1000 / CommunityPerPage, true},
		{5000, 1000/CommunityPerPage + 1, false},
	} {
		asked := fakeSearch(t, tc.total, full)
		page, err := Community(" radio ", tc.page)
		if err != nil {
			t.Fatal(err)
		}
		if page.More != tc.more {
			t.Errorf("page %d of %d: more is %v", tc.page, tc.total, page.More)
		}
		if want := fmt.Sprintf("radio topic:%s archived:false page=%d", Topic, tc.page); (*asked)[0] != want {
			t.Errorf("searched for %q", (*asked)[0])
		}
	}
}
