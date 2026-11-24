package com.xtreeroot.mb.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.xtreeroot.mb.playback.QueueSnapshot
import com.xtreeroot.mb.plugin.PluginInstaller
import com.xtreeroot.mb.plugin.backupSettings
import com.xtreeroot.mb.preferences.AppearanceBackgroundStore
import com.xtreeroot.mb.preferences.SettingsStore
import com.xtreeroot.mb.preferences.appearanceBackgroundByteLimit
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

internal data class PortableStatus(val linked: Boolean = false, val busy: Boolean = false,
                                   val savedAt: Long = 0, val error: PortableFailure? = null, val folderName: String? = null)

@OptIn(FlowPreview::class)
internal class PortableLibrary(private val context: Context, private val local: LocalAudioSource,
                               private val offline: OfflineMediaStore, private val playlists: LocalPlaylistStore,
                               private val settings: SettingsStore, private val plugins: PluginInstaller,
                               private val background: AppearanceBackgroundStore) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("portable-library", Context.MODE_PRIVATE)
    private val browse = context.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private val playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private var measuredBackground: Pair<Long, PortableAsset>? = null
    private fun linkedTree(): Uri? = preferences.getString("tree", null)?.let(Uri::parse)
    private val state = MutableStateFlow(PortableStatus(linkedTree() != null, savedAt = preferences.getLong("savedAt", 0),
        folderName = preferences.getString("folderName", null)))
    private val changes = MutableStateFlow(0L)
    private val restored = MutableStateFlow<QueueSnapshot?>(null)
    val status = state.asStateFlow()
    val restoredQueue = restored.asStateFlow()

    init {
        val retainedDownloads = offline.snapshot.map { saved -> saved.downloads.map(SavedAudio::fileName) to saved.limitMb }
            .distinctUntilChanged()
        scope.launch {
            val appearanceAndPlugins = combine(settings.values, plugins.changes, background.changes) { _, _, _ -> Unit }
            combine(local.catalog.changes, retainedDownloads, playlists.playlists, changes, appearanceAndPlugins) { _, _, _, _, _ -> Unit }
                .debounce(1200).collect { linkedTree()?.let { tree -> runOperation { checkpoint(tree) } } }
        }
    }

    fun userStateChanged() { changes.update { it + 1 } }

    fun restoreDeviceQueue(snapshot: QueueSnapshot) {
        if (playback.getString("queue", null) != null) return
        playback.edit(commit = true) { putString("queue", snapshot.encode()) }
        restored.value = snapshot
    }

    fun acknowledgeQueue(snapshot: QueueSnapshot) { restored.compareAndSet(snapshot, null) }

    fun bind(tree: Uri?) {
        if (tree == null) return
        scope.launch {
            runOperation {
                val previous = linkedTree()
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                resolver.takePersistableUriPermission(tree, flags)
                try {
                    val documents = PortableDocuments(resolver, tree)
                    val directory = documents.library(create = true) ?: throw PortableFailure(PortableProblem.Write)
                    latest(directory)?.let { checkpoint ->
                        if (checkpoint.skippedUnreadable && previous == tree) throw PortableFailure(PortableProblem.Read)
                        restore(directory, checkpoint.manifest)
                        if (checkpoint.skippedUnreadable) throw PortableFailure(PortableProblem.RestoredPrevious)
                    }
                    val stamp = save(directory, verifyAssets = true)
                    val folder = documents.folderName()
                    preferences.edit(commit = true) {
                        putString("tree", tree.toString())
                        putString("folderName", folder)
                        putLong("savedAt", stamp)
                    }
                    state.value = PortableStatus(true, savedAt = stamp, folderName = folder)
                } catch (failure: Exception) {
                    if (previous != tree) runCatching { resolver.releasePersistableUriPermission(tree, flags) }
                    throw failure
                }
                if (previous != null && previous != tree) runCatching { resolver.releasePersistableUriPermission(previous, flags) }
            }
        }
    }

    fun saveNow() {
        scope.launch { runOperation { linkedTree()?.let { checkpoint(it, verifyAssets = true) } } }
    }

    private suspend fun runOperation(action: suspend () -> Unit) = operations.withLock {
        state.value = state.value.copy(busy = true, error = null)
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = when (failure) {
                is PortableFailure -> failure
                is SecurityException -> PortableFailure(PortableProblem.Permission)
                else -> PortableFailure(PortableProblem.Unknown)
            }
            state.value = state.value.copy(error = error)
        }
        finally { state.value = state.value.copy(busy = false) }
    }

    private suspend fun checkpoint(tree: Uri, verifyAssets: Boolean = false) {
        if (tree != linkedTree()) return
        val documents = PortableDocuments(resolver, tree)
        val directory = documents.library(create = false) ?: throw PortableFailure(PortableProblem.FolderMissing)
        val stamp = save(directory, verifyAssets)
        val folder = state.value.folderName ?: documents.folderName()
        preferences.edit { putLong("savedAt", stamp); putString("folderName", folder) }
        state.value = PortableStatus(true, savedAt = stamp, folderName = folder)
    }

    private fun latest(directory: PortableDocuments.PortableDirectory,
                       files: Map<String, PortableDocument> = directory.files()): PortableCheckpoint? =
        latestPortableCheckpoint(files.mapValues { it.value.size }) { name -> directory.open(files.getValue(name)) }

    private suspend fun save(directory: PortableDocuments.PortableDirectory, verifyAssets: Boolean): Long {
        offline.refresh()
        playlists.refresh()
        val files = directory.files()
        val prior = latest(directory, files)
        if (prior?.skippedUnreadable == true) throw PortableFailure(PortableProblem.Read)
        val knownAssets = prior?.manifest?.assets.orEmpty().associateBy(PortableAsset::name)
        val coroutine = currentCoroutineContext()
        val writtenAssets = mutableListOf<PortableAsset>()
        fun matches(document: PortableDocument, asset: PortableAsset): Boolean {
            if (document.size >= 0 && document.size != asset.bytes) return false
            return try {
                directory.open(document).use { transfer(it, null, asset.name, asset.bytes, coroutine) == asset }
            } catch (_: AssetLengthMismatch) { false }
        }
        fun reuse(asset: PortableAsset): Boolean {
            val existing = files[asset.name] ?: return false
            return if (verifyAssets) matches(existing, asset) else existing.size == asset.bytes
        }
        fun sizeOf(file: File): Long = file.length().takeIf { it in 1..PortableAsset.maximumBytes }
            ?: throw PortableFailure(PortableProblem.SourceFile, file.name)
        fun writeAsset(file: File, name: String, size: Long, expectedDigest: String? = null): PortableAsset {
            files[name]?.let(directory::delete)
            var asset: PortableAsset? = null
            val mime = if (PortableAsset.backgroundName.matches(name)) "image/png" else "application/octet-stream"
            directory.write(name, mime) { output ->
                asset = file.inputStream().use { transfer(it, output, name, size, coroutine) }
                if (expectedDigest != null && expectedDigest != asset?.digest) {
                    val problem = if (PortableAsset.importedName.matches(name)) PortableProblem.LocalAudioInvalid else PortableProblem.InvalidAsset
                    throw PortableFailure(problem, name)
                }
            }
            return checkNotNull(asset).also(writtenAssets::add)
        }
        fun store(file: File, name: String, expectedDigest: String? = null): PortableAsset {
            coroutine.ensureActive()
            val size = sizeOf(file)
            val previous = (expectedDigest ?: knownAssets[name]?.digest)?.let { PortableAsset(name, size, it) }
            return previous?.takeIf(::reuse) ?: writeAsset(file, name, size, expectedDigest)
        }
        val activity = local.catalog.activity()
        val activityById = activity.associateBy(LocalTrackActivity::trackId)
        val imports = local.catalog.list().map { item ->
            val id = item.track.sourceTrackId
            val history = activityById[item.track.id]
            PortableImport(id, item.track.title, store(local.catalog.file(id), "mb-import-$id.audio", id),
                local.catalog.lyrics(id), (history?.likedAt ?: 0) > 0, history?.playedAt ?: 0, item.folderId, item.addedAt)
        }
        val saved = offline.snapshot.value
        val downloads = saved.downloads.map { item ->
            val lease = offline.lease(item.track.id, downloadsOnly = true) ?: throw PortableFailure(PortableProblem.DownloadsChanged)
            lease.use { PortableDownload(item.track, store(it.path, "mb-saved-${item.fileName}"), item.used) }
        }
        val queue = playback.getString("queue", null)?.let(QueueSnapshot::decode)
        val packages = plugins.installed().map { plugin ->
            val name = "mb-plugin-${plugin.revision}.zip"
            val known = knownAssets[name]?.takeIf(::reuse)
            val asset = known ?: File.createTempFile("mb-plugin-", ".zip", context.cacheDir).let { archive ->
                try {
                    plugins.archiveForBackup(plugin, archive)
                    writeAsset(archive, name, sizeOf(archive))
                } finally { archive.delete() }
            }
            PortablePlugin(plugin.id, plugin.repository, asset, plugin.manifest.backupSettings(plugins.settings(plugin)))
        }
        val backdrop = background.snapshot { file, revision ->
            val size = sizeOf(file).takeIf { it <= appearanceBackgroundByteLimit }
                ?: throw PortableFailure(PortableProblem.SourceFile, file.name)
            val measured = measuredBackground?.takeIf { it.first == revision }?.second ?: file.inputStream().use { input ->
                transfer(input, null, file.name, size, coroutine).let { it.copy(name = "mb-background-${it.digest}.png") }
            }.also { measuredBackground = revision to it }
            store(file, measured.name, measured.digest)
        }
        val manifest = PortableManifest(imports, downloads, playlists.playlists.value, saved.limitMb, readHistory(), queue,
            settings.values.value, packages, activity, backdrop)
        if (verifyAssets && writtenAssets.isNotEmpty()) {
            val savedFiles = directory.files()
            for (asset in writtenAssets) {
                if (savedFiles[asset.name]?.let { matches(it, asset) } != true) {
                    throw PortableFailure(PortableProblem.InvalidAsset, asset.name)
                }
            }
        }
        val encoded = manifest.encode()
        if (encoded.size > PortableManifest.maxManifestBytes) throw PortableFailure(PortableProblem.IndexTooLarge)
        if (prior != null && writtenAssets.isEmpty() && prior.manifest.encode().contentEquals(encoded)) return prior.stamp
        val stamp = maxOf(System.currentTimeMillis(), (prior?.stamp ?: 0) + 1)
        val snapshot = "mb-state-$stamp-${UUID.randomUUID()}.json"
        directory.write(snapshot, "application/json", verify = {
            val document = directory.files()[snapshot] ?: throw PortableFailure(PortableProblem.Write, snapshot)
            val confirmed = try { directory.open(document).use { portableIndexMatches(it, encoded) } }
            catch (_: IOException) { false }
            if (!confirmed) throw PortableFailure(PortableProblem.Write, snapshot)
        }) { it.write(encoded) }
        val previous = prior?.name
        val keep = (knownAssets.keys + manifest.assets.map(PortableAsset::name)).toSet()
        files.values.filter { document ->
            PortableManifest.snapshotName.matches(document.name) && document.name != previous ||
                (PortableAsset.importedName.matches(document.name) || PortableAsset.downloadedName.matches(document.name) ||
                    PortableAsset.backgroundName.matches(document.name) || PortablePlugin.fileName.matches(document.name)) && document.name !in keep
        }.forEach { document -> runCatching { directory.delete(document) } }
        return stamp
    }

    private suspend fun restore(directory: PortableDocuments.PortableDirectory, manifest: PortableManifest) {
        offline.refresh()
        val imports = manifest.imports.filterNot {
            local.catalog.file(it.id).length() == it.asset.bytes && local.catalog.get(it.id) != null
        }
        val downloads = manifest.downloads.filterNot { offline.hasIntactDownload(it.track.id) }
        val installed = plugins.installed().mapTo(mutableSetOf()) { it.id }
        val packages = manifest.plugins.filterNot { it.id in installed }
        val backdrop = manifest.background?.takeIf { background.snapshot { _, _ -> true } == null }
        val files = directory.files()
        val staging = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "mb-restore-").toFile()
        try {
            val coroutine = currentCoroutineContext()
            val assets = imports.map(PortableImport::asset) + downloads.map(PortableDownload::asset) +
                packages.map(PortablePlugin::asset) + listOfNotNull(backdrop)
            for (asset in assets) {
                val document = files[asset.name] ?: throw PortableFailure(PortableProblem.MissingAsset, asset.name)
                try {
                    File(staging, asset.name).outputStream().use { output ->
                        val copied = directory.open(document).use { input -> transfer(input, output, asset.name, asset.bytes, coroutine) }
                        if (asset != copied) throw PortableFailure(PortableProblem.InvalidAsset, asset.name)
                    }
                } catch (_: AssetLengthMismatch) { throw PortableFailure(PortableProblem.InvalidAsset, asset.name) }
            }
            backdrop?.let { asset ->
                try { background.restoreIfMissing(File(staging, asset.name)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw PortableFailure(PortableProblem.InvalidAsset, asset.name) }
            }
            imports.asReversed().forEach { item -> local.restoreImport(File(staging, item.asset.name), item) }
            downloads.asReversed().forEach { item ->
                offline.restoreDownload(File(staging, item.asset.name), item.track, item.asset.name.removePrefix("mb-saved-"), item.addedAt)
            }
            packages.forEach { item -> plugins.restoreArchive(item.id, item.repository, File(staging, item.asset.name), item.settings) }
            local.catalog.restoreActivity(manifest.activity)
            playlists.merge(manifest.playlists)
            offline.setLimit(manifest.limitMb)
            manifest.preferences?.let(settings::restoreIfUnset)
            val history = (readHistory() + manifest.history).distinct().take(30)
            browse.edit { putString("search_history", JSONArray(history).toString()) }
            if (playback.getString("queue", null) == null) manifest.queue?.let { queue ->
                val hydrated = queue.copy(tracks = queue.tracks.map { track ->
                    if (track.sourceId == localSourceId && PortableAsset.hash.matches(track.sourceTrackId)) {
                        local.catalog.get(track.sourceTrackId)?.track ?: track
                    } else track
                })
                playback.edit { putString("queue", hydrated.encode()) }
                restored.value = hydrated
            }
        } finally { staging.deleteRecursively() }
    }

    private fun readHistory(): List<String> = runCatching {
        val terms = JSONArray(browse.getString("search_history", "[]"))
        (0 until minOf(terms.length(), 30)).map(terms::getString)
    }.getOrDefault(emptyList())

    companion object {
        private class AssetLengthMismatch : IOException()

        private fun transfer(input: InputStream, output: OutputStream?, name: String, expected: Long,
                             coroutine: CoroutineContext): PortableAsset {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > expected) throw AssetLengthMismatch()
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            if (bytes != expected) throw AssetLengthMismatch()
            return PortableAsset(name, bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
