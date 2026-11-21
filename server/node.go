package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

const nodeSpace = "node"

type nodeAccess struct {
	passwordDigest []byte
	recoveryKey    string
}

type nodeConfig struct {
	Password    string `json:"password"`
	RecoveryKey string `json:"recovery_key"`
}

func loadNodeAccess(path string) (*nodeAccess, error) {
	if path == "" {
		return nil, nil
	}
	data, err := readLimited(path, maxEnvelope)
	if err != nil {
		return nil, err
	}
	var config nodeConfig
	if err := json.Unmarshal(data, &config); err != nil || config.Password == "" || !validToken(config.RecoveryKey, 32) {
		return nil, errors.New("invalid node access configuration")
	}
	return &nodeAccess{passwordDigest: digest(config.Password), recoveryKey: config.RecoveryKey}, nil
}

func createNodeConfig(path string, input io.Reader) error {
	password, err := io.ReadAll(io.LimitReader(input, maxEnvelope+1))
	if err != nil {
		return err
	}
	if len(password) == 0 || !utf8.Valid(password) {
		return errors.New("expected a nonempty UTF-8 password on stdin")
	}
	data, err := json.Marshal(nodeConfig{Password: string(password), RecoveryKey: randomToken(32)})
	if err != nil {
		return err
	}
	if len(data) > maxEnvelope-2048 {
		return errors.New("node configuration exceeds protocol body size")
	}
	return writeNew(path, data, 0600)
}

func (service *api) join(writer http.ResponseWriter, request *http.Request) {
	var input struct {
		Password string `json:"password"`
		Name     string `json:"name"`
		Platform string `json:"platform"`
	}
	if !readJSON(writer, request, maxEnvelope, &input) {
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if !validDevice(input.Name, input.Platform) {
		fail(writer, http.StatusBadRequest, "invalid_device")
		return
	}
	now := time.Now()
	if !service.allowPair(now) {
		writer.Header().Set("Retry-After", "60")
		fail(writer, http.StatusTooManyRequests, "rate_limited")
		return
	}
	if subtle.ConstantTimeCompare(digest(input.Password), service.node.passwordDigest) != 1 {
		fail(writer, http.StatusForbidden, "invalid_node_password")
		return
	}
	ctx := request.Context()
	tx, err := service.db.BeginTx(ctx, nil)
	if err != nil {
		databaseFailure(writer, "join")
		return
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO spaces(id) VALUES (?)`, nodeSpace); err != nil {
		databaseFailure(writer, "initialize node space")
		return
	}
	grant, err := issueDevice(ctx, tx, nodeSpace, input.Name, input.Platform, now)
	if err != nil {
		databaseFailure(writer, "issue node device")
		return
	}
	if err := tx.Commit(); err != nil {
		databaseFailure(writer, "commit node device")
		return
	}
	jsonResponse(writer, http.StatusCreated, struct {
		deviceGrant
		RecoveryKey      string `json:"recovery_key"`
		CatalogPublicKey string `json:"catalog_public_key"`
	}{grant, service.node.recoveryKey, service.catalog.key})
}
