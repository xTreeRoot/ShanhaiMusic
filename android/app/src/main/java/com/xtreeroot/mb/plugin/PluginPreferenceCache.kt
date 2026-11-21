package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import org.json.JSONObject

/** 由安装器同一互斥锁保护，保存两份完整版本文档，单份丢失或损坏时修复。 */
internal class PluginPreferenceCache(root: File) {
    private val primary = File(root, "preferences.json")
    private val recovery = File(root, "recovery/preferences.json")
    private data class Snapshot(val writer: String, val document: PluginPreferences)
    fun load(migrate: (String) -> PluginPreferences): PluginPreferences = snapshot(migrate).document
    fun update(migrate: (String) -> PluginPreferences, change: (PluginPreferences, String) -> PluginPreferences): PluginPreferences {
        val current = snapshot(migrate)
        val next = change(current.document, current.writer).validated()
        if (next != current.document) save(current.copy(document = next))
        return next
    }
    private fun snapshot(migrate: (String) -> PluginPreferences): Snapshot {
        var failure: Exception? = null
        val copies = listOf(primary, recovery).filter { it.exists() }.mapNotNull { file ->
            try {
                require(file.length() <= 1024 * 1024)
                val json = JSONObject(file.readText())
                val writer = json.getString("writer")
                PluginPreferences().withOrder(emptyList(), writer)
                Snapshot(writer, PluginPreferences.parse(json.getJSONObject("document")))
            } catch (error: Exception) { failure = error; null }
        }
        if (copies.isNotEmpty()) {
            val first = copies.first()
            val merged = copies.drop(1).fold(first.document) { document, next -> document.merged(next.document) }
            val result = first.copy(document = merged)
            if (copies.size != 2 || copies.any { it != result }) save(result)
            return result
        }
        failure?.let { throw it }
        val writer = PluginPreferences.newWriter()
        return Snapshot(writer, migrate(writer).validated()).also(::save)
    }
    private fun save(value: Snapshot) {
        val json = JSONObject().put("writer", value.writer).put("document", value.document.toJson()).toString()
        writeAtomic(primary, json)
        writeAtomic(recovery, json)
    }
}
