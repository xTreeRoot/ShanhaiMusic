package com.xtreeroot.mb.local

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.PlaybackSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LocalAudioDocument(val uri: Uri, val folderId: String? = null, val sidecar: Uri? = null)

internal class LocalAudioSource(private val context: Context, val catalog: LocalCatalog) : PlaybackSource {
    override val id = localSourceId
    private val resolver = context.contentResolver
    private val writes = Mutex()
    private val folderAudioExtensions = setOf("m4a", "aac", "mp3", "mp4", "flac", "wav", "ogg", "opus", "aiff", "aif", "caf", "wma")
    private val embeddedLyricTypes = setOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "video/mp4", "video/quicktime")
    private data class FolderDocument(val id: String, val mime: String, val name: String) {
        val extension get() = name.substringAfterLast('.', "").lowercase()
        val stem get() = name.substringBeforeLast('.')
    }

    override suspend fun search(query: String, page: Int): TrackPage {
        val term = query.trim()
        if (page != 1 || term.isEmpty()) return TrackPage(emptyList(), false)
        val matches = catalog.list().filter { item ->
            listOf(item.track.title, item.track.artist, item.album, item.albumArtist).any { it.contains(term, ignoreCase = true) }
        }
        return TrackPage(matches.map(LocalTrack::track), false)
    }

    override suspend fun audio(trackId: String): MediaResource = withContext(Dispatchers.IO) {
        val file = catalog.file(trackId).takeIf(File::isFile)
            ?: throw PlaybackIssueException(PlaybackIssue.SourceUnavailable, "本地音频文件不存在")
        MediaResource(Uri.fromFile(file).toString(), emptyMap())
    }

    override suspend fun captions(trackId: String): List<CaptionLine> = observeCaptions(trackId).first()

    fun observeCaptions(trackId: String): Flow<List<CaptionLine>> = catalog.changes
        .map { catalog.lyricDocument(trackId) }
        .distinctUntilChanged()
        .map { document -> document?.let { (lyrics, duration) -> parseLrc(lyrics, duration) }.orEmpty() }
        .flowOn(Dispatchers.IO)

    suspend fun attachLyrics(trackId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val text = readLyrics(uri)
        writes.withLock { catalog.writeLyrics(trackId, text) }
    }

    private suspend fun readLyrics(uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maximumLyricBytes + 1)
            var size = 0
            while (size < buffer.size) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
            }
            if (size == 0 || size == buffer.size) throw IOException("歌词文件为空或超过 2 MB")
            buffer.copyOf(size)
        } ?: throw IOException("无法读取歌词文件")
        return decodeLrcDocument(bytes) ?: throw IOException("没有可用的 LRC 时间戳")
    }

    suspend fun audioDocuments(tree: Uri): List<LocalAudioDocument> = withContext(Dispatchers.IO) {
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val visited = mutableSetOf<String>()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        buildList {
            while (pending.isNotEmpty()) {
                ensureActive()
                val folder = pending.removeFirst()
                if (!visited.add(folder)) continue
                val folderId = MessageDigest.getInstance("SHA-256")
                    .digest(DocumentsContract.buildDocumentUri(tree.authority, folder).toString().encodeToByteArray())
                    .joinToString("") { "%02x".format(it) }
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
                val files = resolver.query(children, columns, null, null, null)?.use { cursor ->
                    buildList<FolderDocument> {
                        while (cursor.moveToNext()) {
                            ensureActive()
                            val document = FolderDocument(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                            if (document.mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.addLast(document.id)
                            else add(document)
                        }
                    }
                } ?: throw IOException("无法读取所选文件夹")
                val backup = files.any { PortableManifest.snapshotName.matches(it.name) }
                val lyrics = files.filter { it.extension == "lrc" }.groupBy { it.stem.lowercase() }
                for (document in files) {
                    ensureActive()
                    if (backup && (PortableAsset.importedName.matches(document.name) ||
                        PortableAsset.downloadedName.matches(document.name))) continue
                    if (document.extension == "lrc" ||
                        !(document.mime.startsWith("audio/") || document.mime == "application/ogg" || document.extension in folderAudioExtensions)) continue
                    val candidates = lyrics[document.stem.lowercase()].orEmpty()
                    val match = candidates.singleOrNull { it.stem == document.stem } ?: candidates.singleOrNull()
                    add(LocalAudioDocument(DocumentsContract.buildDocumentUriUsingTree(tree, document.id), folderId,
                        match?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }))
                }
            }
        }
    }

    suspend fun importFile(document: LocalAudioDocument): LocalTrack = withContext(Dispatchers.IO) {
        val uri = document.uri
        val staged = catalog.temporaryFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri) ?: throw IOException("无法读取音频文件")
            input.use { stream ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val identifier = digest.digest().joinToString("") { "%02x".format(it) }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
            }
            val sidecar = document.sidecar?.let {
                try { readLyrics(it) } catch (_: IOException) { null } catch (_: SecurityException) { null }
            }
            saveImport(staged, identifier, name.orEmpty(), document.folderId, sidecar)
        } finally { staged.delete() }
    }

    suspend fun restoreImport(staged: File, item: PortableImport): LocalTrack =
        saveImport(staged, item.id, item.title, item.folderId, item.lyrics, item.addedAt)

    /** 恢复归档里的标签，避免无内嵌标签的音频显示摘要文件名。 */
    suspend fun restoreImport(staged: File, item: PortableImport, metadata: org.json.JSONObject): LocalTrack {
        val existed = catalog.get(item.id) != null
        val imported = restoreImport(staged, item)
        if (!existed) catalog.restoreMetadata(item.id, metadata)
        return catalog.get(item.id) ?: imported
    }

    private suspend fun saveImport(staged: File, identifier: String, name: String, folderId: String?, lyrics: String?,
                                   addedAt: Long = 0): LocalTrack =
        writes.withLock {
            val target = catalog.file(identifier)
            val existing = catalog.get(identifier)
            if (existing != null && target.length() == staged.length()) return@withLock existing
            verifyAudio(staged)
            val (metadata, picture, mime) = metadata(staged, identifier, name)
            val importedLyrics = lyrics ?: (if (mime in embeddedLyricTypes) embeddedLyrics(context, staged) else null).orEmpty()
            currentCoroutineContext().ensureActive()
            target.parentFile?.mkdirs()
            if (!staged.renameTo(target)) throw IOException("保存音频文件失败")
            if (existing != null) return@withLock existing
            try { catalog.write(metadata.copy(folderId = folderId, addedAt = addedAt), picture, importedLyrics) }
            catch (failure: Exception) { target.delete(); throw failure }
            checkNotNull(catalog.get(identifier))
        }

    suspend fun delete(identifier: String) = writes.withLock { catalog.delete(identifier) }

    suspend fun export(identifier: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = catalog.file(identifier)
        val target = resolver.openOutputStream(destination) ?: throw IOException("无法写入导出位置")
        target.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
    }

    private fun verifyAudio(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            if ((0 until extractor.trackCount).none { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }) throw IOException("文件不含可读取的音轨")
        } finally { extractor.release() }
    }

    private fun metadata(file: File, identifier: String, filename: String): Triple<LocalTrack, ByteArray?, String> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun field(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
            fun index(key: Int): Int = field(key).substringBefore('/').toIntOrNull()?.coerceAtLeast(0) ?: 0
            val title = field(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { filename.ifBlank { "未命名音频" } }
            val artist = field(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val duration = field(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?.coerceAtLeast(0) ?: 0
            val track = Track(id, identifier, title, artist, durationMs = duration)
            val value = LocalTrack(track, field(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                field(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), field(MediaMetadataRetriever.METADATA_KEY_YEAR),
                index(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER), index(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            val picture = retriever.embeddedPicture?.takeIf { it.isNotEmpty() && it.size <= 12 * 1024 * 1024 }
            return Triple(value, picture, field(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } finally { retriever.release() }
    }
}
