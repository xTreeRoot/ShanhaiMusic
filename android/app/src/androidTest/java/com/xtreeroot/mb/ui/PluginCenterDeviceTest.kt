package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCenterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun communitySearchAndInstalledManagementRemainReachable() {
        val context = instrumentation.targetContext
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        instrumentation.waitForIdleSync()
        if (find("插件", "Plugins") == null) {
            if (find("设置", "Settings") == null) click("更多操作", "More Actions")
            click("设置", "Settings")
        }
        click("插件", "Plugins")
        click("社区", "Community")
        assertTrue(waitFor("搜索插件", "Search Plugins") != null)
        if (InstrumentationRegistry.getArguments().getString("communityLive") == "true") {
            val deadline = SystemClock.uptimeMillis() + 45_000
            while (SystemClock.uptimeMillis() < deadline && find("安装", "Install") == null) SystemClock.sleep(100)
            assertTrue("GitHub community must expose installable repositories", find("安装", "Install") != null)
        }
        screenshot("community")
        click("已安装", "Installed")
        assertTrue(waitFor("从 GitHub 安装", "Install from GitHub") != null)
        assertTrue(find("一键导入/覆盖", "Import / Overwrite") != null)
        screenshot("installed")
    }

    private fun find(vararg labels: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() in labels || node.contentDescription?.toString() in labels) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> visit(child)?.let { return it } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(vararg labels: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(*labels)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun click(vararg labels: String) {
        var node = checkNotNull(waitFor(*labels)) { "Missing ${labels.joinToString()}" }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "plugin-center").apply { mkdirs() }
        automation.takeScreenshot()?.let { image ->
            File(root, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
