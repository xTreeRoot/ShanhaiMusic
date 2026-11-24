package com.xtreeroot.mb.edge

import android.content.Context
import androidx.core.content.edit
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.*
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import com.xtreeroot.mb.playback.QueueSnapshot
import com.xtreeroot.mb.plugin.DiscoveryPreferences
import com.xtreeroot.mb.plugin.restoreDeviceArchivePlugins
import com.xtreeroot.mb.plugin.restorePluginInformation
import com.xtreeroot.mb.plugin.PluginPreferences
import com.xtreeroot.mb.preferences.AppPreferences
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 完整设备备份与恢复；所有网络、压缩和媒体工作在 IO 线程执行，节点身份不迁移。 */
internal class NodeDeviceBackup(private val app: MBApplication, private val node: NodeBinding) {
    suspend fun pluginChoices(device: SettingsDevice, settings: DeviceSettings): List<BackupPluginChoice> = withContext(Dispatchers.IO) {
        withArchive(device, settings) { doc, _ ->
            doc.getJSONArray("plugins").objects().map { BackupPluginChoice(it.getString("id"), it.text("name") ?: it.getString("id")) }
        }
    }

    suspend fun restorePlugin(device: SettingsDevice, settings: DeviceSettings, id: String) = withContext(Dispatchers.IO) {
        withArchive(device, settings) { doc, stage ->
            app.retirePluginForDataRestore(id)
            try { app.plugins.restorePluginInformation(doc, stage, id) { active() } }
            finally { withContext(NonCancellable) { app.refreshPlugins(reloadIds = setOf(id)) } }
        }
    }

    private suspend fun <T> withArchive(device: SettingsDevice, settings: DeviceSettings, apply: suspend (JSONObject, File) -> T): T {
        val directory = directory()
        try {
            active()
            val archive = File(directory, "device.zip")
            transfer.download(device.key, requireNotNull(settings.backup), archive)
            val stage = File(directory, "content")
            val doc = DeviceArchive.extract(archive, stage)
            active()
            return apply(doc, stage)
        } finally { directory.deleteRecursively() }
    }

    private val transfer = DeviceBackupTransfer(app.nodeClient, node)
    private fun checkCurrent() { check(app.settings.nodeBinding.value === node) { "Node changed" } }
    private suspend fun active() { currentCoroutineContext().ensureActive(); checkCurrent() }
    private fun directory(): File = File(app.filesDir, "device-backups/operation-${UUID.randomUUID()}").also { check(it.mkdirs()) }

