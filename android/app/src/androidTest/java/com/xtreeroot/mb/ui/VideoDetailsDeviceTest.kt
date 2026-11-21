package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.DiscoverySection
import com.xtreeroot.mb.music.DiscoverySource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.wave
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.PlaybackMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun portraitResourcesUseTallPictureAndOpenCommentsCaptionsAndSave() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val id = "portrait-ui-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        var activity: MainActivity? = null
        var engine: androidx.media3.exoplayer.ExoPlayer? = null
        val firstFrame = kotlinx.coroutines.CompletableDeferred<Unit>()
        val track = Track(id, "first", "Portrait fixture", "Creator", videoWidth = 1080, videoHeight = 1920, shortVideo = true, pageUrl = "https://media.example.test/watch")
        try {
            app.plugins.setEnabled(plugin, true); app.refreshPlugins()
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            withContext(Dispatchers.Main) {
                val player = ViewModelProvider(screen)[PlaybackModel::class.java]
                val preferences = app.settings.values.value
                val clip = File(app.cacheDir, "portrait-ui-fixture.mp4")
                instrumentation.context.assets.open("video-portrait.mp4").use { input -> clip.outputStream().use(input::copyTo) }
                val videoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(app).build().also {
                    it.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame.complete(Unit) }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { firstFrame.completeExceptionally(error) }
                    })
                    engine = it; it.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(clip))); it.prepare()
                }
                val videoFrame = com.xtreeroot.mb.playback.VideoFrameState(track.id, player = videoPlayer, loading = false, aspectRatio = 9f / 16f,
                    quality = "1080p", qualities = listOf(com.xtreeroot.mb.music.VideoQualityOption("1080p", "1080p"), com.xtreeroot.mb.music.VideoQualityOption("720p", "720p")))
                screen.setContent { com.xtreeroot.mb.ui.components.MBTheme(true) {
                    CompositionLocalProvider(LocalMBEnglish provides true) {
                        VideoDetailsScreen(player, PlaybackState(tracks = listOf(track), index = 0), preferences,
                            accountId = null, fullScreen = false, setFullScreen = {}, browse = {}, chooseMode = {}, sharedFrame = videoFrame, ownsVideoFrame = false)
                    }
                } }
            }
            await("Portrait fixture"); await("Video Actions")
            withTimeout(10_000) { firstFrame.await() }
            await("Like"); await("Share")
            captureVideoControl("android-portrait-controls.png")
            tap("Comments"); await("First page request 1")
            captureVideoControl("android-portrait-comments.png")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            tap("Video Actions"); tap("Save to Library")
            withTimeout(5_000) { while (app.resourceBookmarks.tracks.value.none { it.id == track.id }) delay(25) }
            await("Saved")
            captureVideoControl("android-portrait-more.png")
            tap("Video Quality"); tap("720p")
            assertEquals("720p", app.settings.values.value.videoQuality)
            tap("Close"); tap("Video Actions")
            tap("Captions"); await("No Captions")
            captureVideoControl("android-portrait-video.png")
        } finally {
            app.resourceBookmarks.remove(track.id)
            withContext(Dispatchers.Main) { activity?.finish(); engine?.release() }
            app.plugins.remove(app.plugins.installed().single { it.id == id }); app.refreshPlugins()
        }
    }

    @Test fun discoveryAndCategoryUseVideoCardsWithDurationsAndKeepTheSectionQueue() = runBlocking {
        val tracks = listOf(
            Track("video-grid", "first", "Morning Lights", "Fixture Artist", durationMs = 61_000),
            Track("video-grid", "second", "Night Drive", "Fixture Artist"),
            Track("video-grid", "third", "After the Rain", "Fixture Artist", durationMs = 122_000))
        val category = DiscoveryCategory.Chart
        val source = object : DiscoverySource {
            override val id = "video-grid"
            override suspend fun home() = listOf(discovery(category))
            override suspend fun discovery(category: DiscoveryCategory) = DiscoverySection(category, tracks)
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val opened = mutableStateOf(false)
        val video = mutableStateOf(true)
        val selection = mutableStateOf<Pair<List<Track>, Track>?>(null)
        try {
            withContext(Dispatchers.Main) {
                val factory = viewModelFactory { initializer { BrowseModel(app, source) } }
                val model = ViewModelProvider(activity, factory)["video-grid", BrowseModel::class.java]
                model.refresh()
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(true) {
                        CompositionLocalProvider(LocalMBEnglish provides true, LocalVideoBrowse provides video.value) {
                            Surface {
                                val play: (List<Track>, Track) -> Unit = { queue, track -> selection.value = queue to track }
                                if (opened.value) CategoryView(model, category, play)
                                else Discover(model, play) { model.open(it); opened.value = true }
                            }
                        }
                    }
                }
            }
            suspend fun verifyCards() {
                await(tracks.first().title)
                await(tracks[1].title)
                val first = Rect().also(matches(tracks.first().title).first()::getBoundsInScreen)
                val second = Rect().also(matches(tracks[1].title).first()::getBoundsInScreen)
                assertTrue("Video cards should share a row: $first and $second", first.top == second.top && first.right <= second.left)
                await("1:01")
                assertTrue(matches("0:00").isEmpty())
            }
            verifyCards()
            captureVideoControl("android-discovery-video-grid.png")
            tap("See More Music Charts")
            await("Play All")
            verifyCards()
            tap(tracks[1].title)
            assertEquals(tracks to tracks[1], withContext(Dispatchers.Main) { selection.value })
            withContext(Dispatchers.Main) { video.value = false }
            await(tracks[1].title) { node ->
                val first = matches(tracks.first().title).firstOrNull()?.let { Rect().also(it::getBoundsInScreen) }
                first != null && first.bottom <= Rect().also(node::getBoundsInScreen).top
            }
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    @Test fun musicDiscoveryUsesTwoRowsAndRetainsTheSectionQueue() = runBlocking {
        val tracks = (1..4).map { Track("music-cards", "$it", "Music Card $it", "Fixture Artist") }
        val category = DiscoveryCategory.Recommended
        val source = object : DiscoverySource {
            override val id = "music-cards"
            override suspend fun home() = listOf(DiscoverySection(category, tracks))
            override suspend fun discovery(category: DiscoveryCategory) = DiscoverySection(category, tracks)
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val selection = mutableStateOf<Pair<List<Track>, Track>?>(null)
        try {
            withContext(Dispatchers.Main) {
                val factory = viewModelFactory { initializer { BrowseModel(app, source) } }
                val model = ViewModelProvider(activity, factory)["music-cards", BrowseModel::class.java]
                model.refresh()
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(true) {
                        CompositionLocalProvider(LocalMBEnglish provides true, LocalVideoBrowse provides false) {
                            Surface { Discover(model, { queue, track -> selection.value = queue to track }, {}) }
                        }
                    }
                }
            }
            tracks.forEach { await(it.title) }
            val frames = tracks.map { Rect().also(matches(it.title).first()::getBoundsInScreen) }
            assertTrue("First column should contain two rows: $frames", frames[0].bottom < frames[1].top && frames[0].left == frames[1].left)
            assertTrue("Next column should align with first: $frames", frames[0].top == frames[2].top && frames[0].right < frames[2].left)
            tap(tracks[1].title)
            assertEquals(tracks to tracks[1], withContext(Dispatchers.Main) { selection.value })
            captureVideoControl("android-music-discovery-two-rows.png")
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }

    @Test fun videoTabsShareTheLoadedCommentPageAndResetOnTrackChange() = runBlocking {
        val id = "video-comments-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        var activity: MainActivity? = null
        try {
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            val tracks = listOf(Track(id, "first", "First Video", "Fixture Artist"), Track(id, "second", "Second Video", "Fixture Artist"))
            val selection = mutableStateOf(0)
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            withContext(Dispatchers.Main) {
                val player = ViewModelProvider(screen)[PlaybackModel::class.java]
                val preferences = app.settings.values.value
                screen.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(true) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            VideoDetailsScreen(player, PlaybackState(tracks = tracks, index = selection.value), preferences,
                                accountId = null, fullScreen = false, setFullScreen = {}, browse = {}, chooseMode = {})
                        }
                    }
                }
            }
            await("First Video")
            tap("Comments · 123")
            await("First page request 1")
            swipeVideoDetails("Comments · 123", true); await("Captions") { node -> generateSequence(node) { it.parent }.any { it.isSelected } }
            swipeVideoDetails("Captions", false); await("Comments · 123") { node -> generateSequence(node) { it.parent }.any { it.isSelected } }
            tap("More comments")
            await("Second page of first video")
            tap("Info")
            tap("Comments · 123")
            await("First page request 1")
            await("Second page of first video")
            withContext(Dispatchers.Main) { selection.value = 1 }
            await("Second Video")
            tap("Comments · 17")
            await("Only the second video")
            assertFalse(matches("Second page of first video").isNotEmpty())
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
        }
    }

    @Test fun selectingFromVideoInfoChangesTheCurrentItemWithoutReplacingTheQueue() = runBlocking {
        val id = "video-queue-${UUID.randomUUID()}"
        val tracks = listOf(Track(id, "first", "First Queue Video", "Fixture Artist", durationMs = 30_000),
            Track(id, "second", "Second Queue Video", "Fixture Artist", durationMs = 30_000))
        var activity: MainActivity? = null
        var model: PlaybackModel? = null
        try {
            tracks.forEach { track ->
                val staged = app.offline.store.staging()
                try { staged.writeBytes(wave(30)); app.offline.store.save(staged, track, "wav") }
                finally { staged.delete() }
            }
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val player = withContext(Dispatchers.Main) {
                ViewModelProvider(screen)[PlaybackModel::class.java].also { player ->
                    model = player
                    val preferences = app.settings.values.value
                    screen.setContent {
                        val playback by player.playback.collectAsStateWithLifecycle()
                        com.xtreeroot.mb.ui.components.MBTheme(true) {
                            CompositionLocalProvider(LocalMBEnglish provides true) {
                                Surface {
                                    VideoDetailsScreen(player, playback, preferences, accountId = null, fullScreen = false,
                                        setFullScreen = {}, browse = {}, chooseMode = {})
                                }
                            }
                        }
                    }
                    player.play(tracks, tracks.first())
                }
            }
            await("Queue · 2")
            if (InstrumentationRegistry.getArguments().getString("videoQueueScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-video-info-queue.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            await("Queue selection: ${tracks.first().title}", ::isCurrentQueueRow)
            tap("Queue selection: ${tracks.last().title}")
            withTimeout(5_000) {
                while (player.playback.value.let { it.current?.id != tracks.last().id || !it.playing }) delay(25)
            }
            assertEquals(tracks.map { it.id }, player.playback.value.tracks.map { it.id })
            if (InstrumentationRegistry.getArguments().getString("videoQueueScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-video-info-queue-selected.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            tap("Queue")
            await("Queue selection: ${tracks.last().title}", ::isCurrentQueueRow)
        } finally {
            withContext(Dispatchers.Main) { model?.clear(); activity?.finish() }
            tracks.forEach { app.offline.store.removeDownload(it.id) }
        }
    }

    @Test fun videoQueueChangesSystemMediaVolumeAndReflectsExternalChanges() = runBlocking {
        val audio = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val originalVolume = audio.getStreamVolume(stream)
        val targetVolume = audio.getStreamMaxVolume(stream) / 3
        val externalVolume = targetVolume + 1
        val preferences = app.settings.values.value
        val track = Track("volume-fixture", "video", "Volume Fixture", "Fixture Artist")
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            audio.setStreamVolume(stream, externalVolume, 0)
            withContext(Dispatchers.Main) {
                val model = ViewModelProvider(activity)[PlaybackModel::class.java]
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(true) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            Surface {
                                VideoDetailsScreen(model, PlaybackState(tracks = listOf(track), index = 0), preferences,
                                    accountId = null, fullScreen = false, setFullScreen = {}, browse = {}, chooseMode = {})
                            }
                        }
                    }
                }
            }
            await(track.title)
            assertTrue(matches("System Media Volume").isEmpty())
            tap("Queue")
            awaitVolume(externalVolume)
            await("System Media Volume") { node ->
                generateSequence(node) { it.parent }.firstOrNull { parent ->
                    parent.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, targetVolume.toFloat())
                }) == true
            }
            withTimeout(5_000) { while (audio.getStreamVolume(stream) != targetVolume) delay(25) }
            awaitVolume(targetVolume)
            assertEquals(preferences.volume, app.settings.values.value.volume)
            audio.setStreamVolume(stream, externalVolume, 0)
            awaitVolume(externalVolume)
            captureVideoControl("android-video-system-media-volume.png")
            tap("Info")
            await("Previous")
            assertTrue(matches("System Media Volume").isEmpty())
        } finally {
            audio.setStreamVolume(stream, originalVolume, 0)
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    @Test fun fullScreenVideoShowsTheCurrentTitleAndAnAccessibleExit() = runBlocking {
        val track = Track("full-screen-fixture", "video", "Full Screen Fixture", "Fixture Artist")
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withContext(Dispatchers.Main) {
                val model = ViewModelProvider(activity)[PlaybackModel::class.java]
                val preferences = app.settings.values.value
                val fullScreen = mutableStateOf(false)
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(true) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            Surface {
                                VideoDetailsScreen(model, PlaybackState(tracks = listOf(track), index = 0), preferences,
                                    accountId = null, fullScreen = fullScreen.value, setFullScreen = { fullScreen.value = it },
                                    browse = {}, chooseMode = {})
                            }
                        }
                    }
                }
            }
            tap("Fullscreen")
            await("Exit Fullscreen")
            await(track.title)
            captureVideoControl("android-video-fullscreen-title.png")
            tap("Exit Fullscreen")
            await("Queue")
            assertTrue(matches("Exit Fullscreen").isEmpty())
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    @Test fun switchingFromVideoToMusicKeepsThePausedTrackAndQueue() = runBlocking {
        val id = "video-mode-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        val tracks = listOf(Track(id, "first", "First Mode Fixture", "Fixture Artist", durationMs = 30_000),
            Track(id, "second", "Second Mode Fixture", "Fixture Artist", durationMs = 30_000))
        val previous = app.settings.values.value
        var activity: MainActivity? = null
        var model: PlaybackModel? = null
        try {
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            tracks.forEach { track ->
                val staged = app.offline.store.staging()
                try { staged.writeBytes(wave(30)); app.offline.store.save(staged, track, "wav") }
                finally { staged.delete() }
            }
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val player = withContext(Dispatchers.Main) {
                app.settings.update { it.copy(language = AppLanguage.English, playbackMode = PlaybackMode.Music,
                    selectedSourceId = id) }
                ViewModelProvider(screen)[PlaybackModel::class.java].also {
                    model = it
                    it.play(tracks, tracks.last())
                }
            }
            withTimeout(5_000) {
                while (player.playback.value.let { !it.playing || !it.videoAvailable }) delay(25)
            }
            withContext(Dispatchers.Main) {
                player.toggle()
                player.seekCurrent(player.playback.value, 6_000)
                player.watchCurrentVideo()
            }
            await("Music Mode")
            assertEquals(PlaybackMode.Music, app.settings.values.value.playbackMode)
            tap("Back to Browse")
            await("Expand Video")
            tap("Expand Video"); await("Back to Browse")
            tap("Back to Browse"); await("Close Video Window")
            tap("Close Video Window")
            await("Discover")
            tap("Music Mode")
            await("Video Mode")
            withTimeout(5_000) { while (app.settings.values.value.playbackMode != PlaybackMode.Video) delay(25) }
            withContext(Dispatchers.Main) { player.watchCurrentVideo() }
            await("Music Mode")
            tap("Back to Browse")
            await("Discover")
            assertEquals(PlaybackMode.Video, app.settings.values.value.playbackMode)
            withContext(Dispatchers.Main) { player.watchCurrentVideo() }
            await("Music Mode")
            captureVideoControl("android-video-playback-mode.png")
            tap("Music Mode")
            await("Now Playing")
            withTimeout(5_000) { while (player.playback.value.positionMs != 6_000L) delay(25) }
            val playback = player.playback.value
            assertEquals(PlaybackMode.Music, app.settings.values.value.playbackMode)
            assertEquals(tracks.map { it.id }, playback.tracks.map { it.id })
            assertEquals(tracks.last().id, playback.current?.id)
            assertFalse(playback.playWhenReady)
        } finally {
            withContext(Dispatchers.Main) {
                model?.clear()
                activity?.finish()
                app.settings.update { previous }
            }
            tracks.forEach { app.offline.store.removeDownload(it.id) }
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
        }
    }

    private fun swipeVideoDetails(tab: String, left: Boolean) {
        val node = matches(tab).first(); val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
        val size = instrumentation.targetContext.resources.displayMetrics
        val y = bounds.bottom + 75 * size.density
        val start = size.widthPixels * if (left) 0.8f else 0.2f
        val end = size.widthPixels * if (left) 0.2f else 0.8f
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = if (step == 0) android.view.MotionEvent.ACTION_DOWN else if (step == 20) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
            val event = android.view.MotionEvent.obtain(down, down + step * 15L, action, start + (end - start) * step / 20, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
    }

    private fun isSelectedPlaybackMode(node: AccessibilityNodeInfo): Boolean =
        generateSequence(node) { it.parent }.firstOrNull { it.isCheckable }?.isChecked == true

    private fun captureVideoControl(name: String) {
        if (InstrumentationRegistry.getArguments().getString("videoControlsScreenshot") != "true") return
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }

    private suspend fun awaitVolume(expected: Int) = await("System Media Volume") { node ->
        generateSequence(node) { it.parent }.firstNotNullOfOrNull { item ->
            if (item.refresh()) item.rangeInfo else null
        }?.current == expected.toFloat()
    }

    private fun matches(text: String): List<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node).filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) } +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == app.packageName }
        return roots.flatMap(::visit)
    }

    private suspend fun await(text: String, condition: (AccessibilityNodeInfo) -> Boolean = { true }) {
        val found = withTimeoutOrNull(5_000) {
            while (matches(text).none(condition)) delay(25)
            true
        }
        fun describe(node: AccessibilityNodeInfo): List<String> =
            listOf("${node.text}/${node.contentDescription} checked=${node.isChecked} clickable=${node.isClickable}") +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::describe).orEmpty() }
        assertTrue("Missing video detail: $text. ${if (found != true) instrumentation.uiAutomation.rootInActiveWindow?.let(::describe) else ""}", found == true)
    }

    private fun isCurrentQueueRow(node: AccessibilityNodeInfo): Boolean =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.isChecked == true

    private suspend fun tap(text: String) = await(text) { node ->
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun fixtureZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id","name":"Video Comment Fixture"}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                let firstRequests = 0;
                export function interactionSummary() { return {likes: 100000, comments: 123}; }
                export function like() {}
                export function video() { throw new Error('Fixture has no video stream'); }
                export function comments({ trackId, offset }) {
                    const author = 'Fixture Listener';
                    if (trackId !== 'first') return {total: 17, more: false, comments: [{id: 'second', author, text: 'Only the second video'}]};
                    if (offset > 0) return {total: 123, more: false, comments: [{id: 'later', author, text: 'Second page of first video'}]};
                    return {total: 123, more: true, comments: [{id: 'early', author, text: 'First page request ' + ++firstRequests}]};
                }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
