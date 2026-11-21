package com.xtreeroot.mb.plugin

import android.content.ContentResolver
import android.net.Uri
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal data class InstalledPlugin(
    val manifest: PluginManifest,
    val revision: UUID,
    val directory: File,
    val dataDirectory: File,
    val repository: String?,
    val enabled: Boolean,
    val remoteRevision: String? = null,
    val localName: String? = null,
) {
    val id: String get() = manifest.id
    val displayName: String get() = localName ?: manifest.name
}

internal data class PendingPlugin(
    val token: UUID,
    val manifest: PluginManifest,
    val directory: File,
    val repository: String?,
    val replaces: InstalledPlugin?,
    val remoteRevision: String? = null,
    val releaseNotes: String? = null,
)

internal class PluginInstaller(private val root: File) {
    private val gate = Mutex()
    private val pending = File(root, "pending")
    private val code = File(root, "code")
    private val data = File(root, "data")
    private val indexFile = File(root, "installed.json")
    private var initialized = false
    private val mutations = MutableStateFlow(0L)
    val changes = mutations.asStateFlow()

    private val presentationFile = File(root, "presentation.json")
    private val preferenceCache = PluginPreferenceCache(root)
    private fun migratePreferences(writer: String): PluginPreferences {
        val old = if (presentationFile.isFile) PluginPresentation.read(presentationFile.readText()) else PluginPresentation()
        var document = PluginPreferences()
        old.names.toSortedMap().forEach { (id, name) -> document = document.withName(id, name, writer) }
        if (old.order.isNotEmpty()) document = document.withOrder(old.order, writer)
        val index = index()
        index.keys().asSequence().sorted().filter { !index.getJSONObject(it).getBoolean("enabled") }.forEach { id -> document = document.withEnabled(id, false, writer) }
        return document
    }
    private fun preferences() = preferenceCache.load(::migratePreferences)
    private fun presentation(): PluginPresentation {
        val document = preferences()
        return PluginPresentation(document.names.mapValues { it.value.value }.filterValues { it.isNotEmpty() }, document.order?.value.orEmpty())
    }
    private fun savePresentation(value: PluginPresentation) {
        preferenceCache.update(::migratePreferences) { current, writer ->
            var next = current
            (current.names.keys + value.names.keys).sorted().forEach { id ->
                val name = value.names[id].orEmpty()
                if (current.names[id]?.value.orEmpty() != name) next = next.withName(id, name, writer)
            }
            val order = preservingMissingPluginOrder(current.order?.value.orEmpty(), value.order)
            if (current.order?.value.orEmpty() != order) next = next.withOrder(order, writer)
            next
        }
    }
    suspend fun pluginPreferences(): PluginPreferences = locked { preferences() }
    suspend fun mergePluginPreferences(remote: PluginPreferences): PluginPreferences {
        val context = coroutineContext
        return locked {
        context.ensureActive()
        val before = preferences()
        preferenceCache.update(::migratePreferences) { current, _ -> current.merged(remote) }.also {
            if (it != before) changed()
        }
        }
    }

    suspend fun installed(): List<InstalledPlugin> = locked {
        val index = index()
        val order = presentation().order.withIndex().associate { it.value to it.index }
        index.keys().asSequence().map { plugin(index, it) }.sortedWith(
            compareBy<InstalledPlugin> { order[it.id] ?: Int.MAX_VALUE }.thenBy { it.manifest.name.lowercase() }).toList()
    }

    suspend fun rename(current: InstalledPlugin, name: String) = locked {
        checkRevision(index(), current)
        savePresentation(presentation().renamed(current.id, name))
        changed()
    }

    suspend fun move(id: String, step: Int) = locked {
        val index = index()
        val preferences = presentation()
        val positions = preferences.order.withIndex().associate { it.value to it.index }
        val ids = index.keys().asSequence().map { plugin(index, it) }.sortedWith(
            compareBy<InstalledPlugin> { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.manifest.name.lowercase() }).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + step
        if (from >= 0 && to in ids.indices) {
            ids.add(to, ids.removeAt(from))
            savePresentation(preferences.copy(order = ids))
            changed()
        }
    }

