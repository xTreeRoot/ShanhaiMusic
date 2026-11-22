package com.xtreeroot.mb.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.music.MediaResource
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** 显式运行的原生视频基准；同一媒体、80ms 延迟、每 100ms 发送 16KiB。 */
@OptIn(UnstableApi::class)
class VideoPerformanceDeviceTest {
    @Test fun coldAndPreparedVideoProduceRealFrames() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("mbVideoBenchmark") != "true") return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("video-performance.mp4").use { it.readBytes() }
        val transferred = AtomicLong()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val range = chain.request().header("Range")?.removePrefix("bytes=")?.split('-')
            val first = range?.get(0)?.toInt() ?: 0
            val last = minOf(bytes.lastIndex, range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex)
            Thread.sleep(80)
            val body = object : ResponseBody() {
                private val input = object : ForwardingSource(Buffer().write(bytes, first, last - first + 1)) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        return super.read(sink, minOf(byteCount, 16384)).also {
                            if (it > 0) { Thread.sleep((it * 100 / 16384).coerceAtLeast(1)); transferred.addAndGet(it) }
                        }
                    }
                }.buffer()
                override fun contentLength() = (last - first + 1).toLong()
                override fun contentType() = "video/mp4".toMediaType()
                override fun source(): BufferedSource = input
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (range == null) 200 else 206)
                .message("Fixture").header("Content-Length", body.contentLength().toString())
                .header("Content-Type", "video/mp4").header("Content-Range", "bytes $first-$last/${bytes.size}")
                .header("ETag", "\"video-performance-v1\"").body(body).build()
        }.build()
        val cold = JSONArray(); val prepared = JSONArray(); val preparation = JSONArray()
        for (warming in listOf(false, true)) for (index in 0..2) {
            val media = MediaResource("https://fixture.invalid/video.mp4?item=$index", emptyMap(), "mp4")
            val started = SystemClock.elapsedRealtimeNanos()
            val prefix = if (warming) primeAudio(client, media)?.prefix else null
            if (warming) checkNotNull(prefix)
            if (warming) preparation.put((SystemClock.elapsedRealtimeNanos() - started) / 1e6)
            val firstFrame = CompletableDeferred<Long>()
            val guard = MediaRangeGuard().apply { prefix?.let(::offer) }
            val surface = withContext(Dispatchers.Main) { PlaceholderSurface.newInstance(instrumentation.targetContext, false) }
            var player: ExoPlayer? = null
            val begin = SystemClock.elapsedRealtimeNanos()
            try {
                withContext(Dispatchers.Main) {
                    player = ExoPlayer.Builder(instrumentation.targetContext).setLoadControl(DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1_500, 8_000, 250, 500).setTargetBufferBytes(12 * 1024 * 1024).build()).setMediaSourceFactory(ProgressiveMediaSource.Factory {
                        MediaAddressDataSource(guardedMediaHttp(client, guard))
                    }).build().apply {
                        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                        volume = 0f; setVideoSurface(surface)
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { firstFrame.complete(SystemClock.elapsedRealtimeNanos()) }
                        })
                        setMediaItem(MediaItem.fromUri(media.url)); prepare(); play()
                    }
                }
                val elapsed = withTimeout(20_000) { (firstFrame.await() - begin) / 1e6 }
                (if (warming) prepared else cold).put(elapsed)
            } finally { withContext(Dispatchers.Main) { player?.release(); surface.release() } }
        }
        val result = JSONObject().put("coldFirstFrameMilliseconds", cold).put("preparedFirstFrameMilliseconds", prepared)
            .put("backgroundPreparationMilliseconds", preparation).put("transferredBytes", transferred.get())
            .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
        File(instrumentation.targetContext.cacheDir, "video-performance.json").writeText(result.toString(2))
    }
}
