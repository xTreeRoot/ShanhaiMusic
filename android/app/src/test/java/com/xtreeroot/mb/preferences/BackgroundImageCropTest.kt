package com.xtreeroot.mb.preferences

import org.junit.Assert.*
import org.junit.Test

class BackgroundImageCropTest {
    @Test fun cropKeepsAspectAndClampsPanInsideOriginalPixels() {
        assertEquals(BackgroundCropRect(200, 0, 800, 800), backgroundCropRect(1200, 800, 1.0, 1.0, 0.0, 0.0))
        assertEquals(BackgroundCropRect(0, 400, 400, 400), backgroundCropRect(1200, 800, 1.0, 2.0, 3.0, -2.0))
        assertTrue(runCatching { backgroundCropRect(0, 1, 1.0, 1.0, 0.0, 0.0) }.isFailure)
    }
}
