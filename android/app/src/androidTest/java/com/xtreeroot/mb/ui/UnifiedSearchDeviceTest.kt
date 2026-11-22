package com.xtreeroot.mb.ui

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.PlaybackMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedSearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val ids = listOf("search-fixture-a", "search-fixture-b")

    private suspend fun fixture(test: suspend (MainActivity) -> Unit) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val preferences = app.settings.values.value
        var activity: MainActivity? = null
        try {
            ids.forEachIndexed { index, id ->
                val name = if (index == 0) "First" else "Second"
                val candidate = app.plugins.prepareArchive { file -> ZipOutputStream(file.outputStream()).use { zip ->
                    val manifest = JSONObject().put("id", id).put("name", name).put("version", "1.0.0").put("main", "index.js")
                        .put("navigation", org.json.JSONArray("""[{"title":"Daily","page":"daily"}]"""))
                    val code = """
                        const song = {id:"song",name:"$name song",artists:[{id:"artist",name:"Artist"}],duration:180000};
                        export function home(){return {sections:[{title:"Daily",tracks:[song],morePage:"daily"}]};}
                        export function browse(){return {sections:[{title:"Daily",tracks:[{...song,name:"$name detail"}]}]};}
                        export function search(){return {tracks:[{...song,name:"$name music"}],more:false};}
                        export function searchVideos(){return {tracks:[{...song,id:"video",name:"$name video"}],more:false};}
                    """.trimIndent()
                    for ((entry, content) in listOf("plugin.json" to manifest.toString(), "index.js" to code)) {
                        zip.putNextEntry(ZipEntry(entry)); zip.write(content.toByteArray()); zip.closeEntry()
                    }
                } }
                app.plugins.install(candidate)
            }
            val installed = app.plugins.installed().filter { it.id in ids }
            installed.forEach { app.plugins.rename(it, "") }
            app.plugins.move(ids.first(), -1)
            app.settings.update { it.copy(selectedSourceId = ids.first(), language = AppLanguage.English, playbackMode = PlaybackMode.Music) }
            app.refreshPlugins()
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("First")
            test(activity)
        } catch (error: Throwable) {
            screenshot("android-search-failure.png")
            File(app.cacheDir, "android-search-failure-nodes.txt").writeText(allNodes().joinToString("\n") { "${it.className} | ${it.text} | ${it.contentDescription} | editable=${it.isEditable}" })
            throw error
        } finally {
            activity?.let { withContext(Dispatchers.Main) { it.finish() } }
            app.resourceBookmarks.tracks.value.filter { it.sourceId in ids }.forEach { app.resourceBookmarks.remove(it.id) }
            app.plugins.installed().filter { it.id in ids }.forEach { app.plugins.remove(it) }
            app.settings.update { preferences }; app.refreshPlugins()
        }
    }

    @Test fun recentSearchCanRemoveOneEntryAndClearAllAndMagnetImportsDirectly() = runBlocking {
        fixture {
            val prefs = app.getSharedPreferences("browse", 0)
            val previous = prefs.getString("search_history", "[]")
            try {
                prefs.edit().putString("search_history", "[\"First query\",\"Keep query\"]").commit()
                tap("Search"); await("First query"); await("Keep query")
                tap("Remove search First query")
                withTimeout(5_000) { while (prefs.getString("search_history", "")?.contains("First query") == true) delay(25) }
                await("Keep query"); tap("Clear All")
                withTimeout(5_000) { while (prefs.getString("search_history", "") != "[]") delay(25) }
                tap("First ▾"); tap("Magnet Search")
                setText(nodes("Search songs or videos").first(), "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Direct%20resource")
                tap("Search"); await("Direct resource")
                screenshot("android-magnet-direct-result.png")
            } finally { prefs.edit().putString("search_history", previous).commit() }
        }
    }

    @Test fun tappingAResourceOpensItsMusicPlayerImmediately() = runBlocking {
        fixture {
            await("First song"); tap("First song")
            await("Now Playing")
        }
    }

    @Test fun searchDropdownStaysBelowInputAndSavesCrossPlatformResults() = runBlocking {
        fixture {
            tap("Search"); await("First ▾")
            tap("First ▾"); await("All Platforms")
            val input = Rect(); nodes("Search songs or videos").first().getBoundsInScreen(input)
            val choice = Rect(); nodes("All Platforms").first().getBoundsInScreen(choice)
            assertTrue("Options must not cover the search input", choice.top >= input.bottom)
            screenshot("android-search-platforms.png")
            tap("All Platforms"); await("All Platforms ▾")
            setText(nodes("Search songs or videos").first(), "music")
            tap("Search")
            await("First music"); await("Second music"); await("First video")
            screenshot("android-search-results.png")
            tap("Actions for First music"); tap("Save to Library")
            withTimeout(5_000) { while (app.resourceBookmarks.tracks.value.none { it.title == "First music" }) delay(25) }
            tap("Library"); await("Saved Resources"); await("First music")
            assertEquals("music", app.resourceBookmarks.tracks.value.first { it.title == "First music" }.resourceType)
        }
    }

    @Test fun renameOrderSwipeAndModeTogglePropagate() = runBlocking {
        fixture {
            tap("More Actions"); tap("Settings"); tap("Plugins"); await("First")
            val first = android.graphics.Rect(); nodes("First").first().getBoundsInScreen(first)
            val down = SystemClock.uptimeMillis()
            val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, first.centerX().toFloat(), first.centerY().toFloat(), 0)
            instrumentation.sendPointerSync(press); press.recycle()
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
            val release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, first.centerX().toFloat(), first.centerY().toFloat(), 0)
            instrumentation.sendPointerSync(release); release.recycle(); await("Local Name")
            val field = allNodes().first { it.className?.toString() == "android.widget.EditText" }
            setText(field, "Local First"); tap("Save"); await("Local First")
            tap("Edit"); await("Reorder Second")
            val handle = Rect(); nodes("Reorder Second").first().getBoundsInScreen(handle)
            val target = Rect(); nodes("Reorder Local First").first().getBoundsInScreen(target)
            val dragStart = SystemClock.uptimeMillis()
            for (step in 0..24) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(dragStart, SystemClock.uptimeMillis(), action,
                    handle.centerX().toFloat(), handle.centerY() + (target.centerY() - handle.centerY()) * step / 24f, 0)
                instrumentation.sendPointerSync(event); event.recycle(); SystemClock.sleep(20)
            }
            awaitState("拖动后插件顺序未更新") { app.plugins.installed().filter { it.id in ids }.map { it.id } == ids.reversed() }
            tap("Done"); await("Edit")
            tap("Back"); await("Appearance")
            tap("Done"); await("More Actions"); await("First song")
            swipeRight()
            awaitState("发现页横滑后没有切换平台") { app.settings.values.value.selectedSourceId == ids.last() }
            tap("Music Mode")
            awaitState("点击音乐模式后没有切换到视频模式") { app.settings.values.value.playbackMode == PlaybackMode.Video }
            await("Video Mode"); screenshot("android-discovery-platforms.png")
            tap("Search"); await("Second ▾")
            tap("Second ▾"); await("Local First")
        }
    }

    @Test fun discoveryMoreOpensThePluginSectionAndAccountToolsOpenTheirPages() = runBlocking {
        fixture {
            await("First song")
            screenshot("android-discovery-more-aligned.png")
            tap("See More Daily")
            await("First detail")
            tap("Back")
            tap("Me")
            await("My Music")
            screenshot("android-account-root-no-hero.png")
            tap("Settings")
            await("Appearance")
            tap("Done")
            tap("Plugins")
            await("Edit")
            tap("Back")
            await("My Music")
        }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        // Android 9 在菜单关闭后可能没有 activeWindow，复用账号测试的应用窗口回退。
        val automation = instrumentation.uiAutomation
        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }.filter { it.packageName == app.packageName }
        return roots.flatMap(::visit)
    }
    private fun nodes(text: String) = allNodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private suspend fun await(text: String) = withTimeout(8_000) { while (nodes(text).isEmpty()) delay(25) }
    private fun click(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    private suspend fun tap(text: String) {
        try { withTimeout(5_000) { while (nodes(text).none(::click)) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Could not click: $text", error) }
    }
    private suspend fun awaitState(message: String, condition: suspend () -> Boolean) {
        try { withTimeout(5_000) { while (!condition()) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(message, error) }
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val editable = node.takeIf { it.isEditable } ?: allNodes().first { it.isEditable }
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun swipeRight() {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val metrics = app.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, metrics.widthPixels * (0.15f + step / 20f * 0.7f), metrics.heightPixels * 0.17f, 0)
            instrumentation.sendPointerSync(event); event.recycle(); SystemClock.sleep(16)
        }
    }
    private fun screenshot(name: String) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }
}
