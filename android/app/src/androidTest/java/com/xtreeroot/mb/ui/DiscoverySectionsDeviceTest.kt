package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.plugin.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoverySectionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun cancelSaveDragAndReopenPreserveIndependentColumnChoices() = runBlocking {
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val parent = File(app.cacheDir, "discovery-test-${UUID.randomUUID()}").apply { mkdirs() }
        val code = File(parent, "code").apply { mkdirs() }
        File(code, "index.js").writeText("export function home(){return {sections:[]};}")
        val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","discovery":[{"id":"one","title":"第一栏目","page":"one"},{"id":"two","title":"第二栏目","page":"two"},{"id":"three","title":"第三栏目","page":"three"}]}"""))
        val installer = PluginInstaller(File(parent, "installed"))
        val open = mutableStateOf(false)
        val detail = mutableStateOf(false)
        val plugin = InstalledPlugin(manifest, UUID.randomUUID(), code, File(parent, "data"), null, false)
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            installer.loadDiscoveryPreferences()
            withContext(Dispatchers.Main) { activity.setContent { MaterialTheme { CompositionLocalProvider(LocalMBEnglish provides false) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (open.value) DiscoverySectionsEditor(manifest, installer) { open.value = false }
                    else if (detail.value) Text("插件详情已打开")
                    else PluginsOverview(PluginManagerState(installed = listOf(plugin)), false, { detail.value = true }, {}, {}, { _, _ -> }, {}, {}, {}, {}, display = { open.value = true })

                }
            } } } }
            node("设置显示")
            screenshot("android-plugin-display-settings.png")
            tap("设置显示"); assertFalse(detail.value)
            tap("栏目开关 第一栏目"); tap("取消", "Cancel")
            withTimeout(6_000) { while (open.value) delay(50) }; assertTrue(installer.loadDiscoveryPreferences().isEmpty)
            tap("设置显示"); assertFalse(detail.value)
            tap("栏目开关 第一栏目")
            val third = Rect().also { node("排序 第三栏目").getBoundsInScreen(it) }
            val first = Rect().also { node("排序 第一栏目").getBoundsInScreen(it) }
            drag(third, first)
            screenshot("android-discovery-editor.png")
            tap("保存", "Save"); withTimeout(6_000) { while (open.value) delay(50) }
            val saved = installer.loadDiscoveryPreferences().resolve(manifest.id, manifest.discoverySections.map { it.preferenceSection })
            assertEquals(false, saved.enabled["one"])
            assertEquals("three", saved.order.first())
            val reopened = PluginInstaller(File(parent, "installed")).loadDiscoveryPreferences()
            assertEquals(saved, reopened.resolve(manifest.id, manifest.discoverySections.map { it.preferenceSection }))
            tap("设置显示"); assertFalse(detail.value)
            assertFalse(node("栏目开关 第一栏目").isChecked)
            screenshot("android-discovery-editor-reopened.png")
            tap("取消"); withTimeout(6_000) { while (open.value) delay(50) }
            tap(manifest.name); assertTrue(detail.value)
        } finally { withContext(Dispatchers.Main) { activity.finish() }; parent.deleteRecursively() }
    }
    private suspend fun node(vararg labels: String): AccessibilityNodeInfo {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() in labels || n.contentDescription?.toString() in labels) return n
            return (0 until n.childCount).firstNotNullOfOrNull { n.getChild(it)?.let(::find) }
        }
        val found = withTimeoutOrNull(6_000) {
            var result: AccessibilityNodeInfo? = null
            while (result == null) { result = automation.rootInActiveWindow?.let(::find) ?: automation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::find); if (result == null) delay(50) }
            result
        }
        if (found == null) {
            screenshot("android-discovery-missing.png")
            fun tree(n: AccessibilityNodeInfo): String = n.toString() + "\n" + (0 until n.childCount).mapNotNull { n.getChild(it)?.let(::tree) }.joinToString("\n")
            File(app.cacheDir, "android-discovery-missing.txt").writeText((listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }).joinToString("\n", transform = ::tree))
            error("Missing accessibility element: ${labels.joinToString()}")
        }
        return found
    }
    private suspend fun tap(vararg labels: String) {
        val target = generateSequence(node(*labels)) { it.parent }.first { it.isClickable && it.isEnabled }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); delay(180)
    }
    private fun drag(from: Rect, to: Rect) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, from.centerX().toFloat(), y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from.centerY().toFloat()); SystemClock.sleep(650)
        repeat(16) { i -> event(MotionEvent.ACTION_MOVE, from.centerY() + (to.centerY() - from.centerY()) * (i + 1) / 16f); SystemClock.sleep(35) }
        event(MotionEvent.ACTION_UP, to.centerY().toFloat()); SystemClock.sleep(300)
    }
    private fun screenshot(name: String) {
        automation.waitForIdle(200, 4000)
        val image = checkNotNull(automation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }
}
