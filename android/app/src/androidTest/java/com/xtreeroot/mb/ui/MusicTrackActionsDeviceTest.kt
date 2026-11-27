package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 通过真实可访问动作验证互动、作者与底部面板，不断言静态标签排列。 */
class MusicTrackActionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val track = Track("player-actions-fixture", "song", "A long song title for music playback", "Fixture artist", artistId = "creator")

    @Test fun authorAndPlatformLikeUseTheCurrentTrackAndKeepUnknownCountsAbsent() = runBlocking<Unit> {
        val screen = start()
        val mutations = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val more = java.util.concurrent.atomic.AtomicBoolean()
        val source = object : InteractionSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun interactionSummary(trackId: String) = TrackInteractionSummary(120, 30)
            override suspend fun isLiked(trackId: String) = false
            override suspend fun like(trackId: String, enabled: Boolean) { assertEquals(track.sourceTrackId, trackId); mutations += enabled }
            override suspend fun comments(trackId: String, cursor: Int?) = CommentPage(emptyList(), 30, null)
            override suspend fun postComment(trackId: String, text: String): TrackComment = error("not used")
        }
        try {
            withContext(Dispatchers.Main) { screen.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides true) {
                MusicTrackMetadata(track, source, { opened.incrementAndGet() }) { more.set(true) }
            } } } }
            tap("Fixture artist ›"); await { opened.get() == 1 }
            await { find("Like")?.isEnabled == true }; tap("Like"); await { mutations == listOf(true) }
            awaitNode("Unlike"); assertNotNull(findState("121")); tap("Unlike"); await { mutations == listOf(true, false) }
            tap("Song Actions"); await { more.get() }
            capture("android-player-metadata")
            tap("Comments"); awaitNode("Comments · 30")
        } finally { withContext(Dispatchers.Main) { screen.finish() } }
    }

    @Test fun bottomPanelCollectsResourcesAndPersistsTheSharedQualityPreference() = runBlocking<Unit> {
        val previous = app.settings.values.value.quality
        val screen = start(); val store = ViewModelStore()
        val saved = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[SavedLibraryModel::class.java]
        }
        val creator = java.util.concurrent.atomic.AtomicBoolean()
        try {
            withContext(Dispatchers.Main) {
                screen.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides true) {
                    val preferences by app.settings.values.collectAsState()
                    MusicTrackActions(track, saved, preferences, { creator.set(true) }, {})
                } } }
            }
            tap("Collect"); await { saved.resources.value.any { it.id == track.id } }; awaitNode("Collected")
            tap("Audio Quality"); tap("Lossless"); await { app.settings.values.value.quality == AudioQuality.Lossless }
            capture("android-player-actions")
            tap("Artist: Fixture artist"); await { creator.get() }
            assertEquals(AudioQuality.Lossless, app.settings.values.value.quality)
        } finally {
            saved.removeResource(track.id)
            app.settings.update { it.copy(quality = previous) }
            withContext(Dispatchers.Main) { store.clear(); screen.finish() }
        }
    }
    @Test fun existingReadOnlyLikeIsVisibleBeforeSlowCountsFinish() = runBlocking<Unit> {
        val screen = start()
        val countGate = CompletableDeferred<Unit>()
        val source = object : InteractionSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun interactionCapabilities() = InteractionCapabilities(false, false, false, likesReadable = true)
            override suspend fun interactionSummary(trackId: String): TrackInteractionSummary { countGate.await(); return TrackInteractionSummary() }
            override suspend fun isLiked(trackId: String) = true
            override suspend fun like(trackId: String, enabled: Boolean) { fail("read-only source was written") }
            override suspend fun comments(trackId: String, cursor: Int?) = CommentPage(emptyList(), 0, null)
            override suspend fun postComment(trackId: String, text: String): TrackComment = error("unused")
        }
        try {
            withContext(Dispatchers.Main) { screen.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides true) {
                MusicTrackMetadata(track, source, null) { }
            } } } }
            val heart = awaitNode("Unlike")
            // 图标是子节点，禁用语义属于外层按钮。
            assertNotNull(generateSequence(heart) { it.parent }.firstOrNull { !it.isEnabled })
            capture("android-existing-platform-like")
            countGate.complete(Unit)
        } finally { countGate.complete(Unit); withContext(Dispatchers.Main) { screen.finish() } }
    }

    @Test fun platformPlaylistPickerWritesTheOriginalTrackToAnOwnedTarget() = runBlocking<Unit> {
        val parent = File(app.cacheDir, "platform-playlist-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val code = File(parent, "code").apply { mkdirs() }
        File(code, "index.js").writeText("""
            let added=[];
            export function account(){return {id:'one',name:'One'};}
            export function playlists(){return [{id:'mine',name:'Owned target',mine:true},{id:'foreign',name:'Foreign target'}];}
            export function addToPlaylist(args){if(args.id!=='mine')throw Error('wrong target');added.push(args);}
            export function probe(){return {added};}
        """.trimIndent())
        val manifest = com.xtreeroot.mb.plugin.PluginManifest.parse(org.json.JSONObject().put("id", track.sourceId))
        val runtime = com.xtreeroot.mb.plugin.PluginRuntime(app.assets, code, File(parent, "data"), manifest, emptyMap())
        val source = com.xtreeroot.mb.plugin.PluginSource(manifest, runtime)
        val screen = start()
        try {
            withContext(Dispatchers.Main) { screen.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides true) {
                PlatformPlaylistAction(track, source)
            } } } }
            tap("Add to Platform Playlist"); awaitNode("Owned target")
            assertNull(find("Foreign target")); capture("android-platform-playlist-picker")
            tap("Owned target"); await { find("Owned target") == null }
            val values = (runtime.invoke("probe") as org.json.JSONObject).getJSONArray("added")
            assertEquals(1, values.length()); assertEquals("mine", values.getJSONObject(0).getString("id"))
            assertEquals(track.sourceTrackId, values.getJSONObject(0).getJSONArray("trackIds").getString(0))
        } finally { runtime.close(); withContext(Dispatchers.Main) { screen.finish() }; parent.deleteRecursively() }
    }

    private fun capture(name: String) {
        val directory = File(app.filesDir, "review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun start() = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private suspend fun await(check: () -> Boolean) { withTimeout(8_000) { while (!check()) delay(25) } }
    private fun findState(value: String): AccessibilityNodeInfo? = scan { androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString() == value }
    private fun find(label: String): AccessibilityNodeInfo? = scan { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun scan(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::visit) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun awaitNode(label: String): AccessibilityNodeInfo { await { find(label) != null }; return checkNotNull(find(label)) }
    private suspend fun tap(label: String) {
        val node = awaitNode(label)
        val action = generateSequence(node) { it.parent }.first { it.isClickable }
        assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
