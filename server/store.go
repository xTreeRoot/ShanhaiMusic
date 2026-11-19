package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

var errPrecondition = errors.New("precondition failed")

const schema = `
CREATE TABLE spaces (id TEXT PRIMARY KEY NOT NULL);
CREATE TABLE invites (
 digest BLOB PRIMARY KEY NOT NULL, space TEXT NOT NULL REFERENCES spaces(id), expires INTEGER NOT NULL
);
CREATE TABLE devices (
 id TEXT PRIMARY KEY NOT NULL, space TEXT NOT NULL REFERENCES spaces(id),
 name TEXT NOT NULL, platform TEXT NOT NULL, digest BLOB NOT NULL UNIQUE,
 created INTEGER NOT NULL, expires INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE sessions (
 space TEXT NOT NULL REFERENCES spaces(id), plugin TEXT NOT NULL, account TEXT NOT NULL,
 revision TEXT NOT NULL, format INTEGER NOT NULL, envelope BLOB, updated INTEGER NOT NULL,
 PRIMARY KEY(space, plugin, account)
);
PRAGMA user_version = 1;`

func openStore(path string) (*sql.DB, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	dsn := url.URL{Scheme: "file", Path: abs, RawQuery: "_txlock=immediate&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=secure_delete(ON)"}
	db, err := sql.Open("sqlite", dsn.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := initialize(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func initialize(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var version int
	if err := tx.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		return err
	}
	switch version {
	case 0:
		if _, err := tx.Exec(schema); err != nil {
			return err
		}
	case 1:
	default:
		return fmt.Errorf("unsupported database version %d", version)
	}
	return tx.Commit()
}

func randomToken(size int) string {
	return base64.RawURLEncoding.EncodeToString(randBytes(size))
}

func randBytes(size int) []byte {
	data := make([]byte, size)
	rand.Read(data)
	return data
}

func digest(secret string) []byte {
	sum := sha256.Sum256([]byte(secret))
	return sum[:]
}

func createInvite(ctx context.Context, db *sql.DB, space string, now time.Time) (string, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO spaces(id) VALUES (?)`, space); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM invites WHERE expires <= ?`, now.Unix()); err != nil {
		return "", err
	}
	code := randomToken(32)
	if _, err := tx.ExecContext(ctx, `INSERT INTO invites VALUES (?, ?, ?)`, digest(code), space, now.Add(15*time.Minute).Unix()); err != nil {
		return "", err
	}
	return code, tx.Commit()
}

type pairing struct {
	Code     string `json:"code"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
}

type deviceGrant struct {
	ID        string `json:"device_id"`
	Space     string `json:"space_id"`
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expires_at"`
}

func pairDevice(ctx context.Context, db *sql.DB, request pairing, now time.Time) (deviceGrant, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return deviceGrant{}, err
	}
	defer tx.Rollback()
	grant := deviceGrant{ID: randomToken(16), Token: randomToken(32), ExpiresAt: now.Add(90 * 24 * time.Hour).Unix()}
	if err := tx.QueryRowContext(ctx, `DELETE FROM invites WHERE digest = ? AND expires > ? RETURNING space`,
		digest(request.Code), now.Unix()).Scan(&grant.Space); err != nil {
		return deviceGrant{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO devices(id, space, name, platform, digest, created, expires) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		grant.ID, grant.Space, request.Name, request.Platform, digest(grant.Token), now.Unix(), grant.ExpiresAt)
	if err != nil {
		return deviceGrant{}, err
	}
	return grant, tx.Commit()
}

type sessionEntry struct {
	Account   string `json:"account_ref"`
	ETag      string `json:"etag"`
	Format    int    `json:"format"`
	Deleted   bool   `json:"deleted"`
	UpdatedAt int64  `json:"updated_at"`
}

func changeSession(ctx context.Context, db *sql.DB, space, plugin, account, match string, format int, envelope []byte, now time.Time) (string, error) {
	revision := randomToken(16)
	var result sql.Result
	var err error
	if match == "" {
		result, err = db.ExecContext(ctx, `INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
			space, plugin, account, revision, format, envelope, now.Unix())
	} else {
		result, err = db.ExecContext(ctx, `UPDATE sessions SET revision=?, format=?, envelope=?, updated=?
			WHERE space=? AND plugin=? AND account=? AND revision=?`,
			revision, format, envelope, now.Unix(), space, plugin, account, match)
	}
	if err != nil {
		return "", err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return "", err
	}
	if affected != 1 {
		return "", errPrecondition
	}
	return revision, nil
}
