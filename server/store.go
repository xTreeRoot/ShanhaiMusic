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

const platformDataSchema = `
CREATE TABLE platform_data (
 space TEXT NOT NULL REFERENCES spaces(id), platform TEXT NOT NULL, key TEXT NOT NULL,
 revision TEXT NOT NULL, format INTEGER NOT NULL, envelope BLOB, updated INTEGER NOT NULL,
 PRIMARY KEY(space, platform, key)
);`

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
		fallthrough
	case 1:
		if _, err := tx.Exec(platformDataSchema); err != nil {
			return err
		}
	case 2:
		// Both schemas shipped with user_version 2.
		var columns string
		if err := tx.QueryRow(`SELECT coalesce(group_concat(name, ',' ORDER BY cid), '') FROM pragma_table_info('platform_data')`).Scan(&columns); err != nil {
			return err
		}
		switch columns {
		case "space,platform,key,revision,format,envelope,updated":
			// The deployed node already has all metadata.
		case "space,platform,info_key,revision,envelope":
			if _, err := tx.Exec(`ALTER TABLE platform_data RENAME COLUMN info_key TO key;
				ALTER TABLE platform_data ADD COLUMN format INTEGER NOT NULL DEFAULT 0;
				ALTER TABLE platform_data ADD COLUMN updated INTEGER NOT NULL DEFAULT 0;
				UPDATE platform_data SET format=json_extract(CAST(envelope AS TEXT),'$.format') WHERE envelope IS NOT NULL`); err != nil {
				return err
			}
		default:
			return errors.New("unrecognized v2 platform_data columns")
		}
	case 3:
		// Version 4 adds encrypted backup objects without changing sync records.
	case 4:
		return tx.Commit()
	default:
		return fmt.Errorf("unsupported database version %d", version)
	}
	if _, err := tx.Exec(backupObjectSchema + `PRAGMA user_version = 4`); err != nil {
		return err
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
	var space string
	if err := tx.QueryRowContext(ctx, `DELETE FROM invites WHERE digest = ? AND expires > ? RETURNING space`,
		digest(request.Code), now.Unix()).Scan(&space); err != nil {
		return deviceGrant{}, err
	}
	grant, err := issueDevice(ctx, tx, space, request.Name, request.Platform, now)
	if err != nil {
		return deviceGrant{}, err
	}
	return grant, tx.Commit()
}

func issueDevice(ctx context.Context, tx *sql.Tx, space, name, platform string, now time.Time) (deviceGrant, error) {
	grant := deviceGrant{ID: randomToken(16), Space: space, Token: randomToken(32), ExpiresAt: now.Add(90 * 24 * time.Hour).Unix()}
	_, err := tx.ExecContext(ctx, `INSERT INTO devices(id, space, name, platform, digest, created, expires) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		grant.ID, grant.Space, name, platform, digest(grant.Token), now.Unix(), grant.ExpiresAt)
	return grant, err
}

type sessionEntry struct {
	Account   string `json:"account_ref"`
	ETag      string `json:"etag"`
	Format    int    `json:"format"`
	Deleted   bool   `json:"deleted"`
	UpdatedAt int64  `json:"updated_at"`
}

type recordTable string

const (
	sessionTable recordTable = "sessions"
	dataTable    recordTable = "platform_data"
)

func (table recordTable) addressColumns() string {
	if table == dataTable {
		return `platform=? AND key=?`
	}
	return `plugin=? AND account=?`
}

func changeRecord(ctx context.Context, db *sql.DB, table recordTable, space, plugin, account, match string, format int, envelope []byte, now time.Time) (string, error) {
	revision := randomToken(16)
	var result sql.Result
	var err error
	switch {
	case table == dataTable && match == "":
		result, err = db.ExecContext(ctx, `INSERT INTO platform_data(space, platform, key, revision, format, envelope, updated)
			SELECT ?, ?, ?, ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM platform_data WHERE space=?) < 256 ON CONFLICT DO NOTHING`,
			space, plugin, account, revision, format, envelope, now.Unix(), space)
	case table == dataTable:
		result, err = db.ExecContext(ctx, `UPDATE platform_data SET revision=?, format=?, envelope=?, updated=?
			WHERE space=? AND platform=? AND key=? AND revision=?`, revision, format, envelope, now.Unix(), space, plugin, account, match)
	case match == "":
		result, err = db.ExecContext(ctx, `INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
			space, plugin, account, revision, format, envelope, now.Unix())
	default:
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
