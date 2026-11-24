package edge

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
)

const envelopeLimit = 128 << 10

var encoding = base64.RawURLEncoding.Strict()
var identifier = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,63}$`)
var errNodeUnavailable = errors.New("cannot reach MB Server")

type StatusError struct {
	Code       int
	Kind       string
	RetryAfter time.Duration
}

func (err *StatusError) Error() string { return fmt.Sprintf("MB Server: HTTP %d", err.Code) }

type Client struct {
	origin string
	http   *http.Client
}

func newClient(origin string) *Client {
	transport := &http.Transport{ForceAttemptHTTP2: true}
	if existing, ok := http.DefaultTransport.(*http.Transport); ok {
		transport = existing.Clone()
	}
	transport.Proxy = nil
	return &Client{origin: origin, http: &http.Client{
		Timeout:       25 * time.Second,
		Transport:     transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

func NormalizeAddress(address string) (string, error) {
	address = strings.TrimSpace(address)
	if !strings.Contains(address, "://") {
		address = "https://" + address
	}
	u, err := url.Parse(address)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.User != nil || u.ForceQuery || u.RawQuery != "" || strings.Contains(address, "#") || u.RawPath != "" || u.Path != "" && u.Path != "/" {
		return "", errors.New("Enter a server domain or HTTPS address without a path")
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if host == "" || strings.Contains(host, "%") || strings.ContainsFunc(host, func(character rune) bool { return character > unicode.MaxASCII }) {
		return "", errors.New("Enter a valid server domain")
	}
	if port := u.Port(); port != "" {
		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 {
			return "", errors.New("Invalid server port")
		}
		if number != 443 {
			return "https://" + net.JoinHostPort(host, strconv.Itoa(number)), nil
		}
	}
	if strings.Contains(host, ":") {
		if net.ParseIP(host) == nil {
			return "", errors.New("Enter a valid server domain")
		}
		host = "[" + host + "]"
	}
	return "https://" + host, nil
}

type Grant struct {
	DeviceID  string `json:"device_id"`
	SpaceID   string `json:"space_id"`
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expires_at"`
}

type SessionInfo struct {
	Ref     string `json:"account_ref"`
	ETag    string `json:"etag"`
	Format  int    `json:"format"`
	Deleted bool   `json:"deleted"`
}

