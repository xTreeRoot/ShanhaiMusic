package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.preferences.LyricStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun followsWrappedAndEdgeLinesAndRequiresSeekConfirmation() = runBlocking {
        val lines = listOf(
            CaptionLine(1_000, 2_000, "First light"),
            CaptionLine(2_000, 3_000, "Between the city and the sea, every little window catches the light",
                "夜色落下，长长的街道上还有许多没有熄灭的灯。"),
        ) + (3L..12L).map { start -> CaptionLine(start * 1_000, (start + 1) * 1_000, "Distant light $start") } +
            CaptionLine(13_000, 14_000, "Last light at the end of the road", "路尽头的灯光")
        val position = mutableLongStateOf(1_200)
        val sought = mutableLongStateOf(-1)
        val motion = mutableStateOf(false)
        val app = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    MaterialTheme(colorScheme = musicDark) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(270.dp, 360.dp).semantics { contentDescription = "Lyrics viewport" }) {
                                        CaptionStage(lines, position.longValue, LyricStyle.Flow, false, motion.value) { time ->
                                            sought.longValue = time
                                            position.longValue = time
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            awaitCentered(lines.first())
            val wrapped = lines[1]
            withContext(Dispatchers.Main) { position.longValue = 2_300 }
            awaitCentered(wrapped)
            if (InstrumentationRegistry.getArguments().getString("lyricFollowScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-lyrics-follow-wrapped.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            val first = lines.first()
            assertTrue("Cannot tap the first lyric", awaitNode(first.text) { node ->
                generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            })
            assertTrue(awaitNode("Play from lyric ${first.text}") { true })
            assertEquals(-1L, withContext(Dispatchers.Main) { sought.longValue })
            assertTrue(awaitNode(first.text) { row ->
                generateSequence(row) { it.parent }.firstOrNull { it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            })
            assertEquals(-1L, withContext(Dispatchers.Main) { sought.longValue })
            if (InstrumentationRegistry.getArguments().getString("lyricFollowScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-lyrics-confirmation.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            assertTrue("Cannot confirm selected lyric", awaitNode("Play from lyric ${first.text}") { node ->
                generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    ?.let { confirm ->
                        val rect = Rect().also(confirm::getBoundsInScreen)
                        val down = SystemClock.uptimeMillis()
                        for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, rect.exactCenterX(), rect.exactCenterY(), 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                        }
                        true
                    } == true
            })
            assertTrue(withTimeoutOrNull(5_000) { while (sought.longValue != first.startMs) delay(25); true } == true)
            awaitCentered(first)
            withContext(Dispatchers.Main) { motion.value = true; position.longValue = 13_300 }
            awaitCentered(lines.last())
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    private suspend fun awaitCentered(line: CaptionLine) {
        var centers: Pair<Int, Int>? = null
        val aligned = awaitNode(line.text) { row ->
            val viewport = node("Lyrics viewport") ?: return@awaitNode false
            centers = Rect().also(row::getBoundsInScreen).centerY() to Rect().also(viewport::getBoundsInScreen).centerY()
            centers?.let { (rowCenter, viewportCenter) -> kotlin.math.abs(rowCenter - viewportCenter) <= 2 } == true
        }
        assertTrue("Active lyric '${line.text}' should be centered: $centers", aligned)
    }

    private suspend fun awaitNode(name: String, check: (AccessibilityNodeInfo) -> Boolean): Boolean =
        withTimeoutOrNull(5_000) {
            while (node(name)?.let(check) != true) delay(25)
            true
        } == true

    private fun node(name: String): AccessibilityNodeInfo? {
        fun visit(candidate: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (candidate.isVisibleToUser && candidate.contentDescription?.toString()?.substringBefore('\n') == name) return candidate
            return (0 until candidate.childCount).firstNotNullOfOrNull { candidate.getChild(it)?.let(::visit) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
}
