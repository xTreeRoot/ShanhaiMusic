package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.preferences.AppAppearance
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedSettingsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation.also { automation ->
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Suppress("DEPRECATION")
    @Test fun appearanceAndSwitchesPersistAndChildPagesReturnToTheGroupedForm() = runBlocking {
        val previous = app.settings.values.value
        app.settings.update { it.copy(appearance = AppAppearance.Light) }
        val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            tap("资料库", "Library")
            node("歌曲", "Songs")
            capture("android-library-grouped-light.png")
            tap("设置", "Settings")
            node("检查版本", "App Updates")
            capture("android-settings-grouped-light.png")
            tap("背景皮肤", "Background Skin")
            node("选择背景图片", "Choose Background Image")
            tap("返回设置", "Back to Settings")
            tap("外观", "Appearance")
            tap("深色", "Dark")
            withTimeout(5_000) { while (app.settings.values.value.appearance != AppAppearance.Dark) delay(25) }
            node("背景皮肤", "Background Skin")
            capture("android-settings-grouped-dark.png")
            instrumentation.runOnMainSync {
                assertEquals(Color.TRANSPARENT, screen.window.navigationBarColor)
                if (Build.VERSION.SDK_INT >= 29) assertFalse(screen.window.isNavigationBarContrastEnforced)
                assertEquals(0, screen.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
            val smoke = app.settings.values.value.smoke
            tap("进度条烟雾", "Progress Bar Smoke")
            withTimeout(5_000) { while (app.settings.values.value.smoke == smoke) delay(25) }
            tap("完成", "Done")
            tap("设置", "Settings")
            assertEquals(!smoke, app.settings.values.value.smoke)
            tap("网络与系统代理", "Network & System Proxy")
            node("代理域名", "Proxy Domains")
            tap("返回设置", "Back to Settings")
            tap("完成", "Done")
            tap("歌曲", "Songs")
            tap("返回资料库", "Back to Library")
            capture("android-library-grouped-dark.png")
            app.settings.update { it.copy(appearance = AppAppearance.Light) }
            delay(150)
            instrumentation.runOnMainSync {
                assertTrue(screen.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)
            }
        } catch (failure: Throwable) {
            capture("android-settings-failed.png")
            throw failure
        } finally {
            app.settings.update { previous }
            instrumentation.runOnMainSync { screen.finish() }
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::visit) }
        }
        // API 28 关闭 PopupWindow 后 active window 可能暂时为空，按真实交互窗口读取宿主页面。
        return (automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }.filter { it.packageName?.toString() == app.packageName } + listOfNotNull(automation.rootInActiveWindow))
            .firstNotNullOfOrNull(::visit)
    }

    private suspend fun node(vararg labels: String): AccessibilityNodeInfo {
        fun matching() = find { it.text?.toString() in labels || it.contentDescription?.toString() in labels }
        delay(150)
        matching()?.let { return it }
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(14) {
                matching()?.let { return it }
                find { it.isScrollable }?.performAction(direction)
                delay(150)
            }
        }
        return withTimeoutOrNull(5_000) {
            var result = matching()
            while (result == null) { delay(50); result = matching() }
            result
        } ?: error("Missing UI element: ${labels.joinToString()}")
    }

    private suspend fun tap(vararg labels: String) {
        val item = node(*labels)
        val target = generateSequence(item) { it.parent }.first { it.isClickable && it.isEnabled }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("groupedScreenshot") != "true") return
        automation.waitForIdle(250, 5_000)
        val frame = checkNotNull(automation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }
}