type Envelope struct {
	Format     int    `json:"format"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

func (client *Client) request(ctx context.Context, method, path, token, etag string, body []byte, limit int64, expected ...int) ([]byte, http.Header, error) {
	request, err := http.NewRequestWithContext(ctx, method, client.origin+path, bytes.NewReader(body))
	if err != nil {
		return nil, nil, err
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if etag == "*" {
		request.Header.Set("If-None-Match", etag)
	} else if etag != "" {
		request.Header.Set("If-Match", etag)
	}
	httpClient := client.http
	if strings.HasPrefix(path, "/v1/backups/") {
		copy := *httpClient
		copy.Timeout = 90 * time.Second
		httpClient = &copy
	}
	response, err := httpClient.Do(request)
	if err != nil {
		return nil, nil, errors.Join(errNodeUnavailable, ctx.Err())
	}
	defer response.Body.Close()
	for _, code := range expected {
		if response.StatusCode != code {
			continue
		}
		data, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
		if err != nil || int64(len(data)) > limit {
			return nil, nil, errors.New("invalid MB Server response size")
		}
		return data, response.Header, nil
	}
	retry, _ := strconv.ParseInt(response.Header.Get("Retry-After"), 10, 32)
	var failure struct {
		Error string `json:"error"`
	}
	if strings.HasPrefix(response.Header.Get("Content-Type"), "application/json") {
		data, _ := io.ReadAll(io.LimitReader(response.Body, 1025))
		_ = json.Unmarshal(data, &failure)
	}
	return nil, response.Header, &StatusError{Code: response.StatusCode, Kind: failure.Error, RetryAfter: time.Duration(max(retry, 0)) * time.Second}
}

func validBase64(value string, size int) bool {
	decoded, err := encoding.DecodeString(value)
	return err == nil && len(decoded) == size && encoding.EncodeToString(decoded) == value
}

func validTag(value string) bool {
	return len(value) == 24 && value[0] == '"' && value[23] == '"' && validBase64(value[1:23], 16)
}

type nodeGrant struct {
	Grant
	Recovery string `json:"recovery_key"`
	Key      string `json:"catalog_public_key"`
}

func (client *Client) join(ctx context.Context, password string) (nodeGrant, error) {
	var grant nodeGrant
	body, _ := json.Marshal(map[string]string{"password": password, "name": "MB Desktop", "platform": "desktop"})
	if len(body) > envelopeLimit {
		return grant, errors.New("Node password is too long")
	}
	data, _, err := client.request(ctx, http.MethodPost, "/v1/join", "", "", body, 2048, http.StatusCreated)
	if err != nil {
		return grant, err
	}
	if json.Unmarshal(data, &grant) != nil || !identifier.MatchString(grant.SpaceID) || !validBase64(grant.DeviceID, 16) || !validBase64(grant.Token, 32) || !validBase64(grant.Recovery, 32) || !validBase64(grant.Key, 32) || grant.ExpiresAt <= time.Now().Unix() {
		return nodeGrant{}, errors.New("invalid MB node grant")
	}
	return grant, nil
}

func sessionPath(plugin, ref string) (string, error) {
	if !identifier.MatchString(plugin) || ref != "" && !validBase64(ref, 32) {
		return "", errors.New("invalid MB session address")
	}
	path := "/v1/sessions/" + plugin
	if ref != "" {
		path += "/" + ref
	}
	return path, nil
}

func (client *Client) list(ctx context.Context, grant Grant, plugin string) ([]SessionInfo, error) {
	path, err := sessionPath(plugin, "")
	if err != nil {
		return nil, err
	}
	data, _, err := client.request(ctx, http.MethodGet, path, grant.Token, "", nil, 1<<20, http.StatusOK)
	if err != nil {
		return nil, err
	}
	var result struct{ Sessions []SessionInfo }
	if err := json.Unmarshal(data, &result); err != nil || result.Sessions == nil {
		return nil, errors.New("invalid MB session list")
	}
	seen := map[string]bool{}
	for _, session := range result.Sessions {
		if !validBase64(session.Ref, 32) || !validTag(session.ETag) || session.Format < 0 || session.Format > 65535 || (session.Format == 0) != session.Deleted || seen[session.Ref] {
			return nil, errors.New("invalid MB session metadata")
		}
		seen[session.Ref] = true
	}
	return result.Sessions, nil
}

func (client *Client) get(ctx context.Context, grant Grant, plugin, ref string) (Envelope, string, error) {
	var envelope Envelope
	path, err := sessionPath(plugin, ref)
	if err != nil || ref == "" {
		return envelope, "", errors.New("invalid MB account reference")
	}
	data, header, err := client.request(ctx, http.MethodGet, path, grant.Token, "", nil, envelopeLimit, http.StatusOK)
	if err != nil {
		return envelope, header.Get("ETag"), err
	}
	if tag := header.Get("ETag"); validTag(tag) && json.Unmarshal(data, &envelope) == nil {
		return envelope, tag, nil
	}
	return Envelope{}, "", errors.New("invalid MB session response")
}

func (client *Client) change(ctx context.Context, grant Grant, plugin, ref, previous string, envelope *Envelope) (string, error) {
	path, err := sessionPath(plugin, ref)
	if err != nil || ref == "" || !validTag(previous) && (envelope == nil || previous != "*") {
		return "", errors.New("invalid MB session precondition")
	}
	method, status := http.MethodDelete, http.StatusNoContent
	var body []byte
	if envelope != nil {
		method = http.MethodPut
		body, _ = json.Marshal(envelope)
		if len(body) > envelopeLimit {
			return "", errors.New("MB session exceeds size limit")
		}
		if previous == "*" {
			status = http.StatusCreated
		}
	}
	_, header, err := client.request(ctx, method, path, grant.Token, previous, body, 0, status)
	if err != nil {
		return "", err
	}
	if tag := header.Get("ETag"); validTag(tag) {
		return tag, nil
	}
	return "", errors.New("missing MB session revision")
}
