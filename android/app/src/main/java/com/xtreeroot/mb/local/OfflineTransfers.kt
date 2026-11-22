package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.SourceResponseFailure
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.isMediaAddressUnavailable
import com.xtreeroot.mb.music.isMediaConnectionFailure
import com.xtreeroot.mb.music.maxMediaBackupAddresses
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class Transfer(val track: Track, val received: Long = 0, val expected: Long = -1)

internal class OfflineTransfers(
    val store: OfflineMediaStore,
    private val open: (URL) -> java.net.URLConnection,
    private val resolve: suspend (Track) -> MediaResource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val active = MutableStateFlow(emptyMap<String, Transfer>())
    private val failure = MutableStateFlow<String?>(null)
    private val slots = Semaphore(2)
    val transfers = active.asStateFlow()
    val error = failure.asStateFlow()

    fun download(track: Track) {
        if (track.id in jobs || track.sourceId == localSourceId) return
        failure.value = null
        active.update { it + (track.id to Transfer(track)) }
        jobs[track.id] = scope.launch {
            try {
                slots.withPermit {
                    withContext(Dispatchers.IO) {
                        if (!store.promote(track)) save(track, resolve(track), null) { received, expected ->
                            active.update { it + (track.id to Transfer(track, received, expected)) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure.value = "下载失败：${track.title}"
            } finally {
                jobs.remove(track.id)
                active.update { it - track.id }
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    suspend fun fillCache(track: Track, resource: MediaResource) = withContext(Dispatchers.IO) {
        if (track.sourceId == localSourceId || active.value.containsKey(track.id)) return@withContext
        val ticket = store.cacheTicket(track.id) ?: return@withContext
        save(track, resource, ticket) { _, _ -> }
    }

    private suspend fun save(track: Track, resource: MediaResource, ticket: OfflineMediaStore.CacheTicket?,
                             progress: (Long, Long) -> Unit) {
        val staged = store.staging()
        try {
            transfer(resource, staged, ticket?.maximumBytes ?: 8L * 1024 * 1024 * 1024, progress)
            currentCoroutineContext().ensureActive()
            store.save(staged, track, resource.fileExtension, ticket)
        } finally { staged.delete() }
    }

    private suspend fun transfer(resource: MediaResource, destination: File, maximum: Long,
                                  progress: (Long, Long) -> Unit) {
        val context = currentCoroutineContext()
        if (java.net.URI(resource.url).scheme == "file") {
            val source = File(java.net.URI(resource.url))
            val size = source.length()
            if (!source.isFile || size <= 0 || size > maximum) throw IOException("本地音频超过保存上限或不可用")
            var copied = 0L
            source.inputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > maximum) throw IOException("本地音频超过保存上限")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            if (copied != size) throw EOFException("本地音频不完整")
            progress(copied, size)
            return
        }
        val addresses = listOf(resource.url) + resource.backupUrls.take(maxMediaBackupAddresses)
        for ((index, address) in addresses.withIndex()) {
            try {
                transferFrom(URL(address), resource.headers, destination, maximum, progress)
                return
            } catch (failure: IOException) {
                context.ensureActive()
                val retry = if (failure is SourceResponseFailure) failure.kind == SourceResponseFailure.Kind.HTTP &&
                    failure.code.isMediaAddressUnavailable()
                    else failure.isMediaConnectionFailure()
                if (!retry || index == addresses.lastIndex) throw failure
                progress(0, -1)
            }
        }
    }

    private suspend fun transferFrom(initial: URL, initialHeaders: Map<String, String>, destination: File, maximum: Long,
                                      progress: (Long, Long) -> Unit) {
        var address = initial
        var headers = initialHeaders
        repeat(10) {
            currentCoroutineContext().ensureActive()
            if (address.protocol != "https" || address.host.isBlank() || address.userInfo != null) {
                throw IOException("音频地址不受支持")
            }
            val connection = open(address) as HttpsURLConnection
            val next = try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (val status = connection.responseCode) {
                    200 -> { writeAudio(connection, destination, maximum, progress); return }
                    301, 302, 303, 307, 308 -> URL(address, connection.getHeaderField("Location")?.takeIf(String::isNotBlank)
                        ?: throw IOException("音频重定向缺少地址"))
                    else -> throw SourceResponseFailure(SourceResponseFailure.Kind.HTTP, status, "没有收到完整的音频响应")
                }
            } finally { connection.disconnect() }
            if (address.protocol != next.protocol || !address.host.equals(next.host, ignoreCase = true) ||
                address.effectivePort != next.effectivePort) headers = emptyMap()
            address = next
        }
        throw IOException("音频重定向次数过多")
    }

    private suspend fun writeAudio(connection: HttpsURLConnection, destination: File, maximum: Long,
                                    progress: (Long, Long) -> Unit) {
        val context = currentCoroutineContext()
        val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
        if (mime.startsWith("text/") || mime == "application/json") throw IOException("响应不是音频")
        val expected = connection.contentLengthLong
        if (expected > maximum) throw IOException("音频超过保存上限")
        var received = 0L
        var reported = 0L
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > maximum) throw IOException("音频超过保存上限")
                    output.write(buffer, 0, count)
                    if (received - reported >= 128 * 1024) { reported = received; progress(received, expected) }
                }
                if (received <= 0 || (expected >= 0 && received != expected)) throw EOFException("音频响应不完整")
                output.fd.sync()
            }
        }
        progress(received, expected)
    }

    private val URL.effectivePort get() = if (port == -1) defaultPort else port
}
