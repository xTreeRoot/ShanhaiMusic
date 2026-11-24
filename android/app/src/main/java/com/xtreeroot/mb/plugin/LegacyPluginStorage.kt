package com.xtreeroot.mb.plugin

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** 只读合并历史宿主存储；旧文件保留，已有插件键始终优先。 */
internal object LegacyPluginStorage {
    fun migrate(context: Context, plugin: InstalledPlugin) {
        val preferences = context.getSharedPreferences("host.storage.${plugin.id}", Context.MODE_PRIVATE).all
        if (preferences.isEmpty()) return
        val file = AtomicFile(File(plugin.dataDirectory, "storage.json"))
        val current = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else JSONObject()
        val legacy = current.optJSONObject("legacy") ?: JSONObject()
        if (legacy.has("preferences")) return
        legacy.put("preferences", JSONObject().apply { preferences.forEach { (key, value) ->
            put(key, if (value is Set<*>) org.json.JSONArray(value.toList()) else value)
        } })
        current.put("legacy", legacy)
        plugin.dataDirectory.mkdirs()
        val stream = file.startWrite()
        try { stream.write(current.toString().toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
