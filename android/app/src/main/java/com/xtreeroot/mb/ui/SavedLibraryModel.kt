package com.xtreeroot.mb.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.LocalPlaylist
import com.xtreeroot.mb.local.LocalTrackActivity
import com.xtreeroot.mb.local.OfflineTransfers
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SavedLibraryModel(application: Application, private val downloads: OfflineTransfers) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as MBApplication).offline)

    private val app = application as MBApplication
    private val store = downloads.store
    private val playlistStore = app.playlists
    private val activityState = MutableStateFlow(emptyMap<String, LocalTrackActivity>())
    private val refreshingState = MutableStateFlow(false)
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = notices.asSharedFlow()
    val offline = store.snapshot
    val transfers = downloads.transfers
    val transferError = downloads.error
    val resources = app.resourceBookmarks.tracks
    val playlists = playlistStore.playlists
    val portable = app.portableLibrary
    val activity = activityState.asStateFlow()
    val refreshing = refreshingState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            app.local.catalog.changes.collect {
                try { activityState.value = app.local.catalog.activity().associateBy(LocalTrackActivity::trackId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { report(failure) }
            }
        }
    }

    fun refresh() {
        if (refreshingState.value) return
        refreshingState.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.refresh(); playlistStore.refresh(); app.resourceBookmarks.refresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { refreshingState.value = false }
        }
    }

    fun saveResource(track: Track) = runIO { app.resourceBookmarks.save(track) }
    fun removeResource(id: String) = runIO { app.resourceBookmarks.remove(id) }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            try { app.local.catalog.toggleFavorite(track.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun download(track: Track) = downloads.download(track)
    fun cancel(id: String) = downloads.cancel(id)
    fun removeDownload(id: String) = runIO { store.removeDownload(id) }
    fun clearCache() = runIO { store.clearCache() }
    fun setLimit(mb: Int) = runIO { store.setLimit(mb) }
    fun create(name: String, tracks: List<String> = emptyList()) = runIO { playlistStore.create(name, tracks) }
    fun rename(id: String, name: String) = runIO { playlistStore.rename(id, name) }
    fun delete(playlist: LocalPlaylist) = runIO { playlistStore.delete(playlist.id) }
    fun add(id: String, tracks: List<String>) = runIO { playlistStore.add(id, tracks) }
    fun remove(id: String, track: String) = runIO { playlistStore.remove(id, track) }
    fun move(id: String, track: String, step: Int, available: Set<String>) = runIO { playlistStore.move(id, track, step, available) }
    fun reorder(id: String, from: Int, to: Int, visible: List<String>) = runIO { playlistStore.reorder(id, from, to, visible) }

    suspend fun exportName(track: Track): String = withContext(Dispatchers.IO) {
        withExportFile(track) { file -> exportName(track, file) }
    }

    private fun exportName(track: Track, file: File): String {
        val extension = if (track.sourceId == localSourceId) {
            val extractor = MediaMetadataRetriever()
            val mime = try {
                extractor.setDataSource(file.path)
                extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            } finally { extractor.release() }
            if (mime == "audio/mp4" || mime == "video/mp4") "m4a"
            else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "audio"
        } else file.extension
        val title = track.title.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().take(80)
            .trimEnd(Char::isHighSurrogate).ifEmpty { "山海音频" }
        return "$title.$extension"
    }

    suspend fun shareAudio(track: Track): Intent = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        withExportFile(track) { source ->
            val cache = File(app.cacheDir, "audio-share").apply { mkdirs() }
            val expiresBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            cache.listFiles()?.filter { it.isDirectory && it.lastModified() < expiresBefore }?.forEach(File::deleteRecursively)
            val directory = Files.createTempDirectory(cache.toPath(), "track-").toFile()
            try {
                val shared = File(directory, exportName(track, source))
                source.inputStream().use { input ->
                    shared.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                coroutine.ensureActive()
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", shared)
                val mime = app.contentResolver.getType(uri).orEmpty().takeIf { it.startsWith("audio/") } ?: "audio/*"
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(track.title, uri)
                Intent.createChooser(send, null)
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw failure
            }
        }
    }

    private inline fun <Result> withExportFile(track: Track, useFile: (File) -> Result): Result {
        val lease = if (track.sourceId == localSourceId) null else store.lease(track.id, downloadsOnly = true)
            ?: throw IOException("下载文件不存在")
        return lease.use { useFile(lease?.path ?: app.local.catalog.file(track.sourceTrackId)) }
    }

    fun export(track: Track, uri: Uri) = runIO {
        withExportFile(track) { file ->
            file.inputStream().use { input ->
                val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入导出文件")
                output.use { input.copyTo(it) }
            }
        }
        notices.tryEmit("音频已导出")
    }

    fun report(failure: Exception) { notices.tryEmit(failure.localizedMessage ?: "操作失败") }

    private fun runIO(action: () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
}
