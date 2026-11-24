package com.xtreeroot.mb.playback

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginEntityKind
import com.xtreeroot.mb.plugin.PluginException
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.preferences.PlaybackMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GuestPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun publishedGuestAudioPlaysAndSeeks() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("guestPlugin")
        assumeTrue("Explicitly select a public plugin on a disposable device", requested != null)
        val id = checkNotNull(requested)
        val release = checkNotNull(releases[id]) { "Unsupported guest plugin" }
        check(app.plugins.installed().isEmpty()) { "Use a clean test user" }
        val archive = File(app.cacheDir, "guest-playback.zip")
        val pending = app.plugins.prepareZip { archive.inputStream() }
        val plugin = try {
            assertEquals(id, pending.manifest.id)
            assertEquals(release, pending.manifest.version)
            app.plugins.install(pending)
        } finally { app.plugins.discard(pending) }
        val oldSettings = app.settings.values.value
        val oldLimit = app.offline.store.snapshot.value.limitMb
        var activity: MainActivity? = null
        var player: PlaybackModel? = null
        try {
            app.offline.store.setLimit(0)
            app.settings.update { it.copy(playbackMode = PlaybackMode.Music, selectedSourceId = id) }
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val track = try { withTimeoutOrNull(25_000) { guestTrack(source) } }
            catch (_: PluginException) { throw AssertionError("$id guest catalog failed before media resolution") }
            checkNotNull(track) { "$id guest catalog did not yield a playable track" }
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(screen)[PlaybackModel::class.java].also {
                    player = it
                    it.play(listOf(track), track)
                }
            }
            val started = awaitAudio(model, track, 1_500)
            assertTrue("$id audio is not seekable", started.seekable && started.durationMs >= 10_000)
            val seek = minOf(started.durationMs / 2, 120_000)
            withContext(Dispatchers.Main) { model.seekCurrent(model.playback.value, seek) }
            val continued = awaitAudio(model, track, seek + 1_000)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\n$id $release guest: played to ${started.positionMs} ms; seek $seek → ${continued.positionMs} ms\n")
            })
        } finally {
            withContext(Dispatchers.Main) { player?.clear(); activity?.finish() }
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
            app.offline.store.setLimit(oldLimit)
            app.settings.update { oldSettings }
        }
    }

    private suspend fun guestTrack(source: PluginSource): Track? {
        fun List<Track>.playable() = firstOrNull { !it.isUnavailable && it.durationMs >= 15_000 }
        val home = source.catalog.load(PluginCatalogRequest.Home)
        home.sections.flatMap { it.tracks }.playable()?.let { return it }
        for (entity in home.sections.flatMap { it.entities }.filter { it.kind == PluginEntityKind.Playlist }.take(3)) {
            source.catalog.load(PluginCatalogRequest.Entity(entity)).sections.flatMap { it.tracks }.playable()?.let { return it }
        }
        val query = when (source.id) {
            "netease" -> "纯音乐"
            "ximalaya" -> "公开课"
            else -> return null
        }
        return source.search(query, 1).tracks.playable()
    }

    private suspend fun awaitAudio(model: PlaybackModel, track: Track, position: Long): PlaybackState {
        val started = withTimeoutOrNull(30_000) {
            var state = model.playback.value
            while (state.current?.id != track.id || !state.playing || state.positionMs < position) {
                if (app.trackPlaybackIssues.values.value[track.playbackKey] != null) return@withTimeoutOrNull null
                delay(50)
                state = model.playback.value
            }
            state
        }
        val failure = app.trackPlaybackIssues.values.value[track.playbackKey]
        val state = model.playback.value
        return checkNotNull(started) {
            "${track.sourceId} audio did not advance to $position ms: " +
                (failure?.let { "${it.issue} / ${it.diagnostic}" } ?: state.connectionIssue ?: if (state.audioFocusBlocked) "audio focus" else "timeout")
        }
    }

    private companion object {
        val releases = mapOf("netease" to "0.1.17", "ximalaya" to "0.1.7", "soda" to "0.3.0")
    }
}
