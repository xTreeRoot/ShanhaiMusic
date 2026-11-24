package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.UUID

internal fun interface PlaybackPages {
    suspend fun load(): PlaybackPage
}

internal data class PlaybackPage(val tracks: List<Track>, val next: PlaybackPages?)

internal fun <Cursor> playbackPages(cursor: Cursor, visited: Set<Cursor> = emptySet(),
                                   load: suspend (Cursor) -> Pair<List<Track>, Cursor?>): PlaybackPages? {
    if (cursor in visited) return null
    return PlaybackPages {
        val (tracks, next) = load(cursor)
        PlaybackPage(tracks, next?.let { playbackPages(it, visited + cursor, load) })
    }
}

internal class PendingPlaybackPages {
    private val pending = linkedMapOf<String, PlaybackPages>()

    @Synchronized fun offer(pages: PlaybackPages): String {
        if (pending.size >= 8) pending.remove(pending.keys.first())
        return UUID.randomUUID().toString().also { pending[it] = pages }
    }

    @Synchronized fun take(token: String): PlaybackPages? = pending.remove(token)
}

@OptIn(UnstableApi::class)
internal class PagedPlayback(private val player: ExoPlayer, private val scope: CoroutineScope,
                             private val playable: (Track) -> Boolean, private val changed: () -> Unit) : Player.Listener {
    private enum class Advance { Manual, Automatic }

    private var pages: PlaybackPages? = null
    private var job: Job? = null
    private var advance: Advance? = null
    private var advanceFrom: MediaItem? = null
    private var failed = false
    val pending: Boolean get() = pages != null
    val atTail: Boolean get() = player.mediaItemCount > 0 && nextIndex() == C.INDEX_UNSET

    init { player.addListener(this) }

    fun set(loader: PlaybackPages?) {
        job?.cancel(); job = null; cancelAdvance(); failed = false
        val wasPending = pending
        pages = loader
        if (wasPending || pending) updateTailPause()
        if (pending) refill()
        if (wasPending || pending) changed()
    }

    fun next(): Boolean {
        if (!pending || !atTail) return false
        waitForPage(Advance.Manual)
        return true
    }

    fun cancelAdvance() { advance = null; advanceFrom = null }

    private fun nextIndex(index: Int = player.currentMediaItemIndex): Int {
        val timeline = player.currentTimeline
        if (index !in 0 until timeline.windowCount) return C.INDEX_UNSET
        return timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
    }

    private fun updateTailPause() {
        val pause = pending && atTail && player.repeatMode != Player.REPEAT_MODE_ONE
        if (player.pauseAtEndOfMediaItems != pause) player.pauseAtEndOfMediaItems = pause
    }

    private fun waitForPage(reason: Advance) {
        if (reason == Advance.Manual) failed = false
        if (failed) return
        advanceFrom = player.currentMediaItem
        if (advance != Advance.Manual) advance = reason
        refill()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!pending || playWhenReady) return
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && atTail && player.repeatMode != Player.REPEAT_MODE_ONE) {
            failed = false
            waitForPage(Advance.Automatic)
        } else cancelAdvance()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!pending) return
        if (player.mediaItemCount == 0) { set(null); return }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            failed = false
            if (advanceFrom !== player.currentMediaItem) cancelAdvance()
        }
        updateTailPause()
        if (player.playWhenReady && player.playbackState == Player.STATE_ENDED && atTail && player.repeatMode != Player.REPEAT_MODE_ONE) {
            if (failed) player.pause() else waitForPage(Advance.Automatic)
        } else if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) refill()
    }

    private fun refill() {
        val firstPage = pages ?: return
        if (player.mediaItemCount == 0 || job != null || failed) return
        if (advance == null && nextIndex(nextIndex()) != C.INDEX_UNSET) return
        job = scope.launch {
            yield()
            var cursor: PlaybackPages? = firstPage
            try {
                val queued = (0 until player.mediaItemCount).mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
                repeat(8) {
                    val loaded = cursor?.load() ?: return@repeat
                    ensureActive()
                    cursor = loaded.next
                    val fresh = loaded.tracks.filter { queued.add(it.id) }
                    val tracks = fresh.filter(playable)
                    if (tracks.isNotEmpty()) {
                        finish(cursor, tracks)
                        return@launch
                    }
                    if (fresh.isEmpty() && loaded.tracks.isNotEmpty()) cursor = null
                }
                finish(null, emptyList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                pages = cursor; job = null; failed = true; cancelAdvance()
                if (player.playbackState == Player.STATE_ENDED) player.pause()
            }
        }
    }

    private fun finish(next: PlaybackPages?, tracks: List<Track>) {
        if (player.mediaItemCount == 0) { set(null); return }
        val intent = advance.takeIf { advanceFrom === player.currentMediaItem }
        val first = player.mediaItemCount
        val order = player.shuffledQueueOrder()?.plus(first until first + tracks.size)
        cancelAdvance()
        pages = next
        if (tracks.isNotEmpty()) {
            player.addMediaItems(tracks.map { it.mediaItem() })
            if (order != null) player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        }
        job = null
        updateTailPause()
        if (intent != null) {
            val target = if (tracks.isNotEmpty()) first else if (intent == Advance.Manual || player.repeatMode == Player.REPEAT_MODE_ALL)
                player.manualNeighbor(forward = true) else C.INDEX_UNSET
            if (target != C.INDEX_UNSET) {
                player.seekToDefaultPosition(target)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            } else player.pause()
        }
        changed()
    }
}
