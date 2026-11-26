package plugin

import (
	"context"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

// CommunityCatalog is the maintained Shanhai Stars list, shared by all clients.
const CommunityCatalog = "https://github.com/stars/xTreeRoot/lists/fork-mygo-%E5%B1%B1%E6%B5%B7-%E8%87%AA%E8%A1%8C%E7%BB%B4%E6%8A%A4%E4%BB%93%E5%BA%93-%E5%A4%9A%E5%B9%B3%E5%8F%B0"
const CommunityPerPage = 30

type Listing struct {
	Repo        string
	Name        string
	Owner       string
	Avatar      string
	Description string
	URL         string
	Stars       int
}
type CommunityPage struct {
	Listings []Listing
	More     bool
}

var communityRepositories = StarsRepositories
var communityCache struct {
	sync.Mutex
	list []Listing
	at   time.Time
}

// Community searches only the maintained list. Metadata cannot add repositories.
func Community(query string, page int) (*CommunityPage, error) {
	communityCache.Lock()
	defer communityCache.Unlock()
	if communityCache.at.IsZero() || time.Since(communityCache.at) >= time.Hour {
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		defer cancel()
		repos, err := communityRepositories(ctx, CommunityCatalog)
		if err != nil {
			return nil, err
		}
		list := make([]Listing, len(repos))
		var jobs sync.WaitGroup
		gate := make(chan struct{}, 4)
		for i, repo := range repos {
			jobs.Add(1)
			go func() {
				defer jobs.Done()
				gate <- struct{}{}
				defer func() { <-gate }()
				owner, name, _ := strings.Cut(repo, "/")
				item := Listing{Repo: repo, Name: ShortName(name), Owner: owner, Avatar: "https://github.com/" + owner + ".png?size=120", Description: repo, URL: "https://github.com/" + repo}
				var metadata struct {
					Description string
					Stars       int `json:"stargazers_count"`
				}
				if status, err := githubGet(ctx, "/repos/"+repo, &metadata); err == nil && status == http.StatusOK {
					if metadata.Description != "" {
						item.Description = metadata.Description
					}
					item.Stars = metadata.Stars
				}
				list[i] = item
			}()
		}
		jobs.Wait()
		communityCache.list, communityCache.at = list, time.Now()
	}
	return communityPage(communityCache.list, query, page), nil
}

func communityPage(list []Listing, query string, page int) *CommunityPage {
	query = strings.ToLower(strings.TrimSpace(query))
	var filtered []Listing
	for _, item := range list {
		if query == "" || strings.Contains(strings.ToLower(item.Name+"\n"+item.Repo+"\n"+item.Description), query) {
			filtered = append(filtered, item)
		}
	}
	start := min((max(1, page)-1)*CommunityPerPage, len(filtered))
	end := min(start+CommunityPerPage, len(filtered))
	return &CommunityPage{Listings: filtered[start:end], More: end < len(filtered)}
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