    suspend fun prepareZip(open: () -> InputStream): PendingPlugin = prepareArchive(download = { archive ->
        val context = coroutineContext
        open().use { input -> archive.outputStream().use { output ->
            copyPluginBytes(input, output, pluginZipLimit, checkActive = context::ensureActive)
        } }
    })

    suspend fun prepareArchive(repository: String? = null, expected: InstalledPlugin? = null, remoteRevision: String? = null,
                               download: suspend (File) -> Unit): PendingPlugin = prepare(repository, expected, remoteRevision) { staging, folder ->
        val archive = File(staging, "source.zip")
        download(archive)
        PluginPackage.unzip(archive, folder)
        archive.delete()
    }

    suspend fun prepareTree(resolver: ContentResolver, tree: Uri): PendingPlugin = prepare(null, null, null) { _, folder ->
        PluginPackage.copyTree(resolver, tree, folder)
    }

    private suspend fun prepare(repository: String?, expected: InstalledPlugin?, remoteRevision: String?,
                                extract: suspend (File, File) -> Unit): PendingPlugin = withContext(Dispatchers.IO) {
        locked { Unit }
        val token = UUID.randomUUID()
        val staging = File(pending, token.toString())
        val content = File(staging, "content")
        if (!content.mkdirs()) throw PluginException("Cannot stage plugin")
        try {
            extract(staging, content)
            val directory = PluginPackage.findRoot(content)
            val manifest = PluginPackage.readManifest(directory)
            if (manifest.id == localSourceId) throw PluginException("Plugin ID is reserved")
            if (expected != null && manifest.id != expected.id) throw PluginException("Update belongs to a different plugin")
            val replaces = locked {
                val index = index()
                val current = if (index.has(manifest.id)) plugin(index, manifest.id) else null
                if (expected != null && current?.revision != expected.revision) throw PluginException("Plugin changed while checking for updates")
                current
            }
            PendingPlugin(token, manifest, directory, repository?.let(PluginRepository::parse), replaces, remoteRevision)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun install(candidate: PendingPlugin): InstalledPlugin = install(candidate, null)

    private suspend fun install(candidate: PendingPlugin, restoredSettings: Map<String, String>?): InstalledPlugin = locked {
        val staging = File(pending, candidate.token.toString()).canonicalFile
        val folder = candidate.directory.canonicalFile
        if (!folder.toPath().startsWith(staging.toPath())) throw PluginException("Invalid staged plugin")
        val manifest = PluginPackage.readManifest(folder)
        if (manifest != candidate.manifest) throw PluginException("Plugin changed after review")
        val index = index()
        val previous = if (index.has(manifest.id)) plugin(index, manifest.id) else null
        if (previous?.revision != candidate.replaces?.revision) throw PluginException("Plugin changed after review")
        // 远程改名不应让原先按默认名称排列的平台跳位置。
        if (previous != null && previous.manifest.name != manifest.name) {
            val preferences = presentation()
            val positions = preferences.order.withIndex().associate { it.value to it.index }
            val ids = index.keys().asSequence().map { plugin(index, it) }.sortedWith(
                compareBy<InstalledPlugin> { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.manifest.name.lowercase() }).map { it.id }.toList()
            savePresentation(preferences.copy(order = ids))
        }
        val target = File(code, candidate.token.toString())
        if (!code.isDirectory && !code.mkdirs()) throw PluginException("Cannot create plugin store")
        val enabled = preferences().enabled[manifest.id]?.value ?: previous?.enabled ?: (restoredSettings == null)
        Files.move(folder.toPath(), target.toPath(), ATOMIC_MOVE)
        try {
            val settings = File(File(data, manifest.id), "settings.json")
            if (restoredSettings != null && !settings.exists()) {
                val verified = manifest.backupSettings(restoredSettings)
                if (verified.isNotEmpty()) writeAtomic(settings, JSONObject(verified).toString())
            }
            index.put(manifest.id, JSONObject().put("revision", candidate.token.toString())
                .put("repository", candidate.repository ?: JSONObject.NULL).put("enabled", enabled)
                .put("remoteRevision", candidate.remoteRevision ?: JSONObject.NULL))
            writeAtomic(indexFile, index.toString())
        } catch (error: Throwable) {
            Files.move(target.toPath(), folder.toPath(), ATOMIC_MOVE)
            throw error
        }
        staging.deleteRecursively()
        previous?.directory?.deleteRecursively()
        changed()
        plugin(index, manifest.id)
    }

    suspend fun discard(candidate: PendingPlugin) = locked { File(pending, candidate.token.toString()).deleteRecursively() }

    suspend fun setEnabled(current: InstalledPlugin, enabled: Boolean) = locked {
        val index = index()
        checkRevision(index, current)
        preferenceCache.update(::migratePreferences) { document, writer ->
            if (document.enabled[current.id]?.value == enabled) document else document.withEnabled(current.id, enabled, writer)
        }
        index.getJSONObject(current.id).put("enabled", enabled)
        writeAtomic(indexFile, index.toString())
        changed()
    }

    suspend fun remove(current: InstalledPlugin) = locked {
        val index = index()
        checkRevision(index, current)
        index.remove(current.id)
        writeAtomic(indexFile, index.toString())
        current.directory.deleteRecursively()
        changed()
    }

    suspend fun settings(current: InstalledPlugin): Map<String, String> = locked {
        checkRevision(index(), current)
        val file = File(current.dataDirectory, "settings.json")
        val values = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        current.manifest.settings.mapNotNull { setting -> (values.opt(setting.key) as? String)?.let { setting.key to it } }.toMap()
    }

    suspend fun saveSettings(current: InstalledPlugin, settings: Map<String, String>) = locked {
        checkRevision(index(), current)
        val values = JSONObject(settings.filterKeys { key -> current.manifest.settings.any { it.key == key } }).toString()
        if (values.encodeToByteArray().size > 1024 * 1024) throw PluginException("Plugin settings too large")
        writeAtomic(File(current.dataDirectory, "settings.json"), values)
        changed()
    }

    suspend fun archiveForBackup(plugin: InstalledPlugin, archive: File) {
        val context = coroutineContext
        locked {
            checkRevision(index(), plugin)
            PluginPackage.archive(plugin.directory, archive, context::ensureActive)
        }
    }

    suspend fun restoreArchive(id: String, repository: String?, archive: File, settings: Map<String, String>) {
        val candidate = prepareArchive(repository) { destination -> archive.copyTo(destination) }
        try {
            if (candidate.manifest.id != id) throw PluginException("Backup plugin ID does not match")
            if (candidate.replaces == null) install(candidate, settings)
        } finally { withContext(NonCancellable) { discard(candidate) } }
    }

    private fun changed() { mutations.update { it + 1 } }

    private fun index() = if (indexFile.isFile) JSONObject(indexFile.readText()) else JSONObject()

    private fun plugin(index: JSONObject, id: String): InstalledPlugin {
        val record = index.getJSONObject(id)
        val revision = UUID.fromString(record.getString("revision"))
        val directory = File(code, revision.toString())
        val manifest = PluginPackage.readManifest(directory)
        if (id != manifest.id) throw PluginException("Installed plugin ID does not match")
        val source = (record.opt("repository") as? String)?.let { runCatching { PluginRepository.parse(it) }.getOrNull() }
        return InstalledPlugin(manifest, revision, directory, File(data, id), source, preferences().enabled[id]?.value ?: record.getBoolean("enabled"), record.opt("remoteRevision") as? String, presentation().names[id])
    }

    private fun checkRevision(index: JSONObject, current: InstalledPlugin) {
        if (index.optJSONObject(current.id)?.optString("revision") != current.revision.toString()) {
            throw PluginException("Plugin is no longer installed at this revision")
        }
    }

    private suspend fun <Value> locked(action: () -> Value): Value = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!initialized) {
                pending.deleteRecursively()
                val index = index()
                val active = index.keys().asSequence().map { index.getJSONObject(it).getString("revision") }.toSet()
                code.listFiles()?.filter { it.name !in active }?.forEach(File::deleteRecursively)
                initialized = true
            }
            action()
        }
    }
}

internal fun PluginManifest.backupSettings(values: Map<String, String>): Map<String, String> = settings.mapNotNull { setting ->
    val value = values[setting.key] ?: return@mapNotNull null
    when {
        setting.type == "boolean" && value in setOf("true", "false") -> setting.key to value
        setting.type == "select" && setting.options.any { it.value == value } -> setting.key to value
        else -> null
    }
}.toMap()
