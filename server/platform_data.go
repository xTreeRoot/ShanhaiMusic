package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"time"
)

func validDataKey(writer http.ResponseWriter, request *http.Request) bool {
	if !identifier.MatchString(request.PathValue("platform")) || !identifier.MatchString(request.PathValue("key")) {
		fail(writer, http.StatusBadRequest, "invalid_data_key")
		return false
	}
	return true
}

func (service *api) getData(writer http.ResponseWriter, request *http.Request, space string) {
	if !validDataKey(writer, request) {
		return
	}
	var revision string
	var body []byte
	err := service.db.QueryRowContext(request.Context(), `SELECT revision, envelope FROM platform_data WHERE space=? AND platform=? AND key=?`,
		space, request.PathValue("platform"), request.PathValue("key")).Scan(&revision, &body)
	if errors.Is(err, sql.ErrNoRows) {
		fail(writer, http.StatusNotFound, "not_found")
		return
	}
	if err != nil {
		databaseFailure(writer, "get platform data")
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

func (service *api) changeData(writer http.ResponseWriter, request *http.Request, space string) {
	if !validDataKey(writer, request) {
		return
	}
	match, format, body, ok := readChange(writer, request)
	if !ok {
		return
	}
	revision, err := changePlatformData(request.Context(), service.db, space, request.PathValue("platform"), request.PathValue("key"), match, format, body, time.Now())
	if errors.Is(err, errPrecondition) {
		fail(writer, http.StatusPreconditionFailed, "revision_conflict")
		return
	}
	if err != nil {
		databaseFailure(writer, "change platform data")
		return
	}
	writer.Header().Set("ETag", `"`+revision+`"`)
	if match == "" {
		writer.WriteHeader(http.StatusCreated)
	} else {
		writer.WriteHeader(http.StatusNoContent)
	}
}

func changePlatformData(ctx context.Context, db *sql.DB, space, platform, key, match string, format int, envelope []byte, now time.Time) (string, error) {
	revision := randomToken(16)
	var result sql.Result
	var err error
	if match == "" {
		result, err = db.ExecContext(ctx, `INSERT INTO platform_data VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
			space, platform, key, revision, format, envelope, now.Unix())
	} else {
		result, err = db.ExecContext(ctx, `UPDATE platform_data SET revision=?, format=?, envelope=?, updated=? WHERE space=? AND platform=? AND key=? AND revision=?`,
			revision, format, envelope, now.Unix(), space, platform, key, match)
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
