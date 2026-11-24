package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"mime"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode"
)

const maxEnvelope = 128 << 10

var identifier = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,63}$`)

type api struct {
	db      *sql.DB
	catalog *publishedCatalog
	node    *nodeAccess
	mu      sync.Mutex
	minute  int64
	attempt int
}

func (service *api) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", service.health)
	mux.HandleFunc("GET /v1/catalog", service.catalog.serveCatalog)
	mux.HandleFunc("GET /v1/packages/{digest}", service.catalog.servePackage)
	mux.HandleFunc("POST /v1/pair", service.pair)
	if service.node != nil {
		mux.HandleFunc("POST /v1/join", service.join)
	}
	mux.HandleFunc("GET /v1/sessions/{plugin}", service.authenticated(service.list))
	mux.HandleFunc("GET /v1/sessions/{plugin}/{account}", service.authenticated(service.get))
	mux.HandleFunc("PUT /v1/sessions/{plugin}/{account}", service.authenticated(service.change))
	mux.HandleFunc("DELETE /v1/sessions/{plugin}/{account}", service.authenticated(service.change))
	mux.HandleFunc("GET /v1/data/{plugin}/{key}", service.authenticated(service.get))
	mux.HandleFunc("PUT /v1/data/{plugin}/{key}", service.authenticated(service.change))
	mux.HandleFunc("DELETE /v1/data/{plugin}/{key}", service.authenticated(service.change))
	mux.HandleFunc("GET /v1/sync/devices", service.authenticatedDevice(service.deviceSettingsList))
	mux.HandleFunc("PATCH /v1/sync/device", service.authenticatedDevice(service.renameDevice))
	mux.HandleFunc("GET /v1/backups/{device}/{key}", service.authenticatedDevice(service.backupObject))
	mux.HandleFunc("PUT /v1/backups/{device}/{key}", service.authenticatedDevice(service.backupObject))
	mux.HandleFunc("DELETE /v1/backups/{device}/{key}", service.authenticatedDevice(service.backupObject))
	mux.HandleFunc("GET /v1/sync/overview", service.authenticated(service.overview))
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("X-Content-Type-Options", "nosniff")
		writer.Header().Set("Cache-Control", "no-store")
		mux.ServeHTTP(writer, request)
	})
}

func jsonResponse(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	json.NewEncoder(writer).Encode(value)
}

func fail(writer http.ResponseWriter, status int, code string) {
	jsonResponse(writer, status, map[string]string{"error": code})
}

func databaseFailure(writer http.ResponseWriter, operation string) {
	log.Printf("database operation failed: %s", operation)
	fail(writer, http.StatusInternalServerError, "storage_error")
}

func readJSON(writer http.ResponseWriter, request *http.Request, limit int64, target any) bool {
	mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		fail(writer, http.StatusUnsupportedMediaType, "expected_json")
		return false
	}
	decoder := json.NewDecoder(http.MaxBytesReader(writer, request.Body, limit))
	decoder.DisallowUnknownFields()
	err = decoder.Decode(target)
	if err == nil {
		var extra any
		if err = decoder.Decode(&extra); err == io.EOF {
			return true
		}
	}
	var oversized *http.MaxBytesError
	if errors.As(err, &oversized) {
		fail(writer, http.StatusRequestEntityTooLarge, "payload_too_large")
	} else {
		fail(writer, http.StatusBadRequest, "invalid_json")
	}
	return false
}

func validToken(value string, size int) bool {
	if len(value) != base64.RawURLEncoding.EncodedLen(size) || strings.ContainsAny(value, "\r\n") {
		return false
	}
	decoded, err := base64.RawURLEncoding.Strict().DecodeString(value)
	return err == nil && len(decoded) == size
}

func (service *api) health(writer http.ResponseWriter, request *http.Request) {
	if err := service.db.PingContext(request.Context()); err != nil {
		fail(writer, http.StatusServiceUnavailable, "unavailable")
		return
	}
	jsonResponse(writer, http.StatusOK, map[string]string{"status": "ok"})
}

func (service *api) allowPair(now time.Time) bool {
	service.mu.Lock()
	defer service.mu.Unlock()
	if minute := now.Unix() / 60; minute != service.minute {
		service.minute = minute
		service.attempt = 0
	}
	service.attempt++
	return service.attempt <= 60
}

func validDevice(name, platform string) bool {
	return name != "" && len(name) <= 120 && !strings.ContainsFunc(name, unicode.IsControl) &&
		(platform == "desktop" || platform == "ios" || platform == "android")
}

func (service *api) pair(writer http.ResponseWriter, request *http.Request) {
	var input pairing
	if !readJSON(writer, request, 2048, &input) {
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if !validToken(input.Code, 32) || !validDevice(input.Name, input.Platform) {
		fail(writer, http.StatusBadRequest, "invalid_pairing")
		return
	}
	now := time.Now()
	if !service.allowPair(now) {
		writer.Header().Set("Retry-After", "60")
		fail(writer, http.StatusTooManyRequests, "rate_limited")
		return
	}
	grant, err := pairDevice(request.Context(), service.db, input, now)
	if errors.Is(err, sql.ErrNoRows) {
		fail(writer, http.StatusForbidden, "invalid_or_expired_invite")
	} else if err != nil {
		databaseFailure(writer, "pair")
	} else {
		jsonResponse(writer, http.StatusCreated, grant)
	}
}

func (service *api) authenticated(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return service.authenticatedDevice(func(w http.ResponseWriter, r *http.Request, space, device string) { next(w, r, space) })
}

func (service *api) authenticatedDevice(next func(http.ResponseWriter, *http.Request, string, string)) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		scheme, token, found := strings.Cut(request.Header.Get("Authorization"), " ")
		var space, device string
		err := sql.ErrNoRows
		if found && strings.EqualFold(scheme, "Bearer") && validToken(token, 32) {
			err = service.db.QueryRowContext(request.Context(), `SELECT space,id FROM devices WHERE digest=? AND revoked=0 AND expires>?`,
				digest(token), time.Now().Unix()).Scan(&space, &device)
		}
		if errors.Is(err, sql.ErrNoRows) {
			writer.Header().Set("WWW-Authenticate", "Bearer")
			fail(writer, http.StatusUnauthorized, "unauthorized")
			return
		}
		if err != nil {
			databaseFailure(writer, "authenticate")
			return
		}
		plugin, account, key := request.PathValue("plugin"), request.PathValue("account"), request.PathValue("key")
		if (plugin != "" && !identifier.MatchString(plugin)) || (account != "" && !validToken(account, 32)) || (key != "" && !identifier.MatchString(key)) {
			fail(writer, http.StatusBadRequest, "invalid_session_key")
			return
		}
		if plugin == deviceSettingsPlatform && key != "" {
			if request.Method != http.MethodGet && key != hex.EncodeToString([]byte(device)) {
				fail(writer, http.StatusForbidden, "device_settings_owner")
				return
			}
			if request.Method == http.MethodGet {
				var found bool
				err := service.db.QueryRowContext(request.Context(), `SELECT EXISTS(SELECT 1 FROM devices WHERE space=? AND lower(hex(id))=? AND revoked=0)`, space, key).Scan(&found)
				if err != nil {
					databaseFailure(writer, "read device settings owner")
					return
				}
				if !found {
					fail(writer, http.StatusNotFound, "not_found")
					return
				}
			}
		}
		next(writer, request, space, device)
	}
}

func recordAddress(request *http.Request) (recordTable, string, string) {
	if key := request.PathValue("key"); key != "" {
		return dataTable, request.PathValue("plugin"), key
	}
	return sessionTable, request.PathValue("plugin"), request.PathValue("account")
}

func (service *api) list(writer http.ResponseWriter, request *http.Request, space string) {
	rows, err := service.db.QueryContext(request.Context(), `SELECT account, revision, format, envelope IS NULL, updated
		FROM sessions WHERE space=? AND plugin=? ORDER BY updated DESC, account`, space, request.PathValue("plugin"))
	if err != nil {
		databaseFailure(writer, "list sessions")
		return
	}
	defer rows.Close()
	entries := make([]sessionEntry, 0)
	for rows.Next() {
		var entry sessionEntry
		if err := rows.Scan(&entry.Account, &entry.ETag, &entry.Format, &entry.Deleted, &entry.UpdatedAt); err != nil {
			databaseFailure(writer, "scan sessions")
			return
		}
		entry.ETag = `"` + entry.ETag + `"`
		entries = append(entries, entry)
	}
	if rows.Err() != nil {
		databaseFailure(writer, "list sessions")
		return
	}
	jsonResponse(writer, http.StatusOK, map[string]any{"sessions": entries})
}

type encryptedSession struct {
	Format     int    `json:"format"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

