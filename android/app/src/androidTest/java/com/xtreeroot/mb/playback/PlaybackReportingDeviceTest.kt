package com.xtreeroot.mb.playback

import android.Manifest
import android.app.SearchManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadata as PlatformMediaMetadata
import android.media.browse.MediaBrowser as PlatformMediaBrowser
import android.media.session.MediaController as PlatformMediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.service.media.MediaBrowserService as PlatformMediaBrowserService
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.local.LocalPlaylistStore
import com.xtreeroot.mb.local.PortableAsset
import com.xtreeroot.mb.local.PortableImport
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.preferences.AppAppearance
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PlaybackReportingDeviceTest {
    @Test fun neighboringPreviewAndSourceSwitchKeepQueuePositionAndRespectPauseOptIn() = runBlocking {
        withFixture { player ->
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.connected }
                withContext(Dispatchers.Main) { model.play(listOf(first, second), first) }
                eventually { player.isPlaying && model.playback.value.current?.id == first.id }
                withContext(Dispatchers.Main) {
                    repeat(3) { assertEquals(second.id, model.neighboringTrack(true)?.id) }
                    assertEquals(first.id, model.playback.value.current?.id)
                    model.didSwitchSource("another", false)
                    assertTrue(player.playWhenReady)
                    model.didSwitchSource(first.sourceId, true)
                    assertTrue(player.playWhenReady)
                    model.didSwitchSource("another", true)
                    assertEquals(2, player.mediaItemCount)
                    assertEquals(first.id, player.currentMediaItem?.mediaId)
                }
                eventually { !player.playWhenReady }
                withContext(Dispatchers.Main) {
                    assertEquals(2, player.mediaItemCount)
                    assertEquals(first.id, player.currentMediaItem?.mediaId)
                    player.shuffleModeEnabled = true
                    assertEquals(player.getMediaItemAt(player.manualNeighbor(true)).mediaId, model.neighboringTrack(true)?.id)
                }
            } finally { withContext(Dispatchers.Main) { store.clear() } }
        }
    }

    @Test fun signInRequiredIsReportedWithoutReissuingTheAudioRequest() = runBlocking {
        withFixture { player ->
            val restricted = first.copy(sourceTrackId = "restricted", title = "Restricted")
            suspend fun attempts() = source.search("streamAttempts", 1).tracks.single().title.toInt()
            assertEquals(0, attempts())
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(restricted.mediaItem(), second.mediaItem()), 0, 12_000)
                player.prepare()
                player.play()
            }
            eventually { player.playerError != null && app.trackPlaybackIssues.values.value[restricted.playbackKey]?.issue == PlaybackIssue.SignIn }
            assertEquals("An explicit login requirement is not a transient network failure", 1, attempts())
            withContext(Dispatchers.Main) {
                assertEquals(12_000L, player.currentPosition)
                player.prepare()
                player.play()
            }
            withTimeout(8_000) { while (attempts() < 2) delay(20) }
            eventually { player.playerError != null }
            assertEquals(2, attempts())
            withContext(Dispatchers.Main) {
                player.seekToDefaultPosition(1)
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id && player.currentPosition > 100 }
        }
    }

    @Test fun unavailableSourceDoesNotPreventAnotherSongFromPlaying() = runBlocking {
        withFixture { player ->
            val removed = first.copy(sourceId = "removed-fixture", title = "Removed")
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.connected }
                withContext(Dispatchers.Main) { model.play(listOf(removed, first, second), second) }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(first.id, second.id), model.playback.value.tracks.map(Track::id))
                    model.play(listOf(removed, first, second), removed)
                    assertEquals(second.id, model.playback.value.current?.id)
                    assertEquals(PlaybackIssue.SourceUnavailable,
                        app.trackPlaybackIssues.values.value[removed.playbackKey]?.issue)
                    model.playGroup(listOf(removed, first, second), shuffle = false)
                }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id }
            } finally {
                app.trackPlaybackIssues.playing(removed)
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MBApplication

    @Test fun tappingALibrarySongReconnectsAfterAServiceFailure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val service = ComponentName(app, PlaybackService::class.java)
        val original = app.packageManager.getComponentEnabledSetting(service)
        val wav = app.local.catalog.temporaryFile()
        val audio = wave(16).also { UUID.randomUUID().toString().encodeToByteArray().copyInto(it, 128) }
        val id = MessageDigest.getInstance("SHA-256").digest(audio).toHexString()
        wav.writeBytes(audio)
        val asset = PortableAsset("mb-import-$id.audio", audio.size.toLong(), id)
        val track = try {
            app.local.restoreImport(wav, PortableImport(id, "000 MB Reconnect QA ${id.take(8)}", asset, "", false, 0)).track
        } finally { wav.delete() }
        var activity: MainActivity? = null
        var playerFuture: ListenableFuture<MediaController>? = null
        fun findInTree(node: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
            if (node.text?.toString() in labels || node.contentDescription?.toString() in labels) return node
            return (0 until node.childCount).firstNotNullOfOrNull { index ->
                node.getChild(index)?.let { findInTree(it, labels) }
            }
        }
        suspend fun findNode(vararg labels: String): AccessibilityNodeInfo = withTimeoutOrNull(10_000) {
            val targets = labels.toSet()
            var node: AccessibilityNodeInfo?
            do {
                node = automation.rootInActiveWindow?.let { findInTree(it, targets) }
                if (node == null) delay(50)
            } while (node == null)
            checkNotNull(node)
        } ?: error("Missing UI element: ${labels.joinToString()}")
        fun tap(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(automation.injectInputEvent(event, true))
                } finally { event.recycle() }
            }
        }
        try {
            val serviceIntent = Intent(app, PlaybackService::class.java)
            withContext(Dispatchers.Main) { app.stopService(serviceIntent) }
            eventually { app.videoPlayback.value == null }
            check(!playbackStorage.contains("queue")) { "Run on a dedicated installation with an empty queue" }
            app.packageManager.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            findNode("无法连接播放服务", "Can't connect to playback service")
            tap(findNode("资料库", "Library"))
            findNode("本地资料库", "Local Library")
            val song = findNode(track.title)
            app.packageManager.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
            val discovery = Intent(MediaSessionService.SERVICE_INTERFACE).setPackage(app.packageName)
            eventually { app.packageManager.queryIntentServices(discovery, 0).any { it.serviceInfo.name == service.className } }
            tap(song)
            val attempt = withContext(Dispatchers.Main) { MediaController.Builder(app, SessionToken(app, service)).buildAsync() }
            playerFuture = attempt
            val player = withContext(Dispatchers.IO) { attempt.get(10, TimeUnit.SECONDS) }
            try { eventually { player.isPlaying && player.currentMediaItem?.mediaId == track.id && player.currentPosition > 0 } }
            catch (timeout: TimeoutCancellationException) {
                val actual = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId }
                throw AssertionError("Tapping the library song did not start playback after the service recovered: expected ${track.id}, got $actual", timeout)
            }
        } finally {
            app.packageManager.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
            withContext(Dispatchers.Main) {
                playerFuture?.let { attempt ->
                    if (attempt.isDone) runCatching { attempt.get() }.getOrNull()?.let { player ->
                        if (player.currentMediaItem?.mediaId == track.id) { player.pause(); player.clearMediaItems() }
                    }
                    MediaController.releaseFuture(attempt)
                }
                activity?.finish()
            }
            app.local.delete(track.sourceTrackId)
        }
    }

    @Test fun selectingASongReconnectsAfterPlaybackServiceBecomesAvailable() = runBlocking {
        val service = ComponentName(app, PlaybackService::class.java)
        val packages = app.packageManager
        val original = packages.getComponentEnabledSetting(service)
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            eventually { model.playback.value.connectionIssue == PlaybackConnectionIssue.Unavailable && !model.playback.value.connected }
            withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
            }
            withFixture { player ->
                withContext(Dispatchers.Main) { model.play(listOf(first), first) }
                eventually { model.playback.value.connected && model.playback.value.connectionIssue == null }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id && player.currentPosition > 0 }
            }
        } finally {
            withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
                store.clear()
            }
        }
    }

    @Test fun connectingPlaysOnlyTheLatestSelectionAndDiscardsAClearedSelection() = runBlocking {
        withFixture { player ->
            val cancelledStore = ViewModelStore()
            val playbackStore = ViewModelStore()
            try {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                val cancelled = withContext(Dispatchers.Main) {
                    ViewModelProvider(cancelledStore, factory)[PlaybackModel::class.java].also { model ->
                        assertTrue(!model.playback.value.connected)
                        model.play(listOf(first), first)
                        model.clear()
                    }
                }
                eventually { cancelled.playback.value.connected }
                assertTrue(cancelled.playback.value.tracks.isEmpty())
                val model = withContext(Dispatchers.Main) {
                    ViewModelProvider(playbackStore, factory)[PlaybackModel::class.java].also { connecting ->
                        assertTrue(!connecting.playback.value.connected)
                        connecting.play(listOf(first, second), first)
                        connecting.play(listOf(first, second), second)
                    }
                }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id && player.currentPosition > 0 }
                assertEquals(second.id, model.playback.value.current?.id)
                suspend fun reports() = source.search("reports", 1).tracks.map(Track::title)
                withTimeout(6_000) { while (reports().isEmpty()) delay(20) }
                assertEquals(listOf("second"), reports())
            } finally {
                withContext(Dispatchers.Main) { cancelledStore.clear(); playbackStore.clear() }
            }
        }
    }

    @Test fun scrubbingPositionsAPausedQueueBeforePreparationAndRejectsAnOldSelection() = runBlocking {
        withFixture { player ->
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                player.pause()
                player.stop()
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.seekable && model.playback.value.current?.id == first.id }
                val before = model.playback.value
                withContext(Dispatchers.Main) { model.seekCurrent(before, 20_000) }
                eventually { player.currentPosition == 20_000L }
                withContext(Dispatchers.Main) {
                    assertEquals(Player.STATE_IDLE, player.playbackState)
                    assertTrue(!player.playWhenReady)
                    player.seekTo(1, 5_000)
                }
                eventually { model.playback.value.current?.id == second.id && player.currentPosition == 5_000L }
                withContext(Dispatchers.Main) {
                    model.seekCurrent(before, 40_000)
                    assertEquals(5_000L, player.currentPosition)
                    assertEquals(5_000L, model.playback.value.positionMs)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY && player.currentPosition == 5_000L }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    @Test fun reselectingTheSameSongRejectsAnOldScrubButAcceptsANewOne() = runBlocking {
        withFixture { player ->
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 35_000)
                player.prepare()
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.seekable && model.playback.value.positionMs == 35_000L }
                val before = model.playback.value
                withContext(Dispatchers.Main) { model.select(0) }
                eventually { player.isPlaying && player.currentPosition in 0..2_000 }
                withContext(Dispatchers.Main) { player.pause() }
                eventually { model.playback.value.seekable && !model.playback.value.playWhenReady }
                val parked = withContext(Dispatchers.Main) { player.currentPosition }
                withContext(Dispatchers.Main) { model.seekCurrent(before, 55_000) }
                delay(400)
                withContext(Dispatchers.Main) {
                    assertEquals(parked, player.currentPosition)
                    model.seekCurrent(model.playback.value, 55_000)
                }
                eventually { player.playbackState == Player.STATE_READY && player.currentPosition == 55_000L && !player.playWhenReady }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    @Test fun playerShowsBufferingAndStillLetsTheUserPauseBeforeAudioArrives() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun screenshot(stage: String) {
                if (InstrumentationRegistry.getArguments().getString("playbackBufferScreenshot") != "true") return
                val image = checkNotNull(automation.takeScreenshot())
                try {
                    File(app.cacheDir, "android-player-$stage-buffering.png").outputStream().use {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }
            try {
                val waiting = first.copy(sourceTrackId = "pending", title = "Waiting audio")
                withContext(Dispatchers.Main) {
                    player.setMediaItems(listOf(waiting.mediaItem(), first.mediaItem()), 0, 0)
                    player.prepare()
                    player.play()
                }
                eventually { player.playbackState == Player.STATE_BUFFERING && player.playWhenReady }
                val bufferingLabels = arrayOf("正在缓冲，暂停", "Buffering, Pause")
                automation.queueNode(*bufferingLabels)
                screenshot("mini")
                automation.press(automation.queueNode("Waiting audio"))
                automation.queueNode("关闭播放器", "Close Player")
                val control = automation.queueNode(*bufferingLabels)
                screenshot("full")
                automation.press(control)
                eventually { !player.playWhenReady }
                automation.queueNode("播放", "Play")
                withContext(Dispatchers.Main) { player.seekTo(1, 0); player.play() }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id && player.currentPosition > 100 }
                automation.queueNode("暂停", "Pause")
            } catch (failure: Exception) {
                screenshot("failure")
                throw failure
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun mediaTitleUsesCurrentLyricWithoutChangingQueueMetadata() = runBlocking {
        val original = app.settings.values.value.nowPlayingLyrics
        try {
            app.settings.update { it.copy(nowPlayingLyrics = true) }
            withFixture { player ->
                suspend fun expect(title: String, artist: String) = eventually {
                    player.mediaMetadata.title?.toString() == title && player.mediaMetadata.artist?.toString() == artist
                }
                withContext(Dispatchers.Main) {
                    player.pause()
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY }
                expect(first.title, first.title)
                withContext(Dispatchers.Main) { player.seekTo(2_000) }
                expect("First line", first.title)
                withContext(Dispatchers.Main) { player.seekTo(4_000) }
                expect("Next line", first.title)
                withContext(Dispatchers.Main) {
                    assertEquals(first, player.currentMediaItem?.track())
                    assertEquals(second, player.getMediaItemAt(1).track())
                    player.seekToNextMediaItem()
                }
                expect(second.title, second.title)
                app.settings.update { it.copy(nowPlayingLyrics = false) }
                expect(second.title, second.artist)
                withContext(Dispatchers.Main) { assertEquals(second, player.currentMediaItem?.track()) }
            }
        } finally {
            app.settings.update { it.copy(nowPlayingLyrics = original) }
        }
    }

    @Test fun replacingLocalLyricsUpdatesPausedPlayerAndSystemMetadataWithoutReplacingQueue() = runBlocking {
        val original = app.settings.values.value.nowPlayingLyrics
        val wav = app.local.catalog.temporaryFile()
        val lrc = File.createTempFile("mb-local-lyrics-", ".lrc", app.cacheDir)
        val audio = wave(16).also { UUID.randomUUID().toString().encodeToByteArray().copyInto(it, 128) }
        val id = MessageDigest.getInstance("SHA-256").digest(audio).toHexString()
        try {
            wav.writeBytes(audio)
            val asset = PortableAsset("mb-import-$id.audio", audio.size.toLong(), id)
            val track = app.local.restoreImport(wav,
                PortableImport(id, "Local Lyric Fixture", asset, "", false, 0)).track
            app.settings.update { it.copy(nowPlayingLyrics = true) }
            withFixture { player ->
                val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) {
                    ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
                }
                try {
                    withContext(Dispatchers.Main) {
                        player.pause()
                        player.setMediaItems(listOf(track.mediaItem(), first.mediaItem()), 0, 6_500)
                        player.prepare()
                    }
                    eventually { model.playback.value.current?.id == track.id && player.playbackState == Player.STATE_READY }
                    withContext(Dispatchers.Main) { model.loadCaptions(track) }
                    eventually { !model.captions.value.loading && model.captions.value.value.isEmpty() &&
                        player.mediaMetadata.title?.toString() == track.title }
                    suspend fun replace(text: String) {
                        lrc.writeText("[00:05.00]$text\n[00:10.00]Later line")
                        app.local.attachLyrics(id, Uri.fromFile(lrc))
                        assertEquals(text, app.local.captions(id).first().text)
                        eventually { model.captions.value.value.firstOrNull()?.text == text &&
                            player.mediaMetadata.title?.toString() == text }
                        withContext(Dispatchers.Main) {
                            assertEquals(track.title, player.mediaMetadata.artist?.toString())
                            assertEquals(track, player.getMediaItemAt(0).track())
                            assertEquals(first, player.getMediaItemAt(1).track())
                            assertEquals(6_500L, player.currentPosition)
                            assertTrue(!player.playWhenReady && !player.isPlaying)
                        }
                    }
                    replace("Attached line")
                    replace("Replacement line")
                    withContext(Dispatchers.Main) { player.seekTo(0) }
                    eventually { player.mediaMetadata.title?.toString() == track.title }
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                }
            }
        } finally {
            app.settings.update { it.copy(nowPlayingLyrics = original) }
            app.local.delete(id)
            wav.delete()
            lrc.delete()
        }
    }

    @Test fun audioFocusDenialCanBeRetriedWithoutLosingTheQueueOrPosition() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .bufferedReader().use { it.readText().trim() }
            val userId = shell("am get-current-user").toInt()
            check(userId == app.applicationInfo.uid / 100_000) { "Run on the foreground test user" }
            val target = "--user $userId ${app.packageName} TAKE_AUDIO_FOCUS"
            fun focusMode() = shell("cmd appops get $target").lineSequence()
                .firstOrNull { it.startsWith("TAKE_AUDIO_FOCUS:") }?.substringAfter(':')?.substringBefore(';')?.trim()
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            val denied = CompletableDeferred<Unit>()
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) denied.complete(Unit)
                }
            }
            var original: String? = null
            try {
                eventually { model.playback.value.connected && activity.hasWindowFocus() }
                withContext(Dispatchers.Main) {
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 1, 14_000)
                    player.prepare()
                    player.play()
                }
                eventually { player.isPlaying }
                original = checkNotNull(focusMode()) { "The baseline audio focus request did not record an AppOp mode" }
                withContext(Dispatchers.Main) {
                    player.pause()
                    player.stop()
                    player.seekTo(14_000)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY }
                shell("cmd appops set $target ignore")
                assertEquals("ignore", focusMode())
                withContext(Dispatchers.Main) {
                    player.addListener(listener)
                    player.play()
                }
                withTimeout(8_000) { denied.await() }
                try { eventually { model.playback.value.audioFocusBlocked } }
                catch (failure: TimeoutCancellationException) { throw AssertionError("Audio focus denial was not exposed to the player", failure) }
                assertNull(withContext(Dispatchers.Main) { player.playerError })
                assertEquals(14_000L, model.playback.value.positionMs)
                shell("cmd appops set $target $original")
                assertEquals(original, focusMode())
                withContext(Dispatchers.Main) { model.retry() }
                eventually { player.isPlaying && player.currentPosition > 14_000 && !model.playback.value.audioFocusBlocked }
                assertEquals(listOf(first.id, second.id), model.playback.value.tracks.map(Track::id))
                assertEquals(second.id, model.playback.value.current?.id)
            } finally {
                original?.let { shell("cmd appops set $target $it") }
                withContext(Dispatchers.Main) {
                    player.removeListener(listener)
                    store.clear()
                    activity.finish()
                }
            }
        }
    }

    @Test fun carMediaBrowserResumesTheExistingQueueWithoutReplacingIt() = runBlocking {
        withFixture { player ->
            val service = ComponentName(app, PlaybackService::class.java)
            val discovery = Intent(PlatformMediaBrowserService.SERVICE_INTERFACE).setPackage(app.packageName)
            assertTrue("Expose a media browser for car hosts", app.packageManager.queryIntentServices(discovery, 0)
                .any { it.serviceInfo.name == service.className && it.serviceInfo.exported })
            val connected = CompletableDeferred<Unit>()
            val browser = withContext(Dispatchers.Main) {
                PlatformMediaBrowser(app, service, object : PlatformMediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.complete(Unit) }
                    override fun onConnectionFailed() { connected.completeExceptionally(AssertionError("Media browser rejected")) }
                }, null).also(PlatformMediaBrowser::connect)
            }
            try {
                withTimeout(8_000) { connected.await() }
                suspend fun children(): List<PlatformMediaBrowser.MediaItem> {
                    val result = CompletableDeferred<List<PlatformMediaBrowser.MediaItem>>()
                    val callback = object : PlatformMediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: List<PlatformMediaBrowser.MediaItem>) { result.complete(children) }
                        override fun onError(parentId: String) { result.completeExceptionally(AssertionError("Media browser could not load its root")) }
                    }
                    withContext(Dispatchers.Main) { browser.subscribe(browser.root, callback) }
                    try { return withTimeout(8_000) { result.await() } }
                    finally { withContext(Dispatchers.Main) { browser.unsubscribe(browser.root, callback) } }
                }
                assertTrue(children().isEmpty())
                withContext(Dispatchers.Main) { player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 1, 12_000) }
                eventually { player.currentMediaItem?.mediaId == second.id }
                val resume = children().single()
                assertTrue(resume.isPlayable)
                assertEquals(second.title, resume.description.title.toString())
                assertNull(resume.description.mediaUri)
                assertNull(resume.description.iconUri)
                assertTrue(resume.description.extras?.keySet().orEmpty().none { it.startsWith("mb.") })
                withContext(Dispatchers.Main) {
                    PlatformMediaController(app, browser.sessionToken).transportControls.prepareFromMediaId(resume.mediaId!!, null)
                }
                eventually { player.playbackState == Player.STATE_READY }
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(first.id, second.id), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                    assertEquals(second.id, player.currentMediaItem?.mediaId)
                    assertEquals(12_000L, player.currentPosition)
                    assertTrue(!player.playWhenReady)
                }
                withContext(Dispatchers.Main) {
                    PlatformMediaController(app, browser.sessionToken).transportControls.prepareFromSearch("first fixture", null)
                }
                eventually { player.currentMediaItem?.mediaId == first.id && player.playbackState == Player.STATE_READY }
                withContext(Dispatchers.Main) {
                    assertEquals(2, player.mediaItemCount)
                    assertEquals(0L, player.currentPosition)
                    assertTrue(!player.playWhenReady)
                }
                val voiceIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SearchManager.QUERY, second.title)
                val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(voiceIntent)
                try {
                    eventually { player.currentMediaItem?.mediaId == second.id }
                    withContext(Dispatchers.Main) { assertEquals(2, player.mediaItemCount) }
                } finally {
                    withContext(Dispatchers.Main) { activity.finish() }
                }
            } finally {
                withContext(Dispatchers.Main) { browser.disconnect() }
            }
        }
    }

    @Test fun systemMediaArtworkUsesSourceHeadersAndCancelsOnTrackChange() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = app.imageLoader
        val slowCall = AtomicReference<Call>()
        val releaseSlow = CountDownLatch(1)
        val artwork = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.GREEN)
                ByteArrayOutputStream().apply { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        val fixtureClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val permitted = request.url.host == "artwork.fixture.test" && request.header("Authorization") == "Fixture artwork-only"
            if (permitted && request.url.encodedPath == "/slow.png") {
                slowCall.set(chain.call())
                releaseSlow.await(10, TimeUnit.SECONDS)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (permitted) 200 else 403).message(if (permitted) "OK" else "Forbidden")
                .header("Content-Type", "image/png")
                .body((if (permitted) artwork else byteArrayOf()).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val fixtureLoader = ImageLoader.Builder(app).okHttpClient(fixtureClient).build()
        Coil.setImageLoader(fixtureLoader)
        try {
            withFixture { player ->
                val covered = first.copy(artworkUrl = "https://artwork.fixture.test/first.png")
                val slow = second.copy(artworkUrl = "https://artwork.fixture.test/slow.png")
                val uncovered = first.copy(title = "No cover")
                withContext(Dispatchers.Main) {
                    player.setMediaItems(listOf(covered, slow, uncovered).map(Track::mediaItem), 0, 0)
                    player.prepare()
                }
                val automation = instrumentation.uiAutomation
                val manager = app.getSystemService(MediaSessionManager::class.java)
                var platform: PlatformMediaController? = null
                try {
                    automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
                    eventually { platform = manager.getActiveSessions(null).firstOrNull { it.packageName == app.packageName }; platform != null }
                } finally { automation.dropShellPermissionIdentity() }
                val system = checkNotNull(platform)
                fun bitmap() = system.metadata?.getBitmap(PlatformMediaMetadata.METADATA_KEY_ALBUM_ART)
                try { eventually { bitmap()?.let { it.getPixel(0, 0) == Color.GREEN && maxOf(it.width, it.height) <= 384 } == true } }
                catch (failure: TimeoutCancellationException) { throw AssertionError("Android media session did not publish the authorized cover", failure) }
                withContext(Dispatchers.Main) {
                    assertEquals(covered, player.getMediaItemAt(0).track())
                    assertNull(player.getMediaItemAt(0).mediaMetadata.artworkData)
                    assertTrue(playbackStorage.getString("queue", null)?.contains("artwork-only") == false)
                    player.seekTo(1, 0)
                }
                try { eventually { slowCall.get() != null } }
                catch (failure: TimeoutCancellationException) { throw AssertionError("The next track did not request its cover", failure) }
                withContext(Dispatchers.Main) { player.seekTo(2, 0) }
                try {
                    eventually {
                        slowCall.get()?.isCanceled() == true && bitmap() == null &&
                            system.metadata?.getString(PlatformMediaMetadata.METADATA_KEY_TITLE) == uncovered.title &&
                            player.playbackState == Player.STATE_READY && !player.playWhenReady
                    }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError(withContext(Dispatchers.Main) {
                        "Track change: request cancelled=${slowCall.get()?.isCanceled()}, cover cleared=${bitmap() == null}, " +
                            "title=${system.metadata?.getString(PlatformMediaMetadata.METADATA_KEY_TITLE)}, " +
                            "intent=${player.playWhenReady}, state=${player.playbackState}, suppression=${player.playbackSuppressionReason}"
                    }, failure)
                }
            }
        } finally {
            releaseSlow.countDown()
            Coil.setImageLoader(original)
            fixtureLoader.shutdown()
        }
    }

    @Test fun reportsOnlyWhenASelectionStartsPlayingThroughTheMediaSession() = runBlocking {
        withFixture { player ->
            suspend fun reports() = source.search("reports", 1).tracks.map(Track::title)
            suspend fun expect(vararg ids: String) {
                withTimeout(6_000) { while (reports().size < ids.size) delay(20) }
                assertEquals(ids.toList(), reports())
            }
            val queue = listOf(first.mediaItem(), second.mediaItem())
            withContext(Dispatchers.Main) {
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
                player.setMediaItems(queue, 0, 0)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY && player.currentMediaItem?.mediaId == first.id }
            expect()

            withContext(Dispatchers.Main) { player.play() }
            expect("first")
            withContext(Dispatchers.Main) { player.pause() }
            eventually { !player.isPlaying }
            val reopened = connect().also { active = it }
            withContext(Dispatchers.Main) {
                MediaController.releaseFuture(connections.removeAt(0))
                reopened.seekTo(65_000)
                reopened.play()
            }
            eventually { reopened.isPlaying && reopened.currentPosition >= 65_000 }
            expect("first")

            withContext(Dispatchers.Main) { reopened.stop() }
            eventually { reopened.playbackState == Player.STATE_IDLE }
            withContext(Dispatchers.Main) { reopened.prepare(); reopened.play() }
            eventually { reopened.isPlaying }
            expect("first")

            withContext(Dispatchers.Main) { reopened.seekToNextMediaItem() }
            expect("first", "second")
            withContext(Dispatchers.Main) { reopened.seekToPreviousMediaItem() }
            expect("first", "second", "first")

            withContext(Dispatchers.Main) {
                reopened.setMediaItems(queue, 0, 0)
                reopened.prepare()
                reopened.play()
            }
            expect("first", "second", "first", "first")

            withContext(Dispatchers.Main) {
                reopened.repeatMode = Player.REPEAT_MODE_ONE
                reopened.seekTo(89_900)
            }
            expect("first", "second", "first", "first", "first")
        }
    }

    @Test fun recordsRecentOnlyAfterDownloadedAudioPlaysAndSkipsCacheOnlyAudio() = runBlocking {
        withFixture(cacheSecond = true) { player ->
            suspend fun recent() = app.local.catalog.activity().filter { it.trackId in setOf(first.id, second.id) }
            withContext(Dispatchers.Main) {
                player.pause()
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY }
            assertTrue(recent().isEmpty())
            withContext(Dispatchers.Main) { player.play() }
            val firstPlay = withTimeout(6_000) {
                while (recent().isEmpty()) delay(20)
                recent().single().also { assertEquals(first.id, it.trackId) }.playedAt
            }
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) { player.seekToPreviousMediaItem() }
            withTimeout(6_000) {
                while (recent().none { it.trackId == first.id && it.playedAt > firstPlay }) delay(20)
            }
            assertEquals(listOf(first.id), recent().map { it.trackId })
            app.plugins.setEnabled(plugin, false)
            app.refreshPlugins()
            assertNull(app.playbackSource(first.sourceId))
            withContext(Dispatchers.Main) {
                player.pause()
                player.setMediaItem(first.mediaItem(), 3_000)
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && player.mediaItemCount == 1 && player.currentMediaItem?.mediaId == first.id }
        }
    }

    @Test fun shuffleStartsAtTheSelectedSongAndStillPlaysTheRestWithoutRepeat() = runBlocking {
        withFixture { player ->
            val queue = listOf(first.mediaItem(), second.mediaItem())
            withContext(Dispatchers.Main) {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems(queue, 1, 89_850)
                player.shuffleModeEnabled = true
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id }
            withContext(Dispatchers.Main) {
                player.setMediaItems(queue, 0, 89_850)
                player.prepare()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) { player.shuffleModeEnabled = false }
        }
    }

    @Test fun playerAnnouncesTheSelectedModeWithoutChangingTheCurrentSong() = runBlocking {
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                withContext(Dispatchers.Main) {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.shuffleModeEnabled = false
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 8_000)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY && !player.isPlaying }
                automation.press(automation.queueNode("First"))
                automation.press(automation.queueNode("顺序", "In Order"))
                automation.queueNode("已切换为随机播放", "Switched to shuffle")
                eventually { player.shuffleModeEnabled }
                automation.press(automation.queueNode("不循环", "Repeat Off"))
                automation.queueNode("已切换为列表循环", "Switched to repeat all")
                eventually { player.repeatMode == Player.REPEAT_MODE_ALL }
                automation.press(automation.queueNode("循环", "Repeat All"))
                val one = setOf("已切换为单曲循环", "Switched to repeat one")
                val notice = automation.queueNode(*one.toTypedArray())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, notice.liveRegion)
                if (InstrumentationRegistry.getArguments().getString("playbackModeScreenshot") == "true") {
                    val screenshot = checkNotNull(automation.takeScreenshot())
                    try {
                        File(app.cacheDir, "android-player-mode-notice.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screenshot.recycle() }
                }
                eventually { player.repeatMode == Player.REPEAT_MODE_ONE }
                assertNull(automation.findQueueNode(setOf("已切换为列表循环", "Switched to repeat all")))
                withTimeout(5_000) { while (automation.findQueueNode(one) != null) delay(50) }

                automation.press(automation.queueNode("单曲", "Repeat One"))
                automation.queueNode("已关闭循环", "Repeat turned off")
                eventually { player.repeatMode == Player.REPEAT_MODE_OFF && player.shuffleModeEnabled }
                automation.press(automation.queueNode("随机", "Shuffle"))
                automation.queueNode("已切换为顺序播放", "Switched to sequential playback")
                eventually { !player.shuffleModeEnabled }
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(first.id, second.id), player.queueIds())
                    assertEquals(first.id, player.currentMediaItem?.mediaId)
                    assertEquals(8_000L, player.currentPosition)
                    assertTrue(!player.playWhenReady)
                }
            } finally { withContext(Dispatchers.Main) { activity.finish() } }
        }
    }

    @Test fun landscapeRhythmShowsTheCurrentLineAndSeeksToTheNextWithoutResuming() = runBlocking {
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val screenshot = InstrumentationRegistry.getArguments().getString("rhythmScreenshot") == "true"
            val appearance = app.settings.values.value.appearance
            if (screenshot) withContext(Dispatchers.Main) { app.settings.update { it.copy(appearance = AppAppearance.Dark) } }
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                withContext(Dispatchers.Main) {
                    player.pause()
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY && !player.isPlaying }
                automation.press(automation.queueNode("First"))
                automation.press(automation.queueNode("横屏播放", "Landscape Player"))
                automation.press(automation.queueNode("律动", "Rhythm"))
                automation.queueNode("First  line")
                val nextLine = arrayOf("下一句：Next line", "Next line: Next line")
                automation.queueNode(*nextLine)

                withContext(Dispatchers.Main) { player.seekTo(2_850) }
                eventually { player.currentPosition == 2_850L }
                if (screenshot) {
                    automation.waitForIdle(250, 5_000)
                    val frame = checkNotNull(automation.takeScreenshot())
                    try { File(app.cacheDir, "android-rhythm-stage.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { frame.recycle() }
                }

                automation.press(automation.queueNode(*nextLine))
                eventually { player.currentMediaItem?.mediaId == first.id && player.currentPosition == 4_000L }
                automation.queueNode("Next line")
                assertNull(automation.findQueueNode(nextLine.toSet()))
                withContext(Dispatchers.Main) {
                    assertTrue(!player.playWhenReady)
                    assertEquals(listOf(first.id, second.id), player.queueIds())
                    player.seekTo(1, 0)
                }
                automation.queueNode("暂无歌词", "No Lyrics")
            } finally {
                withContext(Dispatchers.Main) {
                    activity.finish()
                    if (screenshot) app.settings.update { it.copy(appearance = appearance) }
                }
            }
        }
    }

    @Test fun mediaSessionSkipsByFifteenSecondsWithoutResumingPausedAudio() = runBlocking {
        withFixture { player ->
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(first.mediaItem()), 0, 20_000)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY && player.currentPosition == 20_000L }
            withContext(Dispatchers.Main) {
                assertEquals(15_000L, player.seekBackIncrement)
                assertEquals(15_000L, player.seekForwardIncrement)
                player.seekBack()
            }
            eventually { player.currentPosition == 5_000L && !player.playWhenReady }
            withContext(Dispatchers.Main) { player.seekForward() }
            eventually { player.currentPosition == 20_000L && !player.playWhenReady }
        }
    }

    @Test fun draggingLocalPlaylistRowsPersistsOrderAndCancellationDoesNot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val tracks = mutableListOf<Track>()
        var playlist: String? = null
        var activity: MainActivity? = null
        try {
            for (title in listOf("Sort First", "Sort Middle", "Sort Last")) {
                val wav = app.local.catalog.temporaryFile()
                val audio = wave(2).also { UUID.randomUUID().toString().encodeToByteArray().copyInto(it, 128) }
                val id = MessageDigest.getInstance("SHA-256").digest(audio).toHexString()
                wav.writeBytes(audio)
                val asset = PortableAsset("mb-import-$id.audio", audio.size.toLong(), id)
                try { tracks.add(app.local.restoreImport(wav, PortableImport(id, title, asset, "", false, 0)).track) }
                finally { wav.delete() }
            }
            val original = tracks.map(Track::id)
            val missing = "bilibili:unavailable-fixture"
            val initialOrder = listOf(original[0], missing, original[1], original[2])
            val name = "MB Playlist Sorting Fixture"
            val id = app.playlists.create(name, initialOrder)
            playlist = id
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            suspend fun open(vararg labels: String) {
                val button = generateSequence(automation.queueNode(*labels)) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
                assertEquals("Expected to open ${labels.joinToString()}", true, button?.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            open("资料库", "Library")
            open("本地歌单", "Local Playlists")
            automation.queueNode("搜索歌单", "Search Playlists")
            open(name)
            automation.press(automation.queueNode("排序", "Sort"))
            automation.waitForIdle(250, 5_000)
            val start = automation.queueNode("调整 Sort First 顺序", "Reorder Sort First")
            val end = automation.queueNode("调整 Sort Last 顺序", "Reorder Sort Last")
            val startingBounds = start.screenBounds()
            val endingBounds = end.screenBounds()
            automation.drag(start, end, cancel = true)
            automation.waitForIdle(250, 5_000)
            assertEquals(initialOrder, app.playlists.playlists.value.single { it.id == id }.trackIds)
            suspend fun restoredHandle(bounds: Rect, vararg labels: String): AccessibilityNodeInfo = withTimeout(5_000) {
                var handle = automation.queueNode(*labels)
                while (handle.screenBounds() != bounds) { delay(50); handle = automation.queueNode(*labels) }
                handle
            }
            val restoredStart = restoredHandle(startingBounds, "调整 Sort First 顺序", "Reorder Sort First")
            val restoredEnd = restoredHandle(endingBounds, "调整 Sort Last 顺序", "Reorder Sort Last")
            automation.drag(restoredStart, restoredEnd)
            val expected = listOf(original[1], missing, original[2], original[0])
            try { eventually { app.playlists.playlists.value.single { it.id == id }.trackIds == expected } }
            catch (failure: TimeoutCancellationException) {
                val actual = app.playlists.playlists.value.single { it.id == id }.trackIds
                throw AssertionError("Playlist drop should move First below Last (start=${restoredStart.screenBounds()}, " +
                    "end=${restoredEnd.screenBounds()}), order=${actual.map(initialOrder::indexOf)}", failure)
            }
            automation.press(automation.queueNode("完成排序", "Done Sorting"))
            val saved = LocalPlaylistStore(File(app.filesDir, "local-playlists.json")).apply { refresh() }
            assertEquals(expected, saved.playlists.value.single { it.id == id }.trackIds)
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            playlist?.let(app.playlists::delete)
            tracks.forEach { app.local.delete(it.sourceTrackId) }
        }
    }

    @Test fun draggingQueueRowsPreservesPlaybackAndPersistsTheirOrder() = runBlocking {
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val startingOrder = listOf(first.id, second.id)
            val rotatedOrder = listOf(second.id, first.id)
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                withContext(Dispatchers.Main) {
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 8_000)
                    player.prepare()
                    player.play()
                }
                eventually { player.isPlaying && player.currentPosition >= 8_000 }
                automation.press(automation.queueNode("播放队列", "Queue"))
                automation.queueNode("关闭队列", "Close Queue")
                automation.waitForIdle(250, 5_000)
                val firstHandle = automation.queueNode("编辑 First", "Edit First")
                val secondHandle = automation.queueNode("编辑 Second", "Edit Second")
                automation.drag(firstHandle, secondHandle, cancel = true)
                instrumentation.waitForIdleSync()
                withContext(Dispatchers.Main) { assertEquals(startingOrder, player.queueIds()) }

                val before = withContext(Dispatchers.Main) { player.currentPosition }
                automation.drag(automation.queueNode("编辑 First", "Edit First"), automation.queueNode("编辑 Second", "Edit Second"))
                try { eventually { player.queueIds() == rotatedOrder } }
                catch (failure: TimeoutCancellationException) { throw AssertionError("Dropping First below Second did not reorder the queue", failure) }
                eventually {
                    val saved = playbackStorage.getString("queue", null)?.let(QueueSnapshot::decode)
                    saved?.tracks?.map(Track::id) == rotatedOrder && saved.index == 1
                }
                withContext(Dispatchers.Main) {
                    assertTrue(player.isPlaying)
                    assertEquals(first.id, player.currentMediaItem?.mediaId)
                    assertTrue(player.currentPosition >= before)
                }

                automation.press(automation.queueNode("关闭队列", "Close Queue"))
                automation.press(automation.queueNode("First"))
                automation.press(automation.queueNode("横屏播放", "Landscape Player"))
                automation.press(automation.queueNode("待播清单", "Up Next"))
                automation.waitForIdle(250, 5_000)
                automation.drag(automation.queueNode("编辑 First", "Edit First"), automation.queueNode("编辑 Second", "Edit Second"))
                eventually { player.queueIds() == startingOrder && player.currentMediaItem?.mediaId == first.id && player.isPlaying }

                withTimeout(5_000) {
                    while (automation.queueNode("编辑 First", "Edit First").screenBounds().centerY() >=
                        automation.queueNode("编辑 Second", "Edit Second").screenBounds().centerY()) delay(50)
                }
                automation.press(automation.queueNode("编辑 Second", "Edit Second"))
                automation.press(automation.queueNode("从队列移除", "Remove from Queue"))
                eventually { player.queueIds() == listOf(first.id) && player.isPlaying }
            } finally { withContext(Dispatchers.Main) { activity.finish() } }
        }
    }

    @Test fun removingTheSelectedQueueRowPlaysItsReplacementEvenWhenPausedOrShuffled() = runBlocking {
        withFixture { player ->
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.connected }
                val queue = listOf(first, second, first)
                for (index in queue.indices) {
                    val remaining = queue.filterIndexed { candidate, _ -> candidate != index }
                    val replacement = index.coerceAtMost(remaining.lastIndex)
                    withContext(Dispatchers.Main) {
                        player.pause()
                        player.shuffleModeEnabled = index == queue.lastIndex
                        player.setMediaItems(queue.map { it.mediaItem() }, index, 8_000)
                        player.prepare()
                        if (index == queue.lastIndex) player.play()
                    }
                    eventually {
                        player.playbackState == Player.STATE_READY && player.currentMediaItemIndex == index &&
                            model.playback.value.tracks == queue && model.playback.value.index == index
                    }
                    withContext(Dispatchers.Main) { model.remove(index, model.playback.value.tracks) }
                    try {
                        eventually {
                            player.queueIds() == remaining.map(Track::id) && player.currentMediaItemIndex == replacement &&
                                player.isPlaying && player.currentPosition < 8_000
                        }
                    } catch (failure: TimeoutCancellationException) {
                        throw AssertionError("Removing selected row $index did not play replacement row $replacement", failure)
                    }
                    withContext(Dispatchers.Main) {
                        assertEquals(index == queue.lastIndex, player.shuffleModeEnabled)
                    }
                }

                val station = PlaybackStation(first.sourceId, "daily", "Daily")
                val stationQueue = listOf(first, second)
                withContext(Dispatchers.Main) {
                    player.pause()
                    player.shuffleModeEnabled = false
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem().withStation(station)), 1, 8_000)
                    player.prepare()
                }
                eventually {
                    player.playbackState == Player.STATE_READY && app.stationStatus.value.station == station &&
                        model.playback.value.tracks == stationQueue
                }
                withContext(Dispatchers.Main) { model.remove(0, model.playback.value.tracks) }
                eventually { model.playback.value.tracks == listOf(second) }
                withContext(Dispatchers.Main) {
                    assertEquals(8_000L, player.currentPosition)
                    assertTrue(!player.playWhenReady)
                    assertEquals(station, app.stationStatus.value.station)
                    model.remove(0, model.playback.value.tracks)
                }
                eventually { player.mediaItemCount == 0 && app.stationStatus.value.station == null }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    @Test fun draggingAQueueRowCanScrollPastTheInitiallyVisibleTracks() = runBlocking {
        withFixture { player ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val tracks = listOf(first, second) + (1..24).map { index -> first.copy(sourceTrackId = "extra-$index", title = "Queue $index") }
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                withContext(Dispatchers.Main) {
                    player.setMediaItems(tracks.map(Track::mediaItem), 0, 0)
                    player.prepare()
                    player.play()
                }
                eventually { player.isPlaying }
                automation.press(automation.queueNode("播放队列", "Queue"))
                automation.queueNode("关闭队列", "Close Queue")
                automation.waitForIdle(250, 5_000)
                val initiallyLast = (1..24).lastOrNull { index -> automation.findQueueNode(setOf("编辑 Queue $index", "Edit Queue $index")) != null } ?: 0
                val bottom = checkNotNull(automation.rootInActiveWindow).screenBounds().bottom
                val firstHandle = automation.queueNode("编辑 First", "Edit First")
                automation.drag(firstHandle, bottom - 3, holdMs = 2_000)
                try { eventually { player.currentMediaItemIndex > initiallyLast + 1 } }
                catch (failure: TimeoutCancellationException) {
                    val actual = withContext(Dispatchers.Main) { player.currentMediaItemIndex }
                    throw AssertionError("Dragging at the viewport edge stopped at $actual; last initial track was Queue $initiallyLast", failure)
                }
                withContext(Dispatchers.Main) {
                    assertEquals(first.id, player.currentMediaItem?.mediaId)
                    assertTrue(player.isPlaying)
                    assertEquals(tracks.drop(1).map(Track::id), player.queueIds().filterNot { it == first.id })
                }
            } finally { withContext(Dispatchers.Main) { activity.finish() } }
        }
    }

    @Test fun mediaSessionTrackButtonsWrapButAutomaticPlaybackStillStopsAtTheTail() = runBlocking {
        withFixture { player ->
            withContext(Dispatchers.Main) {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 25_000)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { player.seekToPrevious() }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) {
                assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                player.seekToNextMediaItem()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val manager = app.getSystemService(MediaSessionManager::class.java)
                var platform: PlatformMediaController? = null
                try {
                    automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
                    eventually {
                        platform = manager.getActiveSessions(null).firstOrNull { it.packageName == app.packageName }
                        platform != null
                    }
                } finally { automation.dropShellPermissionIdentity() }
                checkNotNull(platform).transportControls.skipToPrevious()
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            }
            withContext(Dispatchers.Main) { player.seekTo(1, 89_850) }
            eventually { player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) { assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode) }
        }
    }

    @Test fun mediaSessionNextCanContinueAStationWithOneTrack() = runBlocking {
        withFixture { player ->
            val station = PlaybackStation(first.sourceId, "transport", "Transport Fixture")
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(first.mediaItem().withStation(station)), 0, 0)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY && app.stationStatus.value.failure == StationFailure.Empty }
            withContext(Dispatchers.Main) {
                assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                player.seekToNext()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
        }
    }

    @Test fun leavingStationCancelsPendingPluginRequestAndKeepsThePlayingQueue() = runBlocking {
        withFixture { player ->
            val station = PlaybackStation(first.sourceId, "daily", "Daily")
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(first.mediaItem().withStation(station), second.mediaItem()), 0, 12_000)
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && app.stationStatus.value.station == station }
            val journal = File(plugin.dataDirectory, "storage.json")
            withTimeout(6_000) {
                while (!withContext(Dispatchers.IO) { journal.takeIf(File::isFile)?.readText()?.contains("stationRequest") == true }) delay(20)
            }
            val position = withContext(Dispatchers.Main) { player.currentPosition }
            var discontinuities = 0
            withContext(Dispatchers.Main) {
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        discontinuities++
                    }
                })
            }
            val result = withContext(Dispatchers.Main) { player.sendCustomCommand(leaveStation, Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, withContext(Dispatchers.IO) { result.get(2, TimeUnit.SECONDS).resultCode })
            assertEquals("daily", withTimeout(3_000) { source.search("station", 1).tracks.single().title })
            withContext(Dispatchers.Main) {
                assertNull(app.stationStatus.value.station)
                assertTrue(player.isPlaying)
                assertEquals(0, discontinuities)
                assertEquals(listOf(first.id, second.id), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                val persisted = checkNotNull(playbackStorage.getString("queue", null)?.let(QueueSnapshot::decode))
                assertNull(persisted.station)
                assertTrue("Playback position reset from $position to ${persisted.positionMs}", persisted.positionMs >= position - 500)
                assertEquals(listOf(first.id, second.id), persisted.tracks.map(Track::id))
            }
        }
    }

    private val playbackStorage get() = app.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private fun MediaController.queueIds() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also(::getBoundsInScreen)

    private fun UiAutomation.findQueueNode(labels: Set<String>): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() in labels || node.contentDescription?.toString() in labels)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::find) }
        }
        return rootInActiveWindow?.let(::find)
    }

    private suspend fun UiAutomation.queueNode(vararg labels: String): AccessibilityNodeInfo = withTimeoutOrNull(10_000) {
        val targets = labels.toSet()
        var match = findQueueNode(targets)
        while (match == null) { delay(50); match = findQueueNode(targets) }
        match
    } ?: error("Missing UI element: ${labels.joinToString()}")

    private fun UiAutomation.touch(start: Long, action: Int, x: Int, y: Int) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    private fun UiAutomation.press(node: AccessibilityNodeInfo) {
        val bounds = node.screenBounds()
        val start = SystemClock.uptimeMillis()
        touch(start, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY())
        touch(start, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY())
    }

    private suspend fun UiAutomation.drag(from: AccessibilityNodeInfo, to: AccessibilityNodeInfo, cancel: Boolean = false) =
        drag(from, to.screenBounds().centerY(), cancel)

    private suspend fun UiAutomation.drag(from: AccessibilityNodeInfo, endY: Int, cancel: Boolean = false, holdMs: Long = 0) {
        val bounds = from.screenBounds()
        val x = bounds.centerX()
        val startY = bounds.centerY()
        val start = SystemClock.uptimeMillis()
        touch(start, MotionEvent.ACTION_DOWN, x, startY)
        for (step in 1..10) {
            delay(24)
            touch(start, MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * step / 10)
        }
        delay(holdMs)
        touch(start, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, endY)
    }

    private inner class Fixture(val plugin: InstalledPlugin, val first: Track, val second: Track) {
        val connections = mutableListOf<ListenableFuture<MediaController>>()
        var active: MediaController? = null
        val source get() = app.playbackSource(plugin.id) as PluginSource

        suspend fun connect(): MediaController {
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            }
            connections.add(future)
            return withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
        }
    }

    private suspend fun withFixture(cacheSecond: Boolean = false, action: suspend Fixture.(MediaController) -> Unit) {
        val id = "played-fixture-${UUID.randomUUID()}"
        val first = Track(id, "first", "First", "Fixture artist")
        val second = first.copy(sourceTrackId = "second", title = "Second")
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        val fixture = Fixture(plugin, first, second)
        val store = app.offline.store
        try {
            app.refreshPlugins()
            withContext(Dispatchers.IO) {
                val audio = wave(90)
                for (track in listOf(first, second)) {
                    val staged = store.staging()
                    val cache = if (cacheSecond && track == second) checkNotNull(store.cacheTicket(track.id)) else null
                    try { staged.writeBytes(audio); store.save(staged, track, "wav", cache) }
                    finally { staged.delete() }
                }
            }
            val player = fixture.connect()
            withContext(Dispatchers.Main) {
                assertEquals("Run on a dedicated installation with an empty queue", 0, player.mediaItemCount)
            }
            fixture.active = player
            fixture.action(player)
        } finally {
            try {
                fixture.active?.let { player ->
                    withContext(Dispatchers.Main) { player.pause(); player.clearMediaItems() }
                    eventually { player.mediaItemCount == 0 && !playbackStorage.contains("queue") }
                    withContext(Dispatchers.IO) { playbackStorage.edit().commit() }
                }
            } finally {
                withContext(Dispatchers.Main) { fixture.connections.forEach(MediaController::releaseFuture) }
                app.plugins.remove(plugin)
                app.refreshPlugins()
                withContext(Dispatchers.IO) {
                    store.removeDownload(first.id)
                    if (cacheSecond) store.promote(second)
                    store.removeDownload(second.id)
                    app.local.catalog.writableDatabase.delete("track_activity", "track_id IN (?, ?)", arrayOf(first.id, second.id))
                    plugin.dataDirectory.deleteRecursively()
                }
            }
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(8_000) { while (!condition()) delay(20) }
    }

    private fun fixtureZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id","permissions":{"network":["artwork.fixture.test"]},
                "requestHeaders":[{"hosts":["artwork.fixture.test"],"kinds":["image"],"headers":{"Authorization":"Fixture artwork-only"}}]}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                export function stream({id}) {
                    if (id === 'pending') return new Promise(() => {});
                    if (id === 'restricted') {
                        host.storage.set('streamAttempts', (host.storage.get('streamAttempts') ?? 0) + 1);
                        throw Object.assign(new Error('Sign in first'), {code: 'signIn'});
                    }
                    throw new Error('Fixture audio uses a local copy');
                }
                export function played({id}) {
                    const reports = host.storage.get('plays') ?? [];
                    host.storage.set('plays', [...reports, id]);
                }
                export function lyrics({id}) {
                    return {lrc: id === 'first' ? '[00:02.00]First  line\n[00:04.00]Next line' : ''};
                }
                export function stationNext({id}) {
                    host.storage.set('stationRequest', id);
                    if (id === 'transport') {
                        const count = (host.storage.get('stationCount') ?? 0) + 1;
                        host.storage.set('stationCount', count);
                        return count === 4 ? [{id: 'second', name: 'Second', duration: 90000}] : [];
                    }
                    return new Promise(() => {});
                }
                export function search({query}) {
                    if (query === 'station') return {tracks: [{id: 'station', name: host.storage.get('stationRequest')}]};
                    if (query === 'streamAttempts') return {tracks: [{id: 'attempts', name: String(host.storage.get('streamAttempts') ?? 0)}]};
                    return {tracks: (host.storage.get('plays') ?? []).map((name, index) => ({id: String(index), name}))};
                }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
