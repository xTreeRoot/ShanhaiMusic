package com.xtreeroot.mb.playback

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class PlaylistResume(val trackID: String, val title: String, val position: Long)

/** 歌单进度保存在独立的本机偏好中，设备备份只读取原有播放队列。 */
internal class PlaylistResumeStore(private val preferences: SharedPreferences) {
    private val records = MutableStateFlow(preferences.all.mapNotNull { (key, value) ->
        val raw = value as? String ?: return@mapNotNull null
        runCatching {
            val item = JSONObject(raw)
            key to PlaylistResume(item.getString("track"), item.getString("title"), item.getLong("position"))
        }.getOrNull()
    }.toMap())
    val values = records.asStateFlow()

    fun save(key: String, value: PlaylistResume) {
        val current = records.value
        if (current[key] == value) return
        val encoded = JSONObject().put("track", value.trackID).put("title", value.title).put("position", value.position)
        preferences.edit().putString(key, encoded.toString()).apply()
        records.value = current + (key to value)
    }
}