func (service *api) get(writer http.ResponseWriter, request *http.Request, space string) {
	var revision string
	var body []byte
	table, plugin, account := recordAddress(request)
	err := service.db.QueryRowContext(request.Context(), `SELECT revision, envelope FROM `+string(table)+` WHERE space=? AND `+table.addressColumns(),
		space, plugin, account).Scan(&revision, &body)
	if errors.Is(err, sql.ErrNoRows) {
		fail(writer, http.StatusNotFound, "not_found")
		return
	}
	if err != nil {
		databaseFailure(writer, "get sync record")
		return
	}
	writer.Header().Set("ETag", `"`+revision+`"`)
	if body == nil {
		fail(writer, http.StatusGone, "deleted")
		return
	}
	writer.Header().Set("Content-Type", "application/json")
	writer.Write(body)
}

func (service *api) change(writer http.ResponseWriter, request *http.Request, space string) {
	match, none := request.Header.Get("If-Match"), request.Header.Get("If-None-Match")
	if match == "" && none == "" {
		fail(writer, http.StatusPreconditionRequired, "precondition_required")
		return
	}
	if match != "" {
		if none != "" || len(match) < 2 || match[0] != '"' || match[len(match)-1] != '"' || !validToken(match[1:len(match)-1], 16) {
			fail(writer, http.StatusBadRequest, "invalid_precondition")
			return
		}
		match = match[1 : len(match)-1]
	} else if none != "*" || request.Method != http.MethodPut {
		fail(writer, http.StatusBadRequest, "invalid_precondition")
		return
	}
	var format int
	var body []byte
	if request.Method == http.MethodPut {
		var envelope encryptedSession
		if !readJSON(writer, request, maxEnvelope, &envelope) {
			return
		}
		decoded, err := base64.RawURLEncoding.Strict().DecodeString(envelope.Ciphertext)
		if envelope.Format < 1 || envelope.Format > 65535 || !validToken(envelope.Nonce, 12) || err != nil ||
			len(decoded) <= 16 || strings.ContainsAny(envelope.Ciphertext, "\r\n") {
			fail(writer, http.StatusBadRequest, "invalid_envelope")
			return
		}
		format = envelope.Format
		body, _ = json.Marshal(envelope)
	}
	table, plugin, account := recordAddress(request)
	revision, err := changeRecord(request.Context(), service.db, table, space, plugin, account, match, format, body, time.Now())
	if errors.Is(err, errPrecondition) {
		fail(writer, http.StatusPreconditionFailed, "revision_conflict")
		return
	}
	if err != nil {
		databaseFailure(writer, "change sync record")
		return
	}
	writer.Header().Set("ETag", `"`+revision+`"`)
	if match == "" {
		writer.WriteHeader(http.StatusCreated)
	} else {
		writer.WriteHeader(http.StatusNoContent)
	}
}
