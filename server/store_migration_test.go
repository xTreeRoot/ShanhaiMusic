package main

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"
)

func TestBothV2LayoutsRetainPlatformData(t *testing.T) {
	const envelope = `{"format":7,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAAA"}`
	for _, fixture := range []struct {
		name, ddl, insert string
		updated           int64
	}{
		{"deployed", platformDataSchema,
			`INSERT INTO platform_data(space, platform, key, revision, format, envelope, updated) VALUES ('test','example','profile','original',7,?,123),('test','example','removed','gone',0,NULL,123)`, 123},
		{"release", `CREATE TABLE platform_data(space TEXT NOT NULL REFERENCES spaces(id), platform TEXT NOT NULL, info_key TEXT NOT NULL, revision TEXT NOT NULL, envelope BLOB, PRIMARY KEY(space,platform,info_key))`,
			`INSERT INTO platform_data VALUES ('test','example','profile','original',?),('test','example','removed','gone',NULL)`, 0},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "node.sqlite")
			db, err := sql.Open("sqlite", path)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			if _, err := db.Exec(schema + fixture.ddl + `; INSERT INTO spaces VALUES('test'); PRAGMA user_version=2`); err != nil {
				t.Fatal(err)
			}
			if _, err := db.Exec(fixture.insert, []byte(envelope)); err != nil {
				t.Fatal(err)
			}
			upgraded, err := openStore(path)
			if err != nil {
				t.Fatal(err)
			}
			defer upgraded.Close()
			var body, revision string
			var format, version int
			var updated int64
			if err := upgraded.QueryRow("SELECT envelope,revision,format,updated FROM platform_data WHERE key='profile'").Scan(&body, &revision, &format, &updated); err != nil {
				t.Fatal(err)
			}
			if body != envelope || revision != "original" || format != 7 || updated != fixture.updated {
				t.Fatal("existing platform data changed")
			}
			if err := upgraded.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 4 {
				t.Fatalf("version=%d err=%v", version, err)
			}
			for _, update := range []struct{ key, previous string }{{"profile", "original"}, {"removed", "gone"}, {"new", ""}} {
				if _, err := changeRecord(t.Context(), upgraded, dataTable, "test", "example", update.key, update.previous, 7, []byte(envelope), time.Now()); err != nil {
					t.Fatal(err)
				}
			}
			if err := initialize(upgraded); err != nil {
				t.Fatal(err)
			}
		})
	}
}
