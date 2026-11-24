package com.xtreeroot.mb.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/** 验证清空、密码显隐与非法地址不会接入或改写已有绑定，连续执行五次。 */
class NodeFormDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun invalidAddressAndPasswordVisibilityKeepExistingBinding() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val before = app.settings.nodeBinding.value
        repeat(5) {
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                withContext(Dispatchers.Main) {
                    activity.setContent {
                        com.xtreeroot.mb.ui.components.MBTheme(false) {
                            CompositionLocalProvider(LocalMBEnglish provides true) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                    NodeSettingsView(app, emptyMap(), MaterialTheme.colorScheme.background) {}
                                }
                            }
                        }
                    }
                }
                edit("Address", "https://synthetic.example")
                tap("Clear address")
                awaitEditable("Address") { it.text.isNullOrEmpty() || it.text?.toString() == "Domain or HTTPS address" }
                edit("Address", "http://synthetic.example")
                edit("Password", "synthetic fixture")
                awaitEditable("Password") { it.isPassword }
                tap("Show")
                awaitNode("Hide")
                // 密码键盘类型会保留 accessibility isPassword，不能用它断言可视变换。
                awaitEditable("Password") { it.text?.toString() == "synthetic fixture" }
                if (it == 0) screenshot("node-settings-password-visible.png")
                tap("Hide")
                awaitEditable("Password") { it.isPassword }
                tap(if (before == null) "Connect to Node" else "Reconnect")
                awaitNode("Enter an HTTPS node address without a path.")
                assertSame(before, app.settings.nodeBinding.value)
                if (it == 0) {
                    screenshot("node-settings.png")
                }
            } finally { withContext(Dispatchers.Main) { activity.finish() } }
        }
    }

    private fun find(text: String, editable: Boolean, actionable: Boolean = false): AccessibilityNodeInfo? {
        fun labelled(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == text || node.contentDescription?.toString() == text ||
            (0 until node.childCount).any { node.getChild(it)?.let(::labelled) == true }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val matches = if (editable) node.isEditable && labelled(node) else node.text?.toString() == text || node.contentDescription?.toString() == text
            if (matches && (!actionable || generateSequence(node) { it.parent }.any { it.isClickable && it.isEnabled })) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::visit) }
        }
        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == app.packageName }
        return roots.firstNotNullOfOrNull(::visit)
    }
    private suspend fun awaitNode(text: String, editable: Boolean = false, actionable: Boolean = false): AccessibilityNodeInfo {
        val value = withTimeoutOrNull(8_000) {
            var found = find(text, editable, actionable)
            while (found == null) { delay(30); found = find(text, editable, actionable) }
            found
        }
        if (value != null) return value
        fun describe(node: AccessibilityNodeInfo): String = "${node.text}/${node.contentDescription} editable=${node.isEditable} class=${node.className}\n" +
            (0 until node.childCount).joinToString("") { node.getChild(it)?.let(::describe).orEmpty() }
        throw AssertionError("Missing $text editable=$editable. " + instrumentation.uiAutomation.rootInActiveWindow?.let(::describe))
    }
    private suspend fun edit(label: String, text: String) {
        val node = awaitNode(label, true)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync()
    }
    private suspend fun awaitEditable(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val matched = withTimeoutOrNull(8_000) {
            while (true) {
                val node = find(label, true)
                if (node != null && predicate(node)) return@withTimeoutOrNull true
                delay(30)
            }
        }
        val node = find(label, true)
        assertEquals("Unexpected $label text=${node?.text} password=${node?.isPassword}", true, matched)
    }
    private suspend fun tap(label: String) {
        val node = awaitNode(label, actionable = true)
        val button = generateSequence(node) { it.parent }.first { it.isClickable && it.isEnabled }
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { frame ->
            try { File(app.cacheDir, name).outputStream().use { output -> frame.compress(Bitmap.CompressFormat.PNG, 100, output) } }
            finally { frame.recycle() }
        }
    }
}
