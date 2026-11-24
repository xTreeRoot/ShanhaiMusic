package main

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

// Objects are encrypted on the client. The existing SQLite backup includes them.
// A snapshot is published only after all objects have been uploaded and verified.
const backupObjectSchema = `
CREATE TABLE backup_objects (
 space TEXT NOT NULL REFERENCES spaces(id), device TEXT NOT NULL REFERENCES devices(id),
 key TEXT NOT NULL, envelope BLOB NOT NULL, updated INTEGER NOT NULL,
 PRIMARY KEY(space,device,key)
);`

const maxBackupObject = 4 << 20
const maxBackupSpace = 32 << 30

func (service *api) backupObject(w http.ResponseWriter, r *http.Request, space, current string) {
	owner, err := hex.DecodeString(r.PathValue("device"))
	if err != nil || hex.EncodeToString(owner) != r.PathValue("device") || !validToken(string(owner), 16) {
		fail(w, http.StatusBadRequest, "invalid_backup_owner")
		return
	}
	if r.Method != http.MethodGet && string(owner) != current {
		fail(w, http.StatusForbidden, "backup_owner")
		return
	}
	var exists bool
	if err := service.db.QueryRowContext(r.Context(), `SELECT EXISTS(SELECT 1 FROM devices WHERE id=? AND space=? AND revoked=0)`, string(owner), space).Scan(&exists); err != nil {
		databaseFailure(w, "read backup owner")
		return
	}
	if !exists {
		fail(w, http.StatusNotFound, "not_found")
		return
	}
	key := r.PathValue("key")
	switch r.Method {
	case http.MethodGet:
		var body []byte
		err := service.db.QueryRowContext(r.Context(), `SELECT envelope FROM backup_objects WHERE space=? AND device=? AND key=?`, space, string(owner), key).Scan(&body)
		if errors.Is(err, sql.ErrNoRows) {
			fail(w, http.StatusNotFound, "not_found")
		} else if err != nil {
			databaseFailure(w, "read backup object")
		} else {
			w.Header().Set("Content-Type", "application/json")
			w.Write(body)
		}
	case http.MethodDelete:
		if _, err := service.db.ExecContext(r.Context(), `DELETE FROM backup_objects WHERE space=? AND device=? AND key=?`, space, current, key); err != nil {
			databaseFailure(w, "delete backup object")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case http.MethodPut:
		var envelope encryptedSession
		if !readJSON(w, r, maxBackupObject, &envelope) {
			return
		}
		sealed, err := base64.RawURLEncoding.Strict().DecodeString(envelope.Ciphertext)
		if envelope.Format != 1 || !validToken(envelope.Nonce, 12) || err != nil || len(sealed) <= 16 || base64.RawURLEncoding.EncodeToString(sealed) != envelope.Ciphertext {
			fail(w, http.StatusBadRequest, "invalid_envelope")
			return
		}
		body, _ := json.Marshal(envelope)
		tx, err := service.db.BeginTx(r.Context(), nil)
		if err != nil {
			databaseFailure(w, "begin backup upload")
			return
		}
		defer tx.Rollback()
		var previous []byte
		err = tx.QueryRowContext(r.Context(), `SELECT envelope FROM backup_objects WHERE space=? AND device=? AND key=?`, space, current, key).Scan(&previous)
		if err == nil {
			if !bytes.Equal(previous, body) {
				fail(w, http.StatusConflict, "backup_object_exists")
			} else {
				w.WriteHeader(http.StatusNoContent)
			}
			return
		}
		if !errors.Is(err, sql.ErrNoRows) {
			databaseFailure(w, "read backup upload")
			return
		}
		var used int64
		if err := tx.QueryRowContext(r.Context(), `SELECT coalesce(sum(length(envelope)),0) FROM backup_objects WHERE space=?`, space).Scan(&used); err != nil {
			databaseFailure(w, "measure backup storage")
			return
		}
		if used+int64(len(body)) > maxBackupSpace {
			fail(w, http.StatusInsufficientStorage, "backup_storage_full")
			return
		}
		if _, err := tx.ExecContext(r.Context(), `INSERT INTO backup_objects VALUES(?,?,?,?,?)`, space, current, key, body, time.Now().Unix()); err != nil {
			databaseFailure(w, "write backup object")
			return
		}
		if err := tx.Commit(); err != nil {
			databaseFailure(w, "commit backup object")
			return
		}
		w.WriteHeader(http.StatusCreated)
	}
}