    suspend fun backup(accounts: Map<String, String>) = withContext(Dispatchers.IO) {
        val directory = directory()
        try {
            active()
            val devices = app.nodeClient.settingsDevices(node)
            val previous = devices.firstOrNull { it.current && it.updated > 0 }?.let { app.nodeClient.readSettingsSnapshot(node, it) }
            val plugins = app.plugins.installed()
            val records = JSONArray(); val files = mutableListOf<DeviceArchiveEntry>()
            for (plugin in plugins) {
                active()
                val archive = File(directory, plugin.id + ".zip")
                app.plugins.archiveForBackup(plugin, archive)
                records.put(JSONObject().put("id", plugin.id).put("repository", plugin.repository).put("revision", plugin.remoteRevision)
                    .put("name", plugin.localName).put("enabled", plugin.enabled).put("settings", JSONObject(app.plugins.settings(plugin))))
                files += DeviceArchiveEntry("plugins/${plugin.id}/package.zip", archive)
                files += DeviceArchive.tree(plugin.dataDirectory, "plugins/${plugin.id}/data")
            }
            val tracks = linkedMapOf<String, JSONObject>()
            suspend fun asset(track: Track, file: File, kind: String): JSONObject {
                val digest = DeviceArchive.digest(file)
                val extension = mediaExtension(file)
                val name = "media/$digest.$extension"
                if (files.none { it.name == name }) files += DeviceArchiveEntry(name, file)
                return track.toMetadataJson().put("asset", name).put("kind", kind)
            }
            for (item in app.local.catalog.list()) {
                val track = item.track
                tracks[track.id] = asset(track, app.local.catalog.file(track.sourceTrackId), "imported")
                    .put("lyrics", app.local.catalog.lyrics(track.sourceTrackId)).put("album", item.album)
                    .put("albumArtist", item.albumArtist).put("year", item.year).put("trackNumber", item.number)
                    .put("discNumber", item.disc).put("folder", item.folderId)
            }
            val leases = mutableListOf<OfflineMediaStore.Lease>()
            val output = File(directory, "device.zip")
            try {
                app.offline.store.refresh()
                for (saved in app.offline.store.snapshot.value.downloads) {
                    active()
                    val lease = app.offline.store.lease(saved.track.id, downloadsOnly = true) ?: error("Downloads changed")
                    leases += lease
                    if (saved.track.id !in tracks) tracks[saved.track.id] = asset(saved.track, lease.path, "download")
                }
                app.resourceBookmarks.refresh()
                for (track in app.resourceBookmarks.tracks.value) tracks.putIfAbsent(track.id, track.toMetadataJson())
                val activity = app.local.catalog.activity()
                app.playlists.refresh()
                val browse = app.getSharedPreferences("browse", Context.MODE_PRIVATE)
                val library = JSONObject().put("tracks", JSONArray(tracks.values.toList()))
                    .put("favorites", JSONArray(activity.filter { it.likedAt > 0 }.map { it.trackId }))
                    .put("recent", JSONArray(activity.filter { it.playedAt > 0 }.sortedByDescending { it.playedAt }.map { it.trackId }))
                    .put("playlists", JSONArray(app.playlists.playlists.value.map { JSONObject().put("id", it.id).put("name", it.name).put("tracks", JSONArray(it.trackIds)) }))
                    .put("searchHistory", JSONArray(browse.getString("search_history", "[]")))
                val queue = app.getSharedPreferences("playback", Context.MODE_PRIVATE).getString("queue", null)?.let(QueueSnapshot::decode)
                queue?.let { library.put("queue", JSONObject().put("tracks", JSONArray(it.forPortableStorage().tracks.map(Track::toMetadataJson)))
                    .put("index", it.index).put("position", it.positionMs).put("shuffled", it.shuffle)
                    .put("repeat", listOf("off", "one", "all")[it.repeatMode.coerceIn(0, 2)])) }
                val preferences = File(directory, "preferences.json").apply { writeText(app.settings.values.value.toJson().toString()) }
                files += DeviceArchiveEntry("native/android/preferences.json", preferences)
                app.appearanceBackground.snapshot { file, _ -> files.add(DeviceArchiveEntry("native/android/background.png", file)) }
                val doc = JSONObject().put("version", 1).put("platform", "android").put("plugins", records).put("library", library)
                    .put("pluginPreferences", app.plugins.pluginPreferences().toJson()).put("discoveryPreferences", app.plugins.loadDiscoveryPreferences().toJson())
                DeviceArchive.write(doc, files, output)
            } finally { leases.forEach { it.close() } }
            active()
            val ref = transfer.upload(output, "android", plugins.size)
            try {
                active()
                app.nodeClient.saveSettingsSnapshot(node, DeviceSettings(app.settings.values.value.deviceSettings(), emptyMap(), accounts, ref, previous?.backup))
            } catch (failure: Exception) {
                // The node may have committed the reference before its reply
                // was lost. Retain the complete objects in that case.
                throw failure
            }
            previous?.previousBackup?.let { runCatching { transfer.delete(it) } }
        } finally { directory.deleteRecursively() }
    }

