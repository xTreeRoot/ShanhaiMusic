package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.edge.DeviceArchive
import com.xtreeroot.mb.edge.objects
import com.xtreeroot.mb.edge.stringValues
import com.xtreeroot.mb.edge.text
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 所有代码包预检查通过后补装缺失插件，恢复展示、设置和缺失的私有文件。 */
internal suspend fun PluginInstaller.restoreDeviceArchivePlugins(doc: JSONObject, stage: File, active: suspend () -> Unit = {}) {
    val candidates = mutableListOf<PendingPlugin>()
    try {
        val preferences = doc.optJSONObject("pluginPreferences")?.let(PluginPreferences::parse)
        val discovery = doc.optJSONObject("discoveryPreferences")?.let(DiscoveryPreferences::parse)
        val records = doc.getJSONArray("plugins").objects()
        for (item in records) {
            active()
            val id = item.getString("id")
            val candidate = prepareArchive(item.text("repository"), remoteRevision = item.text("revision")) { target ->
                File(stage, "plugins/$id/package.zip").copyTo(target, overwrite = true)
            }
            candidates += candidate
            require(candidate.manifest.id == id)
        }
        active()
        for ((index, item) in records.withIndex()) {
            active()
            val id = item.getString("id")
            val plugin = installed().firstOrNull { it.id == id } ?: install(candidates[index])
            for (entry in DeviceArchive.tree(File(stage, "plugins/$id/data"), "data")) {
                val file = File(plugin.dataDirectory, entry.name.removePrefix("data/"))
                require(!Files.isSymbolicLink(plugin.dataDirectory.toPath()) && file.canonicalPath.startsWith(plugin.dataDirectory.canonicalPath + File.separator))
                if (file.exists()) continue
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                entry.file.copyTo(file)
            }
            val fields = plugin.manifest.settings.map { it.key }.toSet()
            saveSettings(plugin, settings(plugin) + item.getJSONObject("settings").stringValues().filterKeys { it in fields })
        }
        preferences?.let { mergePluginPreferences(it) }
        discovery?.let { mergeDiscoveryPreferences(it) }
        for (item in records) {
            val plugin = installed().first { it.id == item.getString("id") }
            setEnabled(plugin, item.getBoolean("enabled"))
            item.text("name")?.let { rename(plugin, it) }
        }
        restoreDeviceOrder(records.map { it.getString("id") })
    } finally { withContext(NonCancellable) { candidates.forEach { discard(it) } } }
}
