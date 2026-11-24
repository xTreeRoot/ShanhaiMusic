package com.xtreeroot.mb.playback

import org.junit.Assert.*
import org.junit.Test

class VideoClockCorrectionTest {
    @Test fun moderateDriftChangesSpeedWithoutDiscardingBuffer() {
        assertEquals(VideoClockCorrection(false, 1.03f), videoClockCorrection(400, true, 5_000))
        assertEquals(VideoClockCorrection(false, 0.97f), videoClockCorrection(-400, true, 5_000))
        assertEquals(VideoClockCorrection(false, 1f), videoClockCorrection(40, true, 5_000))
    }
    @Test fun hardSeekIsBoundedButPausedAlignmentRemainsPrecise() {
        assertTrue(videoClockCorrection(900, true, 5_000).seek)
        assertFalse(videoClockCorrection(900, true, 1499).seek)
        assertTrue(videoClockCorrection(90, false, 5_000).seek)
    }
}
