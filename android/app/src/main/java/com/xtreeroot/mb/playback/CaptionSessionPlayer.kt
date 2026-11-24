package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
internal class CaptionSessionPlayer(private val audio: ExoPlayer, private val video: VideoPlayback, private val stations: StationPlayback? = null,
                                    private val pages: PagedPlayback? = null, private val takePages: (String) -> PlaybackPages? = { null },
                                    private val retryPlayback: () -> Unit) :
    ForwardingSimpleBasePlayer(audio) {
    private data class SystemCaption(val trackId: String, val line: String?)

    private var caption: SystemCaption? = null

    init { video.onGateChanged = { invalidateState() } }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) pages?.cancelAdvance()
        if (stations?.updateIntent(playWhenReady) == true) return Futures.immediateVoidFuture()
        return if (video.changeIntent(playWhenReady)) Futures.immediateVoidFuture()
        else super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (mediaItems.singleOrNull()?.mediaId == resumeMediaId) return Futures.immediateVoidFuture()
        if (stations?.station != null) stations.set(null)
        pages?.set(null)
        val selected = mediaItems.getOrNull(startIndex)?.station()
        val token = mediaItems.getOrNull(startIndex)?.pagesToken()
        val clean = if (selected == null && token == null) mediaItems else mediaItems.mapIndexed { index, item ->
            if (index == startIndex) item.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.EMPTY).build() else item
        }
        val result = super.handleSetMediaItems(clean, startIndex, startPositionMs)
        if (audio.shuffleModeEnabled) shuffleFromSelection()
        if (selected != null) stations?.set(selected, prefetch = true)
        else if (token != null) pages?.set(takePages(token))
        return result
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled && !audio.shuffleModeEnabled) shuffleFromSelection()
        return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
    }

    private fun shuffleFromSelection() {
        val indices = 0 until audio.mediaItemCount
        val selected = audio.currentMediaItemIndex
        if (selected !in indices) return
        val order = listOf(selected) + indices.filterNot { it == selected }.shuffled()
        audio.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val forward = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> false
            else -> {
                if (seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM) { stations?.cancelPendingAdvance(); pages?.cancelAdvance() }
                return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
        }
        if (!audio.isPlayingAd && !(forward && (stations?.next() == true || pages?.next() == true))) {
            val target = audio.manualNeighbor(forward)
            if (target != C.INDEX_UNSET) {
                stations?.cancelPendingAdvance()
                pages?.cancelAdvance()
                audio.seekToDefaultPosition(target)
                if (audio.playbackState == Player.STATE_IDLE) audio.prepare()
                return handleSetPlayWhenReady(true)
            }
        }
        return Futures.immediateVoidFuture()
    }

    fun stationChanged() = invalidateState()

    override fun handlePrepare(): ListenableFuture<*> {
        if (player.playerError != null) retryPlayback()
        return super.handlePrepare()
    }

    fun showCaption(trackId: String?, text: String?) {
        val line = text?.let { captionWhitespace.replace(it, " ").trim() }?.takeIf(String::isNotEmpty)
        val next = trackId?.let { SystemCaption(it, line) }
        if (caption != next) {
            caption = next
            invalidateState()
        }
    }

    override fun getState(): State {
        val original = super.getState()
        val builder = original.buildUpon()
        val queueSize = if (audio.isPlayingAd) 0 else audio.mediaItemCount
        val commands = original.availableCommands.buildUpon().removeAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).apply {
            if (queueSize > 1 || (queueSize == 1 && (stations?.station != null || pages?.pending == true))) {
                addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (queueSize > 1) addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        builder.setAvailableCommands(commands.build())
        video.heldIntent?.let { intent ->
            builder.setPlayWhenReady(intent, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            if (player.playbackState == Player.STATE_READY) builder.setPlaybackState(Player.STATE_BUFFERING)
        }
        val item = player.currentMediaItem
        val current = caption?.takeIf { it.trackId == item?.mediaId }
        val trackTitle = item?.mediaMetadata?.title ?: player.mediaMetadata.title
        if (current != null && trackTitle != null) {
            val title = current.line ?: trackTitle
            val metadata = player.mediaMetadata.buildUpon().setTitle(title).setDisplayTitle(title).setArtist(trackTitle).build()
            builder.setPlaylist(player.currentTimeline, player.currentTracks, metadata)
        }
        return builder.build()
    }
}

internal fun Player.manualNeighbor(forward: Boolean): Int {
    val timeline = currentTimeline
    if (timeline.isEmpty) return C.INDEX_UNSET
    return if (forward) timeline.getNextWindowIndex(currentMediaItemIndex, Player.REPEAT_MODE_ALL, shuffleModeEnabled)
    else timeline.getPreviousWindowIndex(currentMediaItemIndex, Player.REPEAT_MODE_ALL, shuffleModeEnabled)
}

private val captionWhitespace = Regex("[\\p{Z}\\s]+")
