package com.xtreeroot.mb.playback

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.StationPlaybackSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.music.VideoSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class StreamingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun exoplayerStartsOnTheBackupAndKeepsThatCdnForSeeks() = startsOnTheBackup(403, "text/plain")

    @Test fun exoplayerSkipsAWebPageReturnedAsThePrimaryStream() = startsOnTheBackup(200, "text/html; charset=utf-8")

    @Test fun exoplayerSkipsAWebPageMislabelledAsBinary() = startsOnTheBackup(200, "application/octet-stream")

    private fun startsOnTheBackup(status: Int, contentType: String) = runBlocking {
        val wave = wave(180)
        val requests = CopyOnWriteArrayList<Request>()
        val primary = "https://primary.fixture.invalid/audio.wav"
        val backup = "https://backup.fixture.invalid/audio.wav"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            if (request.url.host == "primary.fixture.invalid") response(request, status, "<html>Unavailable</html>".encodeToByteArray())
                .header("Content-Type", contentType).build()
            else media(request, wave)
        }.build()
        val http = MediaAddressDataSource(guardedMediaHttp(client)).apply { backupUrls = listOf(backup) }
        val factory = ResolvingDataSource.Factory({ http }) { spec ->
            spec.withAdditionalHeaders(mapOf("Referer" to "https://source.fixture.invalid/"))
        }
        val player = withContext(Dispatchers.Main) {
            player(client, factory).apply {
                setMediaItem(MediaItem.fromUri(primary))
                prepare()
                play()
            }
        }
        try {
            eventually { player.isPlaying && player.currentPosition > 100 }
            withContext(Dispatchers.Main) { player.seekTo(165_000) }
            eventually { player.isPlaying && player.currentPosition > 165_100 }
            assertEquals(primary, requests.first().url.toString())
            assertTrue(requests.size >= 3)
            assertTrue(requests.drop(1).all { it.url.toString() == backup && it.header("Referer") == "https://source.fixture.invalid/" })
            assertTrue(requests.drop(2).any { it.header("Range")?.startsWith("bytes=") == true })
        } finally { withContext(Dispatchers.Main) { player.release() } }
    }

    @Test fun exoplayerReadsAReusedPrefixAndContinuesIntoTheTail() = runBlocking {
        val wave = wave(40)
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.header("Range").orEmpty()
            media(request, wave)
        }.build()
        val resource = MediaResource("https://fixture.invalid/next.wav", emptyMap())
        val prepared = checkNotNull(primeAudio(client, resource))
        assertEquals(listOf("bytes=0-${audioPrefixBytes - 1}"), requests.toList())
        val guard = MediaRangeGuard().apply { offer(prepared.prefix) }
        val player = withContext(Dispatchers.Main) {
            player(client, DataSource.Factory { guardedMediaHttp(client, guard) }).apply {
                setMediaItem(MediaItem.fromUri(resource.url))
                prepare()
                play()
            }
        }
        try {
            eventually { player.isPlaying && player.currentPosition > 100 }
            withContext(Dispatchers.Main) {
                assertEquals(40_000L, player.duration)
                assertNull(player.playerError)
                player.seekTo(30_000)
            }
            eventually { player.isPlaying && player.currentPosition > 30_100 }
            assertEquals("bytes=$audioPrefixBytes-", requests[1])
            assertTrue(requests.drop(2).all { it.startsWith("bytes=") })
        } finally { withContext(Dispatchers.Main) { player.release() } }
    }

    @Test fun farSeeksOnAudioWithoutRangeSupportReuseOneVerifiedFileAndKeepPause() = runBlocking {
        val audio = wave(590)
        val requests = CopyOnWriteArrayList<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += offset(request)
            response(request, 200, audio).header("ETag", "\"full-fixture\"").build()
        }.build()
        val directory = File(instrumentation.targetContext.cacheDir, "sequential-audio-test")
        val fallback = SequentialAudioFile(directory)
        val guard = MediaRangeGuard(fallback)
        val player = withContext(Dispatchers.Main) {
            player(client, DataSource.Factory { guardedMediaHttp(client, guard) }).apply {
                setMediaItem(MediaItem.fromUri("https://fixture.invalid/whole.wav"))
                prepare()
            }
        }
        try {
            eventually { player.playbackState == Player.STATE_READY && player.isCurrentMediaItemSeekable }
            withContext(Dispatchers.Main) { player.seekTo(535_000) }
            eventually { player.playerError != null || (player.playbackState == Player.STATE_READY && player.currentPosition == 535_000L) }
            withContext(Dispatchers.Main) { assertNull(player.playerError); assertFalse(player.playWhenReady) }
            withContext(Dispatchers.Main) { player.seekTo(560_000) }
            eventually { player.playbackState == Player.STATE_READY && player.currentPosition == 560_000L }
            withContext(Dispatchers.Main) { assertFalse(player.isPlaying); player.play() }
            eventually { player.isPlaying && player.currentPosition > 560_100 }
            assertEquals(2, requests.size)
            assertTrue(requests.last() > 8 * 1024 * 1024)
        } finally {
            withContext(Dispatchers.Main) { player.release() }
            fallback.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun reopensResetHttp2StreamWithoutReplacingTheQueueOrRefreshingTheAddress() = runBlocking {
        val bytes = wave(25)
        val requests = AtomicInteger()
        val refreshes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) throw StreamResetException(ErrorCode.CANCEL)
            media(chain.request(), bytes)
        }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val first = MediaItem.fromUri("https://fixture.invalid/first")
        val second = MediaItem.fromUri("https://fixture.invalid/second")
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                val recovery = AudioRecovery(scope, this) { refreshes.incrementAndGet() }
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        recovery.onError(error, currentMediaItem) { true }
                    }
                })
                setMediaItems(listOf(first, second), 0, 4_000)
                prepare()
                play()
            }
        }
        try {
            eventually { audio.isPlaying && audio.currentPosition > 4_200 }
            withContext(Dispatchers.Main) {
                assertEquals(first, audio.currentMediaItem)
                assertEquals(second, audio.getMediaItemAt(1))
                assertEquals(2, audio.mediaItemCount)
                assertTrue(requests.get() >= 2)
                assertEquals(0, refreshes.get())
            }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun expiresAtNonzeroOffsetAndRestartsAtLatestPositionWithoutStartingPausedAudio() = runBlocking {
        val wave = wave(120)
        val revision = AtomicInteger(1)
        val rejectedOffset = AtomicInteger(-1)
        val renewedOffset = AtomicInteger(-1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val start = offset(request)
            when {
                start > 0 && request.url.encodedPath == "/v1" -> {
                    rejectedOffset.set(start)
                    response(request, 403, "<Error><Code>ExpiredToken</Code></Error>".toByteArray()).build()
                }
                else -> {
                    if (start > 0 && request.url.encodedPath == "/v2") renewedOffset.set(start)
                    media(request, wave)
                }
            }
        }.build()
        val factory = ResolvingDataSource.Factory({ guardedMediaHttp(client) }) { spec ->
            spec.withUri(Uri.parse("https://fixture.invalid/v${revision.get()}"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client, factory).apply {
                val recovery = AudioRecovery(scope, this) { revision.incrementAndGet() }
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        recovery.onError(error, currentMediaItem) { true }
                        seekTo(92_000)
                    }
                })
                setMediaItem(MediaItem.fromUri("https://fixture.invalid/track"))
                prepare()
            }
        }
        try {
            eventually { audio.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { audio.seekTo(84_000) }
            eventually { audio.playbackState == Player.STATE_READY && renewedOffset.get() > 0 }
            withContext(Dispatchers.Main) {
                assertEquals(2, revision.get())
                assertTrue(rejectedOffset.get() > 0)
                assertTrue(renewedOffset.get() > rejectedOffset.get())
                assertEquals(92_000L, audio.currentPosition)
                assertFalse(audio.playWhenReady)
                assertFalse(audio.isPlaying)
            }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun changingVideoQualityReloadsAtCurrentPositionAndNextVideoUsesSamePreference() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video-preload.mp4").use { it.readBytes() }
        val wav = wave(30)
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            media(chain.request(), if (chain.request().url.encodedPath == "/video") mp4 else wav)
        }.build()
        val track = Track("fixture", "quality-one", "Quality fixture", "", durationMs = 30_000)
        val next = track.copy(sourceTrackId = "quality-next")
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource = error("Must forward video quality")
            override suspend fun video(trackId: String, qualityID: String): MediaResource {
                requests.add(qualityID)
                return MediaResource("https://fixture.invalid/video", emptyMap(), videoQuality = qualityID,
                    videoQualities = listOf(com.xtreeroot.mb.music.VideoQualityOption("1080p", "1080p"),
                        com.xtreeroot.mb.music.VideoQualityOption("720p", "720p")))
            }
        }
        var quality = "1080p"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client).apply {
            setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build(), 5_000); prepare()
        } }
        val coordinator = withContext(Dispatchers.Main) { VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { quality }) }
        val texture = SurfaceTexture(false); val output = Surface(texture)
        suspend fun ready() {
            eventually { coordinator.frames.value?.player != null }
            withContext(Dispatchers.Main) { coordinator.frames.value?.player?.setVideoSurface(output) }
            eventually { coordinator.frames.value?.loading == false }
        }
        try {
            eventually { audio.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { coordinator.show(track) }; ready()
            withContext(Dispatchers.Main) { quality = "720p"; coordinator.qualityChanged() }
            ready()
            withContext(Dispatchers.Main) {
                assertEquals("720p", coordinator.frames.value?.quality)
                assertTrue(kotlin.math.abs(audio.currentPosition - 5_000) < 300)
                assertFalse(audio.playWhenReady)
                assertEquals(listOf("1080p", "720p"), requests.toList())
                audio.setMediaItem(next.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build()); audio.prepare()
                coordinator.show(next)
            }
            ready()
            assertEquals(listOf("1080p", "720p", "720p"), requests.toList())
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release(); texture.release()
        }
    }

    @Test fun musicPreloadsVideoWithoutPausingAndSwitchesUsingTheSameBufferedPlayer() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video-preload.mp4").use { it.readBytes() }
        val wav = wave(30)
        val calls = AtomicInteger()
        val offMain = java.util.concurrent.atomic.AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            media(chain.request(), if (chain.request().url.encodedPath == "/video") mp4 else wav)
        }.build()
        val track = Track("fixture", "preload", "Preload fixture", "", durationMs = 30_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource {
                calls.incrementAndGet()
                offMain.set(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return MediaResource("https://fixture.invalid/video", emptyMap())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client).apply {
            setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build()); prepare(); play()
        } }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" })
        }
        val texture = SurfaceTexture(false)
        val output = Surface(texture)
        try {
            eventually { audio.isPlaying }
            withContext(Dispatchers.Main) { coordinator.preload(track) }
            eventually { coordinator.preloaded.value?.loading == false }
            val ticket = withContext(Dispatchers.Main) {
                assertNull(coordinator.frames.value)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
                assertTrue(offMain.get())
                val prepared = checkNotNull(coordinator.preloaded.value?.player)
                val ticket = checkNotNull(coordinator.show(track))
                assertTrue(prepared === coordinator.frames.value?.player)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
                prepared.setVideoSurface(output)
                assertEquals(1, calls.get())
                ticket
            }
            eventually { coordinator.frames.value?.loading == false && audio.currentPosition > 300 }
            withContext(Dispatchers.Main) {
                val video = checkNotNull(coordinator.frames.value?.player)
                assertTrue(kotlin.math.abs(audio.currentPosition - video.currentPosition) < 300)
                ticket.close()
                assertNull(coordinator.frames.value)
                assertTrue(audio.isPlaying)
                assertTrue(video === coordinator.preloaded.value?.player)
                audio.pause()
                coordinator.preload(null)
                assertNull(coordinator.preloaded.value)
                assertNull(coordinator.heldIntent)
                assertFalse(audio.playWhenReady)
            }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release(); texture.release()
        }
    }

    @Test fun portraitDecoderPublishesItsMeasuredAspectBeforeAndAfterOpening() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video-portrait.mp4").use { it.readBytes() }
        val wav = wave(30)
        val calls = AtomicInteger()
        val offMain = java.util.concurrent.atomic.AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            media(chain.request(), if (chain.request().url.encodedPath == "/video") mp4 else wav)
        }.build()
        val track = Track("fixture", "portrait", "Preload fixture", "", durationMs = 30_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource {
                calls.incrementAndGet()
                offMain.set(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return MediaResource("https://fixture.invalid/video", emptyMap())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client).apply {
            setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build()); prepare(); play()
        } }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" })
        }
        val texture = SurfaceTexture(false)
        val output = Surface(texture)
        try {
            eventually { audio.isPlaying }
            withContext(Dispatchers.Main) { coordinator.preload(track) }
            eventually { coordinator.preloaded.value?.loading == false }
            val ticket = withContext(Dispatchers.Main) {
                assertEquals(0.5625f, checkNotNull(coordinator.preloaded.value?.aspectRatio), 0.001f)
                assertNull(coordinator.frames.value)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
                assertTrue(offMain.get())
                val prepared = checkNotNull(coordinator.preloaded.value?.player)
                val ticket = checkNotNull(coordinator.show(track))
                assertTrue(prepared === coordinator.frames.value?.player)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
                prepared.setVideoSurface(output)
                assertEquals(1, calls.get())
                ticket
            }
            eventually { coordinator.frames.value?.loading == false && audio.currentPosition > 300 }
            withContext(Dispatchers.Main) {
                assertEquals(0.5625f, checkNotNull(coordinator.frames.value?.aspectRatio), 0.001f)
                val video = checkNotNull(coordinator.frames.value?.player)
                assertTrue(kotlin.math.abs(audio.currentPosition - video.currentPosition) < 300)
                ticket.close()
                assertNull(coordinator.frames.value)
                assertTrue(audio.isPlaying)
                assertTrue(video === coordinator.preloaded.value?.player)
                audio.pause()
                coordinator.preload(null)
                assertNull(coordinator.preloaded.value)
                assertNull(coordinator.heldIntent)
                assertFalse(audio.playWhenReady)
            }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release(); texture.release()
        }
    }

    @Test fun replacedOrFailedBackgroundVideoNeverStopsAudioOrPublishesAnOldTrack() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video-preload.mp4").use { it.readBytes() }
        val wav = wave(30)
        val oldStarted = CompletableDeferred<Unit>()
        val late = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            media(chain.request(), if (chain.request().url.encodedPath == "/video") mp4 else wav)
        }.build()
        val first = Track("fixture", "old-preload", "Old fixture", "", durationMs = 30_000)
        val next = first.copy(sourceTrackId = "new-preload", title = "Next fixture")
        val source = object : VideoSource {
            override val id = first.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource {
                calls.incrementAndGet()
                if (trackId == first.sourceTrackId) {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { late.await() }
                }
                return MediaResource("https://fixture.invalid/video", emptyMap())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client).apply {
            setMediaItem(first.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build()); prepare(); play()
        } }
        var currentSource: VideoSource = source
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { currentSource }, { "auto" })
        }
        try {
            eventually { audio.isPlaying }
            withContext(Dispatchers.Main) { coordinator.preload(first) }
            withTimeout(5_000) { oldStarted.await() }
            withContext(Dispatchers.Main) {
                assertTrue(audio.isPlaying)
                audio.setMediaItem(next.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build()); audio.prepare()
                coordinator.preload(next)
            }
            eventually { audio.isPlaying && coordinator.preloaded.value?.loading == false }
            late.complete(Unit)
            delay(200)
            withContext(Dispatchers.Main) {
                assertEquals(next.id, coordinator.preloaded.value?.trackId)
                assertNull(coordinator.frames.value)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
                assertEquals(2, calls.get())
                coordinator.preload(null)
                currentSource = object : VideoSource {
                    override val id = first.sourceId
                    override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
                    override suspend fun video(trackId: String): MediaResource { calls.incrementAndGet(); error("Fixture unavailable") }
                }
                coordinator.preload(next)
            }
            eventually { coordinator.preloaded.value?.error != null }
            withContext(Dispatchers.Main) {
                repeat(3) { coordinator.preload(next) }
                assertEquals(3, calls.get())
                assertNull(coordinator.frames.value)
                assertNull(coordinator.heldIntent)
                assertTrue(audio.isPlaying)
            }
        } finally {
            late.complete(Unit)
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun videoUsesBackupBeforeFirstFrameAndHonorsPauseAfterTwoSeeks() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }
        val wave = wave(12)
        val primaryRequests = AtomicInteger()
        val backupRequests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/video" -> {
                    primaryRequests.incrementAndGet()
                    response(request, 403, ByteArray(0)).build()
                }
                "/video-backup" -> {
                    backupRequests.incrementAndGet()
                    assertEquals("https://fixture.invalid/", request.header("Referer"))
                    media(request, mp4)
                }
                else -> media(request, wave)
            }
        }.build()
        val address = CompletableDeferred<MediaResource>()
        val track = Track("fixture", "track", "Streaming fixture", "", durationMs = 12_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String) = address.await()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build())
                prepare()
                play()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" })
        }
        // 使用带 EGL 消费线程的真实输出，避免无消费者的 SurfaceTexture 切换触发模拟器 Codec2 崩溃。
        val output = withContext(Dispatchers.Main) { PlaceholderSurface.newInstance(instrumentation.targetContext, false) }
        try {
            eventually { audio.isPlaying }
            val ticket = withContext(Dispatchers.Main) {
                val session = CaptionSessionPlayer(audio, coordinator) {}
                val show = checkNotNull(coordinator.show(track))
                assertTrue(session.playWhenReady)
                assertFalse(audio.isPlaying)
                session.seekTo(820)
                session.seekTo(310)
                session.pause()
                assertEquals(false, coordinator.heldIntent)
                address.complete(MediaResource("https://fixture.invalid/video", mapOf("Referer" to "https://fixture.invalid/"),
                    "mp4", listOf("https://fixture.invalid/video-backup")))
                show
            }
            eventually { coordinator.frames.value?.player != null }
            withContext(Dispatchers.Main) {
                val video = checkNotNull(coordinator.frames.value?.player)
                assertTrue(coordinator.frames.value!!.loading)
                assertEquals(false, coordinator.heldIntent)
                video.setVideoSurface(output)
            }
            eventually { coordinator.frames.value?.loading == false }
            withContext(Dispatchers.Main) {
                assertNull(coordinator.frames.value?.error)
                val video = checkNotNull(coordinator.frames.value?.player)
                assertNull(coordinator.heldIntent)
                assertEquals(310L, video.currentPosition)
                assertEquals(310L, audio.currentPosition)
                assertFalse(audio.playWhenReady)
                assertFalse(video.playWhenReady)
                assertEquals(1, primaryRequests.get())
                assertTrue(backupRequests.get() > 0)
                ticket.close()
                assertNull(coordinator.frames.value)
                assertFalse(audio.isPlaying)
            }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release()
        }
    }

    @Test fun slowVisibleVideoKeepsTheAudioQueueAndClockBeforeItsFirstFrame() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }
        val wav = wave(2)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            media(chain.request(), if (chain.request().url.encodedPath == "/video") mp4 else wav)
        }.build()
        val address = CompletableDeferred<MediaResource>()
        val track = Track("fixture", "slow-video", "Slow picture", "", durationMs = 2_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String) = address.await()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client).apply {
            setMediaItems(listOf(track, track.copy(sourceTrackId = "next")).map { it.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build() })
            prepare(); play()
        } }
        val coordinator = withContext(Dispatchers.Main) { VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" }) }
        val textures = SurfaceTexture(false); val output = Surface(textures)
        try {
            eventually { audio.isPlaying }
            val start = withContext(Dispatchers.Main) { coordinator.show(track); audio.currentPosition }
            delay(2_400)
            withContext(Dispatchers.Main) {
                assertEquals(track.id, audio.currentMediaItem?.mediaId)
                assertTrue(kotlin.math.abs(audio.currentPosition - start) < 100)
                assertFalse(audio.isPlaying)
                assertEquals(true, coordinator.heldIntent)
                address.complete(MediaResource("https://fixture.invalid/video", emptyMap(), "mp4"))
            }
            eventually { coordinator.frames.value?.player != null }
            withContext(Dispatchers.Main) { coordinator.frames.value!!.player!!.setVideoSurface(output) }
            eventually { coordinator.frames.value?.loading == false && audio.isPlaying }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release(); textures.release()
        }
    }

    @Test fun failedVideoAddressDoesNotStopPlayingAudioOrReopenVideoOnSeek() = runBlocking {
        val wave = wave(20)
        val client = OkHttpClient.Builder().addInterceptor { chain -> media(chain.request(), wave) }.build()
        val releaseAddress = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val track = Track("fixture", "video-failed-address", "Fixture", "", durationMs = 20_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource {
                calls.incrementAndGet()
                releaseAddress.await()
                throw IOException("Fixture address failure")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build())
                prepare()
                play()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" })
        }
        try {
            eventually { audio.isPlaying }
            withContext(Dispatchers.Main) { checkNotNull(coordinator.show(track)) }
            eventually { calls.get() == 1 }
            val heldPosition = withContext(Dispatchers.Main) {
                assertFalse(audio.isPlaying)
                audio.currentPosition.also { releaseAddress.complete(Unit) }
            }
            eventually { coordinator.frames.value?.error != null }
            withContext(Dispatchers.Main) { assertNull(coordinator.heldIntent) }
            eventually { audio.isPlaying && audio.currentPosition > heldPosition + 100 }
            withContext(Dispatchers.Main) { audio.seekTo(4000) }
            eventually { audio.isPlaying && audio.currentPosition > 4100 }
            withContext(Dispatchers.Main) {
                assertEquals(track.id, audio.currentMediaItem?.mediaId)
                assertEquals(1, calls.get())
                assertTrue(coordinator.frames.value?.error != null)
                coordinator.retry()
            }
            eventually { calls.get() == 2 && coordinator.frames.value?.error != null && audio.isPlaying }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun terminalVideoReadFailureKeepsPausedAudioAndAllowsPlayWithoutClosingVideo() = runBlocking {
        val wave = wave(20)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/video") response(request, 404, ByteArray(0)).build() else media(request, wave)
        }.build()
        val track = Track("fixture", "video-read-failure", "Fixture", "", durationMs = 20_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String) = MediaResource("https://fixture.invalid/video", emptyMap(), "mp4")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build(), 1500)
                prepare()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { "auto" })
        }
        try {
            eventually { audio.playbackState == Player.STATE_READY }
            val session = withContext(Dispatchers.Main) {
                CaptionSessionPlayer(audio, coordinator) {}.also { checkNotNull(coordinator.show(track)) }
            }
            eventually { coordinator.frames.value?.error?.contains("HTTP 404") == true }
            withContext(Dispatchers.Main) {
                assertNull(coordinator.heldIntent)
                assertFalse(audio.playWhenReady)
                assertFalse(audio.isPlaying)
                assertEquals(1500L, audio.currentPosition)
                session.play()
            }
            eventually { audio.isPlaying && audio.currentPosition > 1600 }
            assertTrue(coordinator.frames.value?.error?.contains("HTTP 404") == true)
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun stationAutomaticallyContinuesAfterTheTailEnds() = runBlocking {
        val first = Track("radio", "first", "First", "", durationMs = 1000)
        val next = first.copy(sourceTrackId = "second", title = "Second")
        val batch = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        try {
            withContext(Dispatchers.Main) {
                val source = stationProvider { id ->
                    assertEquals("daily", id)
                    if (calls.incrementAndGet() > 1) awaitCancellation()
                    batch.await()
                }
                val radio = StationPlayback(audio, scope, { source }, {})
                audio.setMediaItem(first.mediaItem())
                radio.set(PlaybackStation(first.sourceId, "daily", "Daily"), prefetch = true)
                audio.prepare()
                audio.play()
            }
            eventually { calls.get() == 1 && audio.playbackState == Player.STATE_ENDED }
            batch.complete(listOf(next))
            eventually { audio.currentMediaItem?.mediaId == next.id && audio.isPlaying }
            withContext(Dispatchers.Main) {
                assertEquals(2, audio.mediaItemCount)
                audio.pause()
            }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun stationTailTakesPrecedenceOverRepeatAndShuffleEvenAfterAnEmptyPrefetch() = runBlocking {
        for ((repeat, shuffle) in listOf(Player.REPEAT_MODE_OFF to false, Player.REPEAT_MODE_ALL to false,
            Player.REPEAT_MODE_ONE to false, Player.REPEAT_MODE_OFF to true)) {
            val tail = Track("radio", "first", "Tail", "", durationMs = 1000)
            val previous = tail.copy(sourceTrackId = "previous", title = "Previous")
            val next = tail.copy(sourceTrackId = "next", title = "Next")
            val final = tail.copy(sourceTrackId = "final", title = "Final")
            val batch = CompletableDeferred<List<Track>>()
            val calls = AtomicInteger()
            val client = stationFixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
            var status = StationStatus()
            var video: VideoPlayback? = null
            try {
                val session = withContext(Dispatchers.Main) {
                    val source = stationProvider {
                        when (calls.incrementAndGet()) {
                            in 1..3 -> emptyList()
                            4 -> batch.await()
                            else -> awaitCancellation()
                        }
                    }
                    val radio = StationPlayback(audio, scope, { source }, { status = it })
                    audio.setMediaItems(listOf(previous.mediaItem(), tail.mediaItem()), 1, 0)
                    audio.repeatMode = repeat
                    audio.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(1, 0), 2L))
                    audio.shuffleModeEnabled = shuffle
                    radio.set(PlaybackStation(tail.sourceId, "daily", "Daily"), prefetch = true)
                    audio.prepare()
                    val coordinator = VideoPlayback(instrumentation.targetContext, client, audio, scope, { null }, { "auto" })
                    video = coordinator
                    CaptionSessionPlayer(audio, coordinator, radio) {}
                }
                eventually { status.failure == StationFailure.Empty && audio.playbackState == Player.STATE_READY }
                withContext(Dispatchers.Main) { session.play() }
                try {
                    eventually { calls.get() == 4 && !audio.isPlaying && audio.currentMediaItem?.mediaId == tail.id }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError("Station did not wait at the tail: repeat=$repeat, shuffle=$shuffle", failure)
                }
                val pause = repeat == Player.REPEAT_MODE_ONE
                if (pause) withContext(Dispatchers.Main) { session.pause() }
                batch.complete(listOf(next, final))
                eventually {
                    audio.currentMediaItem?.mediaId == next.id && audio.playbackState == Player.STATE_READY && audio.isPlaying != pause
                }
                withContext(Dispatchers.Main) {
                    assertEquals(4, audio.mediaItemCount)
                    assertEquals(repeat, audio.repeatMode)
                    assertEquals(shuffle, audio.shuffleModeEnabled)
                    if (shuffle) {
                        val timeline = audio.currentTimeline
                        assertEquals(listOf(1, 0, 2, 3), generateSequence(timeline.getFirstWindowIndex(true)) { index ->
                            timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true).takeIf { it >= 0 }
                        }.toList())
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { video?.close(); scope.cancel(); audio.release() }
            }
        }
    }

    @Test fun stationWithAPrefetchedSuccessorDoesNotPauseAtTheOldTail() = runBlocking {
        val current = Track("radio", "long-tail", "Tail", "", durationMs = 12_000)
        val next = current.copy(sourceTrackId = "next", title = "Next")
        val batch = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        try {
            withContext(Dispatchers.Main) {
                val source = stationProvider { if (calls.incrementAndGet() == 1) batch.await() else awaitCancellation() }
                val radio = StationPlayback(audio, scope, { source }, {})
                audio.setMediaItem(current.mediaItem())
                radio.set(PlaybackStation(current.sourceId, "daily", "Daily"), prefetch = true)
                audio.prepare()
                audio.play()
            }
            eventually { audio.isPlaying && audio.currentMediaItem?.mediaId == current.id }
            batch.complete(listOf(next))
            eventually { audio.mediaItemCount == 2 && audio.isPlaying && audio.currentMediaItem?.mediaId == current.id }
            withContext(Dispatchers.Main) { audio.seekTo(11_800) }
            eventually { audio.isPlaying && audio.currentMediaItem?.mediaId == next.id }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun finiteQueueWaitsAtTheTailAndResumesWithTheNextPage() = runBlocking {
        val current = Track("fixture", "long-tail", "Tail", "", durationMs = 12_000)
        val next = current.copy(sourceTrackId = "next", title = "Next")
        val batch = CompletableDeferred<PlaybackPage>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        try {
            withContext(Dispatchers.Main) {
                val pages = PagedPlayback(audio, scope, { true }, {})
                audio.setMediaItems(listOf(current.mediaItem()), 0, 11_800)
                pages.set(PlaybackPages { PlaybackPage(listOf(current), PlaybackPages { calls.incrementAndGet(); batch.await() }) })
                audio.prepare()
                audio.play()
            }
            eventually { calls.get() == 1 && audio.playbackState == Player.STATE_ENDED }
            batch.complete(PlaybackPage(listOf(current, next), null))
            eventually { audio.mediaItemCount == 2 && audio.currentMediaItem?.mediaId == next.id && audio.isPlaying }
            assertEquals(1, calls.get())
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun reachingTheVisibleQueueEndLoadsThePageWhileTheCurrentTrackStaysPaused() = runBlocking {
        val tracks = (1..4).map { Track("fixture", "track-$it", "Track $it", "") }
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        try {
            withContext(Dispatchers.Main) {
                val pages = PagedPlayback(audio, scope, { true }, {})
                audio.setMediaItems(tracks.take(3).map { it.mediaItem() }, 0, 0)
                pages.set(PlaybackPages { calls.incrementAndGet(); PlaybackPage(tracks.takeLast(2), null) })
                assertEquals(0, calls.get())
                pages.loadNextPage()
                pages.loadNextPage()
            }
            eventually { audio.mediaItemCount == 4 }
            withContext(Dispatchers.Main) {
                assertEquals(tracks.first().id, audio.currentMediaItem?.mediaId)
                assertEquals(tracks.last().id, audio.getMediaItemAt(3).mediaId)
                assertEquals(false, audio.playWhenReady)
            }
            assertEquals(1, calls.get())
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun manuallyReplayingTheStationTailKeepsTheSelectionWhenTheBatchArrives() = runBlocking {
        val tail = Track("radio", "long-tail", "Tail", "", durationMs = 12_000)
        val next = tail.copy(sourceTrackId = "next", title = "Next")
        val batch = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        val video = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { null }, { "auto" })
        }
        try {
            val session = withContext(Dispatchers.Main) {
                val provider = stationProvider { if (calls.incrementAndGet() == 1) batch.await() else awaitCancellation() }
                val radio = StationPlayback(audio, scope, { provider }, {})
                val session = CaptionSessionPlayer(audio, video, radio) {}
                audio.setMediaItems(listOf(tail.mediaItem()), 0, 11_800)
                radio.set(PlaybackStation(tail.sourceId, "daily", "Daily"), prefetch = true)
                session.prepare()
                session.play()
                session
            }
            eventually { calls.get() == 1 && audio.playbackState == Player.STATE_ENDED }
            withContext(Dispatchers.Main) {
                session.seekToDefaultPosition(0)
                session.play()
            }
            eventually { audio.isPlaying && audio.currentPosition in 1..2_000L }
            batch.complete(listOf(next))
            eventually { audio.mediaItemCount == 2 }
            withContext(Dispatchers.Main) {
                assertEquals(tail.id, audio.currentMediaItem?.mediaId)
                assertTrue(audio.isPlaying)
            }
        } finally {
            withContext(Dispatchers.Main) { video.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun stationStopsAfterThreeEmptyBatchesAndRejectsALateAppendAfterQueueReplacement() = runBlocking {
        val first = Track("radio", "first", "First", "")
        val other = first.copy(sourceTrackId = "regular", title = "Regular selection")
        val late = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        val source = stationProvider { if (calls.incrementAndGet() <= 3) emptyList() else withContext(NonCancellable) { late.await() } }
        var status = StationStatus()
        val radio = withContext(Dispatchers.Main) {
            StationPlayback(audio, scope, { source }, { status = it }).apply {
                audio.setMediaItem(first.mediaItem())
                set(PlaybackStation(first.sourceId, "daily", "Daily"), prefetch = true)
            }
        }
        val video = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { null }, { "auto" })
        }
        try {
            eventually { status.failure == StationFailure.Empty }
            withContext(Dispatchers.Main) { assertEquals(3, calls.get()); assertTrue(radio.next()) }
            eventually { calls.get() == 4 }
            withContext(Dispatchers.Main) {
                CaptionSessionPlayer(audio, video, radio) {}.setMediaItems(listOf(other.mediaItem()), 0, 0)
                assertNull(status.station)
            }
            late.complete(listOf(first))
            withContext(Dispatchers.Main) {
                delay(80)
                assertEquals(1, audio.mediaItemCount)
                assertEquals(other.id, audio.currentMediaItem?.mediaId)
            }
        } finally {
            late.complete(emptyList())
            withContext(Dispatchers.Main) { video.close(); scope.cancel(); audio.release() }
        }
    }

    private fun stationProvider(batch: suspend (String) -> List<Track>) = object : StationPlaybackSource {
        override val id = "radio"
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun stationNext(id: String) = batch(id)
    }

    private fun stationFixture(): OkHttpClient {
        val initial = wave(1)
        val remaining = wave(12)
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            media(request, if (request.url.encodedPath == "/first") initial else remaining)
        }.build()
    }

    private fun stationMedia(client: OkHttpClient) = ResolvingDataSource.Factory({ guardedMediaHttp(client) }) { spec ->
        spec.withUri(Uri.parse("https://fixture.invalid/${spec.uri.lastPathSegment}"))
    }

    private fun player(client: OkHttpClient, factory: DataSource.Factory = DataSource.Factory { guardedMediaHttp(client) }) =
        ExoPlayer.Builder(instrumentation.targetContext)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(300, 1000, 100, 100).build())
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory).recoverOutsideLoader()).build()

    private suspend fun eventually(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(8_000) { while (!condition()) delay(20) }
    }

    private fun offset(request: Request) = request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0

    private fun response(request: Request, status: Int, bytes: ByteArray) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(bytes.toResponseBody())

    private fun media(request: Request, bytes: ByteArray): Response {
        val range = request.header("Range") ?: return response(request, 200, bytes).header("ETag", "\"fixture\"").build()
        val start = offset(request)
        if (start >= bytes.size) return response(request, 416, byteArrayOf()).header("Content-Range", "bytes */${bytes.size}").build()
        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
        return response(request, 206, bytes.copyOfRange(start, end + 1)).header("ETag", "\"fixture\"")
            .header("Content-Range", "bytes $start-$end/${bytes.size}").build()
    }
}

internal fun wave(seconds: Int): ByteArray {
    val rate = 8_000
    val length = seconds * rate * 2
    return ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(length + 36).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(length).array()
}
