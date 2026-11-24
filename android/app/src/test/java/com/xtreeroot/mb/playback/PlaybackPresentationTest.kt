package com.xtreeroot.mb.playback

import com.xtreeroot.mb.preferences.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun restoredOrTransportedTrackDoesNotApplySavedVideoPreferenceUntilContentSelection() {
        val restored = PlaybackPresentation().update(supportsVideo = true)
        assertEquals(PlaybackMode.Music, restored.mode)
        assertEquals(0L, restored.opening)
        val explicit = restored.update(supportsVideo = true, contentPreference = PlaybackMode.Video)
        assertEquals(PlaybackMode.Video, explicit.mode)
        assertEquals(1L, explicit.opening)
        assertEquals(explicit, explicit.update(supportsVideo = true))
        assertEquals(2L, explicit.update(supportsVideo = true, contentPreference = PlaybackMode.Video).opening)
    }

    @Test fun playbackOfALocalTrackForcesMusicWithoutEatingTheSavedVideoPreference() {
        val video = PlaybackPresentation().update(supportsVideo = true, contentPreference = PlaybackMode.Video)
        val local = video.update(supportsVideo = false, contentPreference = PlaybackMode.Video)
        assertEquals(PlaybackMode.Music, local.mode)
        assertEquals(video.opening + 1, local.opening)
        assertEquals(local, local.update(supportsVideo = true))
        assertEquals(PlaybackMode.Video, local.update(supportsVideo = true, contentPreference = PlaybackMode.Video).mode)
    }

    @Test fun selectingMusicAlwaysOpensMusicPlayerButTransportDoesNotReopenIt() {
        val music = PlaybackPresentation().update(supportsVideo = true, contentPreference = PlaybackMode.Music)
        assertEquals(PlaybackMode.Music, music.mode)
        assertEquals(1L, music.opening)
        assertEquals(music, music.update(supportsVideo = true))
        assertEquals(2L, music.update(supportsVideo = true, contentPreference = PlaybackMode.Music).opening)
    }
}
