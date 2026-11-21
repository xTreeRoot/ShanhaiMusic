package com.xtreeroot.mb.ui

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCollection
import com.xtreeroot.mb.plugin.PluginSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountPageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun pluginAccountDestinationsKeepTheirProfileAndCollectionRequests() = runBlocking {
        val id = "account-page-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { pluginZip(id).inputStream() })
        try {
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val navigator = PluginCatalogNavigator()
            val tools = Channel<String>(Channel.UNLIMITED)
            withPage(source, dark = true, content = { model ->
                val state by model.account.collectAsStateWithLifecycle()
                PluginPersonalContent(source, navigator, model, state,
                    openSettings = { tools.trySend("settings") }, openPlugins = { tools.trySend("plugins") }, openService = { tools.trySend("service") })
            }) {
                awaitNode("Fixture Listener")
                awaitNode("Source Playlists")
                capture("android-account-plugin-dark")
                tap("My Profile")
                assertRequest(PluginCatalogRequest.Browse("personal", "My Profile"), navigator)
                tap("Source Playlists")
                assertRequest(PluginCatalogRequest.Collection(PluginCollection.Playlists, "Source Playlists"), navigator)
                tap("Settings")
                assertEquals("settings", withTimeout(5_000) { tools.receive() })
                tap("Plugins")
                assertEquals("plugins", withTimeout(5_000) { tools.receive() })
                tap("Shanhai Service")
                assertEquals("service", withTimeout(5_000) { tools.receive() })
            }
        } finally {
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
        }
    }

    private suspend fun assertRequest(expected: PluginCatalogRequest, navigator: PluginCatalogNavigator) {
        val actual = withTimeoutOrNull(5_000) {
            var request: PluginCatalogRequest?
            do {
                delay(25)
                request = withContext(Dispatchers.Main) { (navigator.current as? CatalogDestination.Content)?.request }
            } while (request == null)
            withContext(Dispatchers.Main) { navigator.back() }
            request
        }
        assertEquals(expected, actual)
    }

    private suspend fun withPage(source: AccountSource, dark: Boolean, content: @Composable (AccountModel) -> Unit,
                                 run: suspend () -> Unit) {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ViewModelStore()
        try {
            withContext(Dispatchers.Main) {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AccountModel::class.java]
                model.activate(source)
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(dark) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { content(model) }
                        }
                    }
                }
            }
            run()
        } finally {
            withContext(Dispatchers.Main) { store.clear(); activity.finish() }
        }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::find) }
        }
        // Android 9 的瞬时登录弹窗关闭后 activeWindow 可暂时为空，从本应用窗口读取实际页面。
        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == app.packageName }
        return roots.firstNotNullOfOrNull(::find)
    }

    private suspend fun awaitNode(text: String) {
        val visible = withTimeoutOrNull(5_000) {
            while (node(text) == null) delay(25)
            true
        }
        fun describe(node: AccessibilityNodeInfo): List<String> = listOf("${node.text}/${node.contentDescription} enabled=${node.isEnabled}") +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::describe).orEmpty() }
        if (visible != true) capture("android-account-failure")
        assertEquals("Expected to see $text. ${if (visible != true) instrumentation.uiAutomation.rootInActiveWindow?.let(::describe) else ""}", true, visible)
    }

    private suspend fun tap(text: String) {
        scrollTo(text)
        awaitNode(text)
        val button = generateSequence(node(text)) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertEquals("Expected to tap $text", true, button?.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun scrollTo(text: String) {
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = node.takeIf { it.isScrollable } ?:
            (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::scrollable) }
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(8) {
                if (node(text)?.isVisibleToUser == true) return
                val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) +
                    instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == app.packageName }
                roots.firstNotNullOfOrNull(::scrollable)?.performAction(direction)
                delay(100)
            }
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("accountScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }

    private fun pluginZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id","name":"Fixture Music"}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                export function account() { return {id:'listener', name:'Fixture Listener', page:'personal'}; }
                export function playlists() { return [{id:'pl',name:'List',mine:true}]; }
                export function liked() { return []; }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
