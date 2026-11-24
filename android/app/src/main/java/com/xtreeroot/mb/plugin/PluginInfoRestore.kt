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

/** 仅覆盖用户所选插件的信息，保留现有代码、名称、开关和其他插件。 */
internal suspend fun PluginInstaller.restorePluginInformation(doc: JSONObject, stage: File, id: String, active: suspend () -> Unit = {}) {
    val item = doc.getJSONArray("plugins").objects().firstOrNull { it.getString("id") == id }
        ?: error("Plugin absent from backup")
    val candidate = prepareArchive(item.text("repository"), remoteRevision = item.text("revision")) {
        File(stage, "plugins/$id/package.zip").copyTo(it, overwrite = true)
    }
    try {
        require(candidate.manifest.id == id)
        val source = File(stage, "plugins/$id/data")
        val entries = DeviceArchive.tree(source, "data")
        active()
        val plugin = installed().firstOrNull { it.id == id } ?: install(candidate)
        val target = plugin.dataDirectory
        val parent = requireNotNull(target.parentFile)
        require(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath()))
        DeviceArchive.tree(target, "old")
        check(parent.mkdirs() || parent.isDirectory)
        val next = Files.createTempDirectory(parent.toPath(), ".plugin-restore-").toFile()
        val previous = File(parent, next.name + "-previous")
        try {
            for (entry in entries) {
                active()
                val output = File(next, entry.name.removePrefix("data/"))
                require(output.canonicalPath.startsWith(next.canonicalPath + File.separator))
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                entry.file.copyTo(output)
            }
            val fields = plugin.manifest.settings.map { it.key }.toSet()
            File(next, "settings.json").writeText(JSONObject(item.getJSONObject("settings").stringValues().filterKeys { it in fields }).toString())
            active()
            withContext(NonCancellable) {
                val existed = target.exists()
                if (existed) check(target.renameTo(previous))
                if (!next.renameTo(target)) {
                    if (existed) check(previous.renameTo(target))
                    error("Cannot replace plugin data")
                }
                if (existed) check(previous.deleteRecursively())
            }
        } finally { next.deleteRecursively() }
    } finally { withContext(NonCancellable) { discard(candidate) } }
}