    suspend fun restore(device: SettingsDevice, settings: DeviceSettings) = withContext(Dispatchers.IO) {
        val ref = requireNotNull(settings.backup)
        val directory = directory()
        try {
            active()
            val archive = File(directory, "device.zip")
            transfer.download(device.key, ref, archive)
            val stage = File(directory, "content")
            val doc = DeviceArchive.extract(archive, stage)
            val preferences = if (doc.getString("platform") == "android") AppPreferences.parse(JSONObject(File(stage, "native/android/preferences.json").readText())) else null
            active()
            app.plugins.restoreDeviceArchivePlugins(doc, stage) { active() }
            val library = doc.getJSONObject("library"); val mapped = mutableMapOf<String, Track>()
            for (item in library.optJSONArray("tracks").objects()) {
                active()
                var track = item.toTrackMetadata(); val key = track.id
                item.text("asset")?.let { name ->
                    val original = File(stage, name)
                    val file = File(directory, UUID.randomUUID().toString() + "." + original.extension)
                    original.copyTo(file)
                    if (track.sourceId == localSourceId || item.text("kind") == "imported") {
                        val digest = DeviceArchive.digest(file)
                        val imported = app.local.restoreImport(file, PortableImport(digest, track.title, PortableAsset(name, file.length(), digest),
                            item.text("lyrics").orEmpty(), false, 0, item.text("folder")), item)
                        track = imported.track
                    } else {
                        app.offline.store.restoreDownload(file, track, "${UUID.randomUUID()}.${name.substringAfterLast('.')}")
                    }
                }
                if (track.sourceId != localSourceId) app.resourceBookmarks.save(track)
                mapped[key] = track
            }
            fun resolve(key: String) = mapped[key]?.id ?: key
            val favorites = library.optJSONArray("favorites").strings().map(::resolve).toSet()
            val recent = library.optJSONArray("recent").strings().map(::resolve)
            val now = System.currentTimeMillis()
            app.local.catalog.restoreActivity((favorites + recent).map { LocalTrackActivity(it, if (it in favorites) now else 0,
                recent.indexOf(it).takeIf { n -> n >= 0 }?.let { n -> now - n } ?: 0) })
            app.playlists.merge(library.optJSONArray("playlists").objects().map { LocalPlaylist(it.getString("id"), it.getString("name"), it.optJSONArray("tracks").strings().map(::resolve)) })
            val history = library.optJSONArray("searchHistory").strings().distinct().take(30)
            app.getSharedPreferences("browse", Context.MODE_PRIVATE).edit(commit = true) { putString("search_history", JSONArray(history).toString()) }
            library.optJSONObject("queue")?.let { queue ->
                val tracks = queue.optJSONArray("tracks").objects().map { mapped[it.toTrackMetadata().id] ?: it.toTrackMetadata() }
                if (tracks.isNotEmpty()) app.portableLibrary.restoreDeviceQueue(QueueSnapshot(tracks, queue.optInt("index").coerceIn(tracks.indices),
                    queue.optLong("position").coerceAtLeast(0), listOf("off", "one", "all").indexOf(queue.optString("repeat")).coerceAtLeast(0), queue.optBoolean("shuffled")))
            }
            active()
            if (preferences != null) app.settings.update { preferences }
            app.settings.update { it.restoreSettings(settings.app).first }
            File(stage, "native/android/background.png").takeIf { it.isFile }?.let { app.appearanceBackground.restoreIfMissing(it) }
            app.refreshPlugins(forceReload = true)
            app.offline.store.refresh()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun mediaExtension(file: File): String {
        val data = ByteArray(16)
        file.inputStream().use { it.read(data) }
        fun starts(value: String) = data.take(value.length).toByteArray().contentEquals(value.toByteArray(Charsets.US_ASCII))
        return when {
            starts("fLaC") -> "flac"
            starts("OggS") -> "ogg"
            starts("RIFF") -> "wav"
            starts("ID3") -> "mp3"
            data.size >= 8 && data.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "m4a"
            data.size >= 2 && data[0].toInt() and 255 == 255 && data[1].toInt() and 246 == 240 -> "aac"
            else -> file.extension.lowercase().takeIf { it in listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "alac", "mp4") } ?: "mp3"
        }
    }
}
