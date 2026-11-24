package com.xtreeroot.mb.playback

import com.xtreeroot.mb.preferences.PlaybackMode

internal data class PlaybackPresentation(val mode: PlaybackMode = PlaybackMode.Music, val opening: Long = 0) {
    fun update(supportsVideo: Boolean, contentPreference: PlaybackMode? = null): PlaybackPresentation {
        val next = if (supportsVideo) contentPreference ?: mode else PlaybackMode.Music
        val opens = contentPreference != null
        return PlaybackPresentation(next, opening + if (opens) 1 else 0)
    }
}
