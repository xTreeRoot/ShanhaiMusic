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
		FROM platform_data WHERE space=? AND platform!=? AND envelope IS NOT NULL
		ORDER BY kind, plugin, info_key`, space, space, deviceSettingsPlatform)
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

const deviceSettingsPlatform = "device-settings"

func (service *api) deviceSettingsList(w http.ResponseWriter, r *http.Request, space, device string) {
	type entry struct {
		Key      string `json:"key"`
		Name     string `json:"name"`
		Platform string `json:"platform"`
		Current  bool   `json:"current"`
		Updated  int64  `json:"updated_at"`
	}
	rows, err := service.db.QueryContext(r.Context(), `SELECT lower(hex(d.id)),d.name,d.platform,d.id=?,coalesce(p.updated,0)
        FROM devices d LEFT JOIN platform_data p ON p.space=d.space AND p.platform=? AND p.key=lower(hex(d.id)) AND p.envelope IS NOT NULL
        WHERE d.space=? AND d.revoked=0 AND (d.id=? OR p.key IS NOT NULL)
        ORDER BY d.id=? DESC,p.updated DESC,d.id`, device, deviceSettingsPlatform, space, device, device)
	if err != nil {
		databaseFailure(w, "list device settings")
		return
	}
	defer rows.Close()
	result := make([]entry, 0)
	for rows.Next() {
		var v entry
		if err := rows.Scan(&v.Key, &v.Name, &v.Platform, &v.Current, &v.Updated); err != nil {
			databaseFailure(w, "scan device settings")
			return
		}
		result = append(result, v)
	}
	if rows.Err() != nil {
		databaseFailure(w, "read device settings")
		return
	}
	jsonResponse(w, http.StatusOK, map[string]any{"devices": result})
}
