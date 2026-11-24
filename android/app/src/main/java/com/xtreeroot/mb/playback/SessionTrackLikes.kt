package com.xtreeroot.mb.playback

import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SessionTrackLikes(
    private val currentTrack: () -> Track?,
    private val resolve: (String) -> InteractionSource?,
    private val publish: (Boolean?) -> Unit,
) {
    private class Target(val track: Track, val source: InteractionSource) {
        var writing = false
    }

    private var target: Target? = null

    suspend fun refresh(track: Track?) {
        target = null
        publish(null)
        val source = track?.let { resolve(it.sourceId) } ?: return
        val liked = try {
            if (!source.interactionCapabilities().likes) return
            source.isLiked(track.sourceTrackId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        currentCoroutineContext().ensureActive()
        if (currentTrack()?.id != track.id || resolve(track.sourceId) !== source) return
        target = Target(track, source)
        publish(liked)
    }

    suspend fun setLiked(enabled: Boolean): Boolean {
        val selected = target ?: return false
        val track = selected.track
        if (selected.writing || currentTrack()?.id != track.id || resolve(track.sourceId) !== selected.source) return false
        selected.writing = true
        try {
            if (selected.source.isLiked(track.sourceTrackId) != enabled) {
                currentCoroutineContext().ensureActive()
                if (target !== selected || currentTrack()?.id != track.id || resolve(track.sourceId) !== selected.source) return false
                selected.source.like(track.sourceTrackId, enabled)
            }
            if (target === selected) publish(enabled)
            return true
        } finally {
            selected.writing = false
        }
    }
}
