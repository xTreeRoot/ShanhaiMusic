package com.xtreeroot.mb.plugin

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.AudioQuality
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 合成音频验证原生播放；真实账号仅在显式启用时使用本机现有插件会话。 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class CencAudioDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MBApplication
    private val key = "00112233445566778899aabbccddeeff"
    private fun fixture() = instrument.context.assets.open("cenc-aac.m4a").use { it.readBytes() }
    private fun manifest() = PluginManifest.parse(JSONObject("""{"id":"cenc-fixture","permissions":{"network":["audio.example.test","*.edge.example.test"]}}"""))

    @Test fun nativeQuickJsResolvesAuthorizedContainerAndAndroidPlaysIt() = runBlocking {
        val root = File(app.cacheDir, "cenc-test-${UUID.randomUUID()}").apply { mkdirs() }
        val code = File(root, "code").apply { mkdirs() }
        File(code, "index.js").writeText("""
            export function stream() {
              if (!host.capabilities.cencAudio) throw new Error('Missing CENC capability');
              return {url:'https://audio.example.test/audio',type:'m4a',headers:{'X-Fixture':'test'},encryption:{scheme:'cenc',key:'$key'}};
            }
        """.trimIndent())
        val data = File(root, "data").apply { mkdirs() }
        val account = File(data, "storage.json").apply { writeText("""{"private-fixture":"preserved"}""") }
        var requests = 0
        val runtime = PluginRuntime(app.assets, code, data, manifest(), audioCacheDirectory = File(root, "cache"), transport = PluginTransport { request, _ ->
            requests++
            assertEquals("test", request.headers["X-Fixture"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(), fixture())
        })
        try {
            val source = PluginSource(manifest(), runtime)
            val resource = source.audio("fixture", AudioQuality.Lossless)
            assertEquals("file", java.net.URI(resource.url).scheme)
            assertTrue(resource.headers.isEmpty())
            assertEquals(1, requests)
            assertEquals(resource, source.audio("fixture", AudioQuality.Lossless))
            assertEquals(1, requests)
            assertEquals("""{"private-fixture":"preserved"}""", account.readText())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(java.net.URI(resource.url)).path)
                assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                assertTrue(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) >= 150_000)
            } finally { extractor.release() }
            assertTrue(play(resource.url, waitForProgress = false).duration >= 150)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun everyRedirectChecksPermissionsAndDenialDoesNotExposeAuthorization() = runBlocking {
        val root = File(app.cacheDir, "cenc-test-${UUID.randomUUID()}").apply { mkdirs() }
        val contacted = mutableListOf<String>()
        val runtime = PluginRuntime(app.assets, root, File(root, "data"), manifest(), audioCacheDirectory = File(root, "cache"), transport = PluginTransport { request, _ ->
            contacted += request.url.host
            if (request.url.host == "audio.example.test") {
                val host = if (request.url.path == "/denied") "outside.example.test" else "one.edge.example.test"
                PluginFetchResponse(302, request.url, mapOf("location" to "https://$host/audio?token=private-fixture"), emptyList(), byteArrayOf())
            } else {
                assertEquals("one.edge.example.test", request.url.host)
                PluginFetchResponse(200, request.url, emptyMap(), emptyList(), fixture())
            }
        })
        val encryption = JSONObject().put("scheme", "cenc").put("key", key)
        try {
            try {
                runtime.prepareCencAudio(uri("https://audio.example.test/denied"), emptyMap(), encryption)
                fail("Undeclared host must be rejected")
            } catch (error: PluginException) {
                assertTrue(error.message.orEmpty().contains("outside.example.test"))
                assertFalse(error.message.orEmpty().contains("private-fixture"))
            }
            assertEquals(listOf("audio.example.test"), contacted)
            assertTrue(runtime.prepareCencAudio(uri("https://audio.example.test/allowed"), emptyMap(), encryption).isFile)
            assertEquals(listOf("audio.example.test", "audio.example.test", "one.edge.example.test"), contacted)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun existingDeviceMemberAccountCanPlayAuthorizedAudio() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mbCencMemberVerification") == "true")
        app.refreshPlugins()
        val source = checkNotNull(app.playbackSource(checkNotNull(args.getString("sourceId")))).also {
            assertNotNull((it as com.xtreeroot.mb.music.AccountSource).account())
        }
        val resource = source.audio(checkNotNull(args.getString("trackId")), AudioQuality.Lossless)
        assertEquals("file", java.net.URI(resource.url).scheme)
        val result = play(resource.url, waitForProgress = true)
        assertTrue(result.duration > 200_000)
        assertTrue(result.position > 2_000)
        val destination = File(app.cacheDir, "cenc-member-verification.json")
        destination.writeText(JSONObject().put("durationMilliseconds", result.duration).put("positionMilliseconds", result.position).put("playing", true).toString())
    }

    private data class PlaybackResult(val duration: Long, val position: Long)
    private suspend fun play(address: String, waitForProgress: Boolean): PlaybackResult {
        val ready = CompletableDeferred<Long>()
        lateinit var player: ExoPlayer
        instrument.runOnMainSync {
            player = ExoPlayer.Builder(app).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.complete(duration)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { ready.completeExceptionally(error) }
                })
                setMediaItem(MediaItem.fromUri(address)); prepare(); play()
            }
        }
        try {
            val duration = withTimeout(15_000) { ready.await() }
            if (waitForProgress) delay(3_000)
            var position = 0L
            instrument.runOnMainSync { position = player.currentPosition }
            return PlaybackResult(duration, position)
        } finally { instrument.runOnMainSync { player.release() } }
    }
}
