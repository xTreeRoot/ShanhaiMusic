package com.xtreeroot.mb.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAdjustmentDragTest {
    @Test fun reversingTheFingerReturnsToTheSystemBaselineWithoutAccumulating() {
        val drag = VideoAdjustmentDrag(VideoAdjustmentKind.Volume, .4f, 400f)
        assertEquals(.7f, drag.value(-90f), .001f)
        assertEquals(.1f, drag.value(90f), .001f)
        assertEquals(.4f, drag.value(0f), .001f)
        assertEquals(1f, drag.value(-1000f), 0f)
        assertEquals(0f, drag.value(1000f), 0f)
    }
    @Test fun brightnessCannotBlackOutAndInvalidGeometryDoesNotChangeTheBaseline() {
        val drag = VideoAdjustmentDrag(VideoAdjustmentKind.Brightness, .6f, 300f)
        assertEquals(.01f, drag.value(1000f), 0f)
        assertEquals(.6f, drag.value(Float.NaN), 0f)
        assertEquals(.6f, drag.copy(height = 0f).value(-100f), 0f)
    }
}
