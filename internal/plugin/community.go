package plugin

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

// Topic is the topic on GitHub that the README asks plugins to have, which is
// how others find them: the website's plugins page lists them, and the app's
// Plugins page.
const Topic = "mygo-music-plugin"

// Listing is a plugin someone published: a public repository with the topic.
type Listing struct {
	Repo        string // owner/repo, to install it from
	Name        string // the repository's name, without the app's in front
	Owner       string
	Avatar      string // the owner's picture
	Description string
	URL         string // its page on GitHub
	Stars       int
}

// CommunityPerPage is how many plugins a page of Community has.
const CommunityPerPage = 30

// communityLimit is how far GitHub's search goes: its first thousand results.
const communityLimit = 1000

// CommunityPage is a page of the plugins on GitHub, and whether there are more.
type CommunityPage struct {
	Listings []Listing
	More     bool
}

// Community lists the plugins on GitHub that query finds, by their names and
// what they say of themselves, or all of them without one: the most starred
// first, as the website does, CommunityPerPage at a time from page 1. GitHub's
// search allows few requests without the listener's token.
func Community(query string, page int) (*CommunityPage, error) {
	var answer struct {
		Total int `json:"total_count"`
		Items []struct {
			FullName    string `json:"full_name"`
			Name        string `json:"name"`
			Description string `json:"description"`
			HTMLURL     string `json:"html_url"`
			Stars       int    `json:"stargazers_count"`
			Owner       struct {
				Login  string `json:"login"`
				Avatar string `json:"avatar_url"`
			} `json:"owner"`
		} `json:"items"`
	}
	q := strings.TrimSpace(strings.TrimSpace(query) + " topic:" + Topic + " archived:false")
	path := fmt.Sprintf("/search/repositories?q=%s&sort=stars&per_page=%d&page=%d", url.QueryEscape(q), CommunityPerPage, max(page, 1))
	status, err := githubGet(context.Background(), path, &answer)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, fmt.Errorf("GitHub answered %d", status)
	}
	list := make([]Listing, 0, len(answer.Items))
	for _, r := range answer.Items {
		list = append(list, Listing{
			Repo:        r.FullName,
			Name:        ShortName(r.Name),
			Owner:       r.Owner.Login,
			Avatar:      r.Owner.Avatar,
			Description: r.Description,
			URL:         r.HTMLURL,
			Stars:       r.Stars,
		})
	}
	seen := max(page, 1) * CommunityPerPage
	more := len(list) == CommunityPerPage && seen < min(answer.Total, communityLimit)
	return &CommunityPage{Listings: list, More: more}, nil
}

var appPrefix = regexp.MustCompile(`(?i)^mygo-music-(plugin-)?`)

// ShortName is a plugin's name without the app's in front, as repositories tend
// to be named: mygo-music-youtube-music is "youtube-music".
func ShortName(name string) string {
	if s := appPrefix.ReplaceAllString(name, ""); s != "" {
		return s
	}
	return name
}
