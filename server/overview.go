package main

import "net/http"

type overviewEntry struct {
	Kind     string `json:"kind"`
	Platform string `json:"platform"`
	Key      string `json:"key,omitempty"`
	Count    int    `json:"count"`
	Updated  int64  `json:"updated_at,omitempty"`
}

func (service *api) overview(writer http.ResponseWriter, request *http.Request, space string) {
	rows, err := service.db.QueryContext(request.Context(), `
		SELECT 'sessions' AS kind, plugin, '' AS info_key, COUNT(*), MAX(updated)
		FROM sessions WHERE space=? AND envelope IS NOT NULL GROUP BY plugin
		UNION ALL
		SELECT 'data' AS kind, platform, key, 1, updated
		FROM platform_data WHERE space=? AND envelope IS NOT NULL
		ORDER BY kind, plugin, info_key`, space, space)
	if err != nil {
		databaseFailure(writer, "read sync overview")
		return
	}
	defer rows.Close()
	entries := make([]overviewEntry, 0)
	for rows.Next() {
		var entry overviewEntry
		if err := rows.Scan(&entry.Kind, &entry.Platform, &entry.Key, &entry.Count, &entry.Updated); err != nil {
			databaseFailure(writer, "scan sync overview")
			return
		}
		entries = append(entries, entry)
	}
	if rows.Err() != nil {
		databaseFailure(writer, "read sync overview")
		return
	}
	jsonResponse(writer, http.StatusOK, map[string]any{"records": entries})
}
