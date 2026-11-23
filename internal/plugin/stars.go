package plugin

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/html"
	"mygo-music/internal/network"
)

const maxCatalogPage = 4 << 20

var catalogClient = &http.Client{Transport: network.Transport(), Timeout: 25 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
	if !catalogWebURL(req.URL) || len(via) >= 8 || (via[len(via)-1].URL.Scheme == "https" && req.URL.Scheme == "http") {
		return http.ErrUseLastResponse
	}
	return nil
}}

func catalogWebURL(address *url.URL) bool {
	return address != nil && (address.Scheme == "https" || address.Scheme == "http") && address.Hostname() != "" && address.User == nil
}

func githubCatalogHost(address *url.URL) bool {
	return strings.EqualFold(address.Hostname(), "github.com") && (address.Port() == "" || address.Port() == "443" && address.Scheme == "https" || address.Port() == "80" && address.Scheme == "http")
}

func isStarsCatalog(address *url.URL) bool {
	parts := strings.Split(strings.TrimPrefix(address.Path, "/"), "/")
	return githubCatalogHost(address) && len(parts) >= 4 && parts[0] == "stars" && parts[2] == "lists"
}

func starsListURL(input string) (*url.URL, error) {
	text := strings.TrimSpace(input)
	if !strings.Contains(text, "://") && strings.Contains(strings.SplitN(text, "/", 2)[0], ".") {
		text = "https://" + text
	}
	address, err := url.Parse(text)
	if err != nil || !catalogWebURL(address) {
		return nil, errors.New("enter a GitHub repository or a web address containing plugin links")
	}
	address.Fragment, address.RawFragment = "", ""
	return address, nil
}

func StarsRepositories(ctx context.Context, input string) ([]string, error) {
	if repo := catalogRepository(input, nil); repo != "" {
		return []string{repo}, nil
	}
	address, err := starsListURL(input)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address.String(), nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "text/html, */*;q=0.8")
	request.Header.Set("User-Agent", "MB-Desktop")
	response, err := catalogClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("plugin catalog: HTTP %d", response.StatusCode)
	}
	page, err := io.ReadAll(io.LimitReader(response.Body, maxCatalogPage+1))
	if err != nil {
		return nil, err
	}
	if len(page) > maxCatalogPage {
		return nil, errors.New("plugin catalog page is too large")
	}
	if repo := catalogRepository(response.Request.URL.String(), nil); repo != "" {
		return []string{repo}, nil
	}
	return parseStarsRepositories(bytes.NewReader(page), response.Request.URL)
}

func parseStarsRepositories(reader io.Reader, address *url.URL) ([]string, error) {
	doc, err := html.Parse(reader)
	if err != nil {
		return nil, err
	}
	stars := isStarsCatalog(address)
	list := doc
	if stars {
		for node := range doc.Descendants() {
			if node.Type == html.ElementNode && htmlAttr(node, "id") == "user-list-repositories" {
				list = node
				break
			}
		}
		if list == doc {
			return nil, errors.New("GitHub Stars repository list not found")
		}
	}
	var repos []string
	seen := map[string]bool{"xtreeroot/mb": true, "xtreeroot/shanhaimusic": true, "xtreeroot/mb-plugin-template": true}
	for node := range list.Descendants() {
		if node.Type != html.ElementNode || node.Data != "a" {
			continue
		}
		if stars && (node.Parent.Data != "h2" || !strings.Contains(" "+strings.Join(strings.Fields(htmlAttr(node.Parent, "class")), " ")+" ", " h3 ")) {
			continue
		}
		repo := catalogRepository(htmlAttr(node, "href"), address)
		if repo == "" {
			continue
		}
		key := strings.ToLower(repo)
		if !seen[key] {
			seen[key] = true
			repos = append(repos, repo)
			if len(repos) > 32 {
				return nil, errors.New("import supports up to 32 repositories per batch")
			}
		}
	}
	if len(repos) == 0 {
		return nil, errors.New("no plugin repository links found")
	}
	return repos, nil
}

func catalogRepository(input string, base *url.URL) string {
	input = strings.TrimSpace(input)
	if input == "" || strings.HasPrefix(input, "#") || strings.HasPrefix(input, "?") {
		return ""
	}
	address, err := url.Parse(input)
	if err != nil {
		return ""
	}
	if (address.Scheme == "mb" || address.Scheme == "mygo-music") && address.Host == "install-plugin" && address.User == nil &&
		(address.Path == "" || address.Path == "/") && address.Fragment == "" {
		if values := address.Query()["repo"]; len(values) == 1 && repoName.MatchString(values[0]) {
			return catalogRepository(values[0], nil)
		}
		return ""
	}
	if base != nil {
		address = base.ResolveReference(address)
	} else if strings.HasPrefix(strings.ToLower(input), "github.com/") {
		address, _ = url.Parse("https://" + input)
	} else if address.Scheme == "" && address.Host == "" && !strings.Contains(strings.SplitN(input, "/", 2)[0], ".") && repoName.MatchString(address.Path) && address.Path == input {
		address = &url.URL{Scheme: "https", Host: "github.com", Path: "/" + address.Path}
	}
	if !catalogWebURL(address) || !githubCatalogHost(address) || isStarsCatalog(address) {
		return ""
	}
	path := strings.TrimPrefix(address.Path, "/")
	parts := strings.Split(strings.TrimSuffix(path, "/"), "/")
	if len(parts) < 2 || parts[0] == "." || parts[1] == "." {
		return ""
	}
	if len(parts) > 2 {
		switch parts[2] {
		case "releases", "tree", "blob":
		default:
			return ""
		}
	}
	repo := parts[0] + "/" + strings.TrimSuffix(parts[1], ".git")
	if !repoName.MatchString(repo) || strings.Contains(repo, "..") {
		return ""
	}
	return repo
}

func htmlAttr(node *html.Node, key string) string {
	for _, attr := range node.Attr {
		if attr.Key == key {
			return attr.Val
		}
	}
	return ""
}
