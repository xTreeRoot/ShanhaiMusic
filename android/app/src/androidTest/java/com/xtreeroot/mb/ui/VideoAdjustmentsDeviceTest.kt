package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.VideoFrameState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class VideoAdjustmentsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val resolver = app.contentResolver
    private val audio = app.getSystemService(AudioManager::class.java)

    @Test fun sideDragsChangeRealSystemValuesInlineAndFullscreenWithoutPausing() = runBlocking {
        allowed(true)
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val screen = start(); val taps = mutableIntStateOf(0)
        try {
            for (full in listOf(false, true)) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 125)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3, 0)
                show(screen, full, false, taps)
                val area = Rect().also { awaitNode("Video Picture").getBoundsInScreen(it) }
                val initial = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                drag(area, .24f, .62f, .24f, .32f)
                assertTrue("Must change system media stream, not player gain", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > initial)
                awaitNode("System Volume")
                drag(area, .77f, .62f, .77f, .32f)
                assertTrue(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) > 125)
                awaitNode("System Brightness")
                capture(if (full) "android-fullscreen-system-brightness.png" else "android-inline-system-brightness.png")
                val higher = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
                drag(area, .77f, .32f, .77f, .62f)
                assertTrue(Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) < higher)
                assertEquals("Dragging must not toggle playback", 0, taps.intValue)
                withContext(Dispatchers.Main) { assertEquals(-1f, screen.window.attributes.screenBrightness, 0f) }
            }
        } finally {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            withContext(Dispatchers.Main) { screen.finish() }
            allowed(false)
        }
    }

    @Test fun permissionDenialNeverWritesBrightnessAndPortraitVideoHasNoAdjustmentGesture() = runBlocking {
        allowed(false)
        val before = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val screen = start(); val taps = mutableIntStateOf(0)
        try {
            show(screen, false, false, taps)
            val area = Rect().also { awaitNode("Video Picture").getBoundsInScreen(it) }
            drag(area, .77f, .6f, .77f, .3f)
            awaitNode("Open System Permission")
            assertEquals(before, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            tap("Not Now")
            show(screen, false, true, taps)
            val portrait = Rect().also { awaitNode("Video Picture").getBoundsInScreen(it) }
            drag(portrait, .22f, .6f, .22f, .3f)
            drag(portrait, .78f, .6f, .78f, .3f)
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(before, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            assertNull(node("Open System Permission")); assertNull(node("System Volume")); assertNull(node("System Brightness"))
            assertEquals(0, taps.intValue)
        } finally { withContext(Dispatchers.Main) { screen.finish() } }
    }

    private fun allowed(value: Boolean) {
        instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} WRITE_SETTINGS ${if (value) "allow" else "default"}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun start(): MainActivity {
        // 测试设备先确认 Android 自带的首次全屏教学，避免系统浮层拦截画面手势。
        instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        return instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    private suspend fun show(screen: MainActivity, full: Boolean, portrait: Boolean, taps: androidx.compose.runtime.MutableIntState) {
        val track = Track("fixture", "video", "Video", "Fixture Artist", videoWidth = if (portrait) 9 else 16, videoHeight = if (portrait) 16 else 9)
        withContext(Dispatchers.Main) { screen.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides true) {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 90.dp)
                .semantics { contentDescription = "fixture-$full-$portrait" }) {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    VideoStage(VideoFrameState(track.id, loading = false, aspectRatio = if (portrait) 9f / 16f else 16f / 9f),
                        PlaybackState(tracks = listOf(track), index = 0), { taps.intValue++ }, {}, {}, full, {}, portrait = portrait)
                }
            }
        } } } }
        awaitNode(if (full) "Exit Fullscreen" else "fixture-$full-$portrait")
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun visit(item: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (item.isVisibleToUser && (item.text?.toString() == label || item.contentDescription?.toString() == label)) return item
            return (0 until item.childCount).firstNotNullOfOrNull { item.getChild(it)?.let(::visit) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun awaitNode(label: String): AccessibilityNodeInfo {
        val found = withTimeoutOrNull(5000) {
            var result = node(label)
            while (result == null) { delay(25); result = node(label) }
            result
        }
        if (found == null) {
            capture("android-adjustments-missing-${label.replace(' ', '-')}.png")
            error("Missing accessibility node: $label")
        }
        return found
    }
    private suspend fun tap(label: String) {
        val target = generateSequence(awaitNode(label)) { it.parent }.first { it.isClickable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun drag(area: Rect, x0: Float, y0: Float, x1: Float, y1: Float) {
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        for (step in 0..24) {
            val fraction = step / 24f
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                area.left + area.width() * (x0 + (x1 - x0) * fraction), area.top + area.height() * (y0 + (y1 - y0) * fraction), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(12)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { frame.recycle() }
    }
}
