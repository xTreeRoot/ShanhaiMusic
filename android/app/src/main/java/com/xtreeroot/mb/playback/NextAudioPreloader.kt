package com.xtreeroot.mb.playback

import com.xtreeroot.mb.music.MediaResource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val audioPrefixBytes = 256 * 1024
internal const val preparedAudioLifetimeMs = 90_000L

internal fun canPrefetchAudio(positionMs: Long, durationMs: Long, playing: Boolean, videoActive: Boolean): Boolean =
    playing && !videoActive && durationMs > 0 && positionMs.toDouble() / durationMs >= 0.7

internal data class PreparedAudio(val resource: MediaResource, val prefix: MediaRangeGuard.Prefix)

internal suspend fun primeAudio(client: OkHttpClient, media: MediaResource): PreparedAudio? {
    // 音源解析已将授权容器准备到独立本地缓存，无需再发 HTTP Range 预载。
    if (java.net.URI(media.url).scheme == "file") return null
    return suspendCancellableCoroutine { result ->
        val guard = MediaRangeGuard()
        val request = Request.Builder().url(media.url).apply { media.headers.forEach(::header) }
            .header("Range", "bytes=0-${audioPrefixBytes - 1}").build()
        val call = guardedMediaClient(client, guard).newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val prefix = response.use { guard.prime(it, request.url) }
                    result.resume(prefix?.let { PreparedAudio(media, it) })
                } catch (failure: Exception) { result.resumeWithException(failure) }
            }
        })
    }
}

internal class NextAudioPreloader<Key : Any, Value : Any>(private val scope: CoroutineScope, private val now: () -> Long,
                                                         private val prepare: suspend (Key) -> Value?) {
    var failed: (Key, Exception) -> Unit = { _, _ -> }
    private data class Ready<Value>(val value: Value, val until: Long)
    private class Attempt<Key, Value>(val key: Key) {
        var running: Job? = null
        var ready: Ready<Value>? = null
    }

    private var attempt: Attempt<Key, Value>? = null
    private var handoff: Attempt<Key, Value>? = null

    @Synchronized fun update(key: Key?) {
        if (attempt?.key == key) return
        attempt?.running?.cancel()
        attempt = key?.let(::Attempt)
        val next = attempt ?: return
        next.running = scope.launch {
            val value = try { prepare(next.key) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(this@NextAudioPreloader) { if (attempt === next) failed(next.key, failure) }
                null
            }
            synchronized(this@NextAudioPreloader) {
                if (attempt === next && value != null) next.ready = Ready(value, now() + preparedAudioLifetimeMs)
            }
        }
    }

    @Synchronized fun advance(current: Key?) {
        handoff = attempt?.takeIf { it.key == current && it.ready?.until?.let { until -> now() < until } == true }
        update(null)
    }

    @Synchronized fun expire() {
        val time = now()
        if (attempt?.ready?.until?.let { it <= time } == true) attempt?.ready = null
        if (handoff?.ready?.until?.let { it <= time } == true) handoff = null
    }

    @Synchronized fun take(key: Key): Value? {
        expire()
        val selected = attempt?.takeIf { it.key == key } ?: handoff?.takeIf { it.key == key } ?: return null
        val ready = selected.ready.also { selected.ready = null }
        if (selected === handoff) handoff = null
        return ready?.value
    }

    @Synchronized fun cancel() {
        handoff = null
        update(null)
    }
}
