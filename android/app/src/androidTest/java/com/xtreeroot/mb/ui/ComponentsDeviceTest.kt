package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.ui.components.*
import com.xtreeroot.mb.update.AppRelease
import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ComponentsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test fun controlsKeepEditingSelectionDisabledAndDialogActionsWithLargeText() = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val checked = mutableStateOf(false)
        val input = mutableStateOf("")
        val menu = mutableStateOf(false)
        val dialog = mutableStateOf(false)
        var accepted = 0
        var disabledClicks = 0
        val dark = mutableStateOf(false)
        try {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    MBTheme(dark.value) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                            androidx.compose.material3.Scaffold(topBar = { MBTopAppBar({ Text("Component Review") }, largeTitle = true) }) { padding ->
                                Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MBTextField(input.value, { input.value = it }, label = { Text("Repository") }, modifier = Modifier.semantics { contentDescription = "Repository Input" })
                                    MBSwitch(checked.value, { checked.value = it }, Modifier.semantics { contentDescription = "Enable Source" })
                                    MBTextButton({ disabledClicks++ }, enabled = false) { Text("Disabled Action") }
                                    Box {
                                        MBTextButton({ menu.value = true }) { Text("Open Menu") }
                                        MBDropdownMenu(menu.value, { menu.value = false }) {
                                            MBMenuItem({ Text("Confirm Import") }, { menu.value = false; dialog.value = true })
                                        }
                                    }
                                }
                            }
                            if (dialog.value) MBAlertDialog({ dialog.value = false },
                                title = { Text("Confirm Update") }, text = { Text(input.value) },
                                confirmButton = { MBTextButton({ accepted++; dialog.value = false }) { Text("Apply") } },
                                dismissButton = { MBTextButton({ dialog.value = false }) { Text("Cancel") } })
                        }
                    }
                }
            }
            await("Repository Input")
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "xTreeRoot/MB") }
            // TextField 的外层描述节点与真正的可编辑子节点分开，按编辑动作定位。
            val editor = withTimeout(5_000) {
                var result: AccessibilityNodeInfo? = null
                while (result == null) {
                    result = allNodes().firstOrNull { it.isEditable }
                    if (result == null) delay(25)
                }
                result
            }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            await("xTreeRoot/MB")
            tap("Enable Source")
            assertTrue(withContext(Dispatchers.Main) { checked.value })
            assertFalse(nodes("Disabled Action").any { it.isEnabled && it.isClickable })
            tap("Open Menu"); tap("Confirm Import"); await("Confirm Update")
            screenshot("android-components-light.png")
            tap("Cancel"); assertEquals(0, accepted)
            withContext(Dispatchers.Main) { dark.value = true }
            tap("Open Menu"); tap("Confirm Import"); await("Confirm Update")
            screenshot("android-components-dark.png")
            tap("Apply")
            assertEquals(1, accepted); assertEquals(0, disabledClicks)
            assertEquals("xTreeRoot/MB", withContext(Dispatchers.Main) { input.value })
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }

    @Test fun appUpdateActionsCheckDownloadCancelAndHandOffInstallation() = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val state = mutableStateOf(AppUpdateState(checked = true, error = "Check service unavailable"))
        val uri = URI("https://github.com/xTreeRoot/MB/releases/tag/mb-android-v0.1.26")
        val release = AppRelease("mb-android-v0.1.26", "0.1.26", uri, "Update fixture", uri, 3, "a".repeat(64))
        var checks = 0; var downloads = 0; var cancelled = 0; var installs = 0
        try {
            withContext(Dispatchers.Main) {
                activity.setContent { MBTheme(false) { CompositionLocalProvider(LocalMBEnglish provides true) {
                    AppUpdateContent("0.1.25", state.value, check = { checks++; state.value = AppUpdateState(checked = true, release = release) },
                        download = { downloads++; state.value = state.value.copy(downloading = true, progress = 0.4f) },
                        install = { installs++ }, dismiss = { cancelled++; state.value = state.value.copy(downloading = false) })
                } } }
            }
            await("Check service unavailable")
            assertTrue(nodes("You're up to date").isEmpty())
            tap("Check Version"); await("Version 0.1.26 available"); assertEquals(1, checks)
            tap("Download Update"); await("Cancel Download"); assertEquals(1, downloads)
            tap("Cancel Download"); assertEquals(1, cancelled)
            withContext(Dispatchers.Main) { state.value = state.value.copy(file = File(app.cacheDir, "fixture.apk")) }
            tap("Install Update"); assertEquals(1, installs)
            screenshot("android-app-updates.png")
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        return allNodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
    }
    private suspend fun await(text: String) = withTimeout(5_000) { while (nodes(text).isEmpty()) delay(25) }
    private suspend fun tap(text: String) = withTimeout(5_000) {
        while (nodes(text).none { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }) delay(25)
    }
    private fun screenshot(name: String) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
}
