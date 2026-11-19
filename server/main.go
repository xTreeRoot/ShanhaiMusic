package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		log.Fatal(err)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: mb-server {serve|invite|devices|revoke|backup|keygen|sign|verify} [flags]")
	}
	command := args[0]
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	switch command {
	case "serve", "verify", "sign", "keygen":
		key := flags.String("key", "catalog.pub", "catalog key file (private for sign/keygen)")
		var dir, public, addr, database *string
		var revision *int64
		switch command {
		case "keygen":
			public = flags.String("public", "catalog.pub", "output public key")
			flags.Set("key", "catalog.key")
		case "sign":
			dir = flags.String("dir", "catalog", "new output directory")
			revision = flags.Int64("revision", 0, "monotonic catalog revision")
			flags.Set("key", "catalog.key")
		default:
			dir = flags.String("dir", "catalog", "published catalog directory")
			if command == "serve" {
				addr = flags.String("listen", "127.0.0.1:19047", "loopback listen address")
				database = flags.String("db", "mb.sqlite", "SQLite database path")
			}
		}
		if err := flags.Parse(args[1:]); err != nil {
			return err
		}
		if command != "sign" && flags.NArg() != 0 {
			return errors.New("unexpected positional arguments")
		}
		switch command {
		case "keygen":
			return createKeys(*key, *public)
		case "sign":
			return signCatalog(*dir, *key, *revision, flags.Args())
		case "verify":
			_, err := loadCatalog(*dir, *key)
			return err
		default:
			return serve(*addr, *database, *dir, *key)
		}
	case "invite", "devices", "revoke", "backup":
		return admin(command, flags, args[1:])
	default:
		return errors.New("unknown command")
	}
}

func serve(address, database, dir, key string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil || !net.ParseIP(host).IsLoopback() {
		return errors.New("server must listen on a numeric loopback address")
	}
	catalog, err := loadCatalog(dir, key)
	if err != nil {
		return fmt.Errorf("load catalog: %w", err)
	}
	db, err := openStore(database)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	server := &http.Server{Handler: (&api{db: db, catalog: catalog}).handler(), ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: time.Minute, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 << 10}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	result := make(chan error, 1)
	go func() { result <- server.Serve(listener) }()
	log.Print("MB Server listening on ", listener.Addr())
	select {
	case err := <-result:
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdown)
	}
}

func admin(command string, flags *flag.FlagSet, args []string) error {
	database := flags.String("db", "mb.sqlite", "SQLite database path")
	var space, device, output *string
	if command != "backup" {
		space = flags.String("space", "", "sync space id")
	}
	if command == "revoke" {
		device = flags.String("device", "", "device id")
	}
	if command == "backup" {
		output = flags.String("out", "", "new SQLite backup file")
	}
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 || (space != nil && !identifier.MatchString(*space)) ||
		(device != nil && !validToken(*device, 16)) || (output != nil && *output == "") {
		return errors.New("invalid command arguments")
	}
	if _, err := os.Stat(*database); err != nil {
		return err
	}
	db, err := openStore(*database)
	if err != nil {
		return err
	}
	defer db.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	switch command {
	case "invite":
		code, err := createInvite(ctx, db, *space, time.Now())
		if err == nil {
			fmt.Println(code)
		}
		return err
	case "devices":
		return listDevices(ctx, db, *space)
	case "revoke":
		return revokeDevice(ctx, db, *space, *device)
	default:
		return backupStore(ctx, db, *output)
	}
}

func revokeDevice(ctx context.Context, db *sql.DB, space, device string) error {
	result, err := db.ExecContext(ctx, `UPDATE devices SET revoked=1 WHERE space=? AND id=?`, space, device)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err == nil && count == 0 {
		return errors.New("unknown device")
	}
	return err
}

func listDevices(ctx context.Context, db *sql.DB, space string) error {
	type record struct {
		ID        string `json:"device_id"`
		Name      string `json:"name"`
		Platform  string `json:"platform"`
		Created   int64  `json:"created_at"`
		ExpiresAt int64  `json:"expires_at"`
		Revoked   bool   `json:"revoked"`
	}
	rows, err := db.QueryContext(ctx, `SELECT id, name, platform, created, expires, revoked FROM devices WHERE space=? ORDER BY created, id`, space)
	if err != nil {
		return err
	}
	defer rows.Close()
	entries := make([]record, 0)
	for rows.Next() {
		var entry record
		if err := rows.Scan(&entry.ID, &entry.Name, &entry.Platform, &entry.Created, &entry.ExpiresAt, &entry.Revoked); err != nil {
			return err
		}
		entries = append(entries, entry)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(entries)
}

func backupStore(ctx context.Context, db *sql.DB, output string) error {
	if _, err := os.Lstat(output); !errors.Is(err, os.ErrNotExist) {
		return errors.New("backup output must not exist")
	}
	dir, err := os.MkdirTemp(filepath.Dir(output), ".mb-backup-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	snapshot := filepath.Join(dir, "snapshot.sqlite")
	if _, err := db.ExecContext(ctx, `VACUUM INTO ?`, snapshot); err != nil {
		return err
	}
	if err := os.Chmod(snapshot, 0600); err != nil {
		return err
	}
	return os.Link(snapshot, output)
}
