package com.xtreeroot.mb.ui

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.xtreeroot.mb.music.Track
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun catalogFooterLoadsOnlyWhenVisibleAndSurvivesLoadingIndicatorReplacement() = runBlocking {
        val pager = PluginCatalogPager()
        fun page(count: Int, next: Int?) = com.xtreeroot.mb.plugin.PluginCatalogPage("Fixture", "", "",
            listOf(com.xtreeroot.mb.plugin.PluginCatalogSection("Items", (0 until count).map {
                Track("fixture", "$it", "Item $it", "", durationMs = 1000)
            }, emptyList(), emptyList(), null, "", false)), next)
        pager.load { page(40, 40) }
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val source = object : AccountSource {
            override val id = "pagination-fixture"
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun account(): SourceAccount? = null
            override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount = error("unused")
            override suspend fun signOut() {}
        }
        withPage(source, dark = false, content = {
            val state by pager.state.collectAsStateWithLifecycle()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.foundation.lazy.LazyColumn {
                state.page?.sections?.flatMap { it.tracks }?.forEach { track -> item {
                    androidx.compose.material3.Text(track.title, Modifier.fillMaxWidth().padding(20.dp))
                } }
                if (state.busy) item { Loading() }
                else state.page?.nextOffset?.let { next -> item {
                    PaginationTrigger(next) { scope.launch { pager.load(true) { offset ->
                        assertEquals(40, offset); requests.incrementAndGet(); delay(350)
                        page(1, null).copy(sections = listOf(page(1, null).sections.single().copy(tracks = listOf(Track("fixture", "next", "Next item", "", durationMs = 1000)))))
                    } } }
                } }
            }
        }) {
            awaitNode("Item 0")
            instrumentation.waitForIdleSync()
            assertEquals("No eager request before reaching the end", 0, requests.get())
            scrollTo("Item 39")
            // 末项仍可能只露出一部分，继续滚动到页尾哨兵真正进入可见范围。
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fun list(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = node.takeIf { it.isScrollable }
                ?: (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::list) }
            root?.let(::list)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            awaitNode("Next item")
            assertEquals(1, requests.get())
            assertEquals(null, pager.state.value.page?.nextOffset)
            capture("android-pagination-loaded-in-place")
        }
    }

    @Test fun pluginReviewShowsNotesCollapsesPermissionsAndOpensGroupedSettings() = runBlocking {
        val id = "manager-ui-${UUID.randomUUID()}"
        val archive = File(app.cacheDir, "$id.zip").apply { writeBytes(pluginZip(id)) }
        val store = ViewModelStore()
        val manager = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PluginManagerModel::class.java]
        }
        suspend fun awaitState(description: String, ready: suspend () -> Boolean) {
            val completed = withTimeoutOrNull(5_000) { while (!ready()) delay(25); true }
            if (completed != true) capture("android-plugin-state-failure")
            assertEquals("$description: ${manager.state.value}", true, completed)
        }
        val source = object : AccountSource {
            override val id = "manager-fixture"
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun account(): SourceAccount? = null
            override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount = error("unused")
            override suspend fun signOut() {}
        }
        try {
            withPage(source, dark = false, skin = true, content = {
                val state by manager.state.collectAsStateWithLifecycle()
                PluginsView(manager, state.copy(pending = state.pending?.copy(releaseNotes = "Fixed recommendation refresh")), {}, {})
            }) {
                withContext(Dispatchers.Main) { manager.prepareZip(android.net.Uri.fromFile(archive)) }
                withTimeout(5_000) { while (manager.state.value.pending == null && manager.state.value.check !is PluginCheck.Failed) delay(25) }
                assertEquals(null, manager.state.value.error)
                assertEquals(null, manager.state.value.check)
                awaitNode("Fixed recommendation refresh")
                assertNull(node("api.example.test"))
                tap("Network permissions"); awaitNode("api.example.test")
                tap("Network permissions"); awaitState("Permissions collapse") { node("api.example.test") == null }
                capture("android-plugin-review-notes-collapsed-permissions")
                tap("Install")
                awaitState("Installation completes") { manager.state.value.pending == null }
                awaitNode("Fixture Music")
                capture("android-plugin-grouped-overview")
                tap("Fixture Music"); awaitNode("Local Name")
                capture("android-plugin-grouped-settings")
                tap("Local Name"); awaitNode("Save")
                fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = node.takeIf { it.isEditable }
                    ?: (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::editable) }
                val field = withTimeout(5_000) {
                    var found: AccessibilityNodeInfo? = null
                    while (found == null) {
                        val roots = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) + instrumentation.uiAutomation.windows.mapNotNull { it.root }
                        found = roots.firstNotNullOfOrNull(::editable)
                        if (found == null) delay(25)
                    }
                    found
                }
                org.junit.Assert.assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Local Fixture")
                }))
                tap("Save")
                awaitState("Rename persists") { app.plugins.installed().single { it.id == id }.displayName == "Local Fixture" }
                awaitNode("Local Fixture")
                assertEquals("Local Fixture", app.plugins.installed().single { it.id == id }.displayName)
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.plugins.installed().firstOrNull { it.id == id }?.let { app.plugins.remove(it); it.dataDirectory.deleteRecursively() }
            app.refreshPlugins(); archive.delete()
        }
    }

    @Test fun catalogFilterTabsReplaceContentWithoutPushingNavigation() = runBlocking {
        val id = "filter-ui-${UUID.randomUUID()}"
        val data = ByteArrayOutputStream().apply { ZipOutputStream(this).use { zip ->
            for ((name, text) in mapOf("plugin.json" to """{"id":"$id","name":"Filter Fixture"}""",
                "index.js" to """const tabs=[{title:'All',page:'all'},{title:'Music',page:'music'},{title:'Games',page:'games'}];
                export function home(){return {tabs,selectedTab:'all',sections:[{title:'Home',tracks:[{id:'all',name:'All item'}]}]};}
                export function browse({page,offset}){return {tabs,selectedTab:page,sections:[{title:page,tracks:[{id:page+offset,name:page+' item '+offset}]}],more:offset===0};}""")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.encodeToByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val plugin = app.plugins.install(app.plugins.prepareZip { data.inputStream() })
        try {
            app.plugins.setEnabled(plugin, true); app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val navigator = PluginCatalogNavigator()
            withPage(source, dark = false, content = {
                PluginCatalogBrowser(source, navigator.home, navigator, { _, _ -> }, { _, _, _ -> })
            }) {
                awaitNode("All item"); tap("Music"); awaitNode("music item 0")
                assertNull(navigator.current); assertNull(node("All item"))
                assertEquals("music", withContext(Dispatchers.Main) { navigator.home.filter?.page })
                capture("android-catalog-filter-tabs")
                tap("Games"); awaitNode("games item 0"); assertNull(node("music item 0"))
                tap("All"); awaitNode("All item"); assertNull(node("games item 0")); assertNull(navigator.home.filter)
                tap("Music"); awaitNode("music item 0"); tap("All"); awaitNode("All item")
            }
        } finally { app.plugins.remove(plugin); app.refreshPlugins(); plugin.dataDirectory.deleteRecursively() }
    }

    @Test fun pullRefreshReplacesBothCardRowsAndKeepsTitleAndMoreAtOppositeSides() = runBlocking {
        val id = "two-row-refresh-${UUID.randomUUID()}"
        val archive = ByteArrayOutputStream().apply { ZipOutputStream(this).use { zip ->
            val script = """
                let revision=0, snapshot;
                export function home({refresh}={}) {
                    if (!snapshot || refresh) {
                        revision++;
                        snapshot={sections:[{title:'Shorts',morePage:'shorts',tracks:Array.from({length:6},(_,index)=>({id:revision+'-'+index,name:'Short '+revision+'-'+index,video:{width:9,height:16,short:true}}))}]};
                    }
                    return snapshot;
                }
            """.trimIndent()
            for ((name, text) in mapOf("plugin.json" to """{"id":"$id","name":"Two Row Fixture"}""", "index.js" to script)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.encodeToByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val plugin = app.plugins.install(app.plugins.prepareZip { archive.inputStream() })
        try {
            app.plugins.setEnabled(plugin, true); app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val navigator = PluginCatalogNavigator()
            withPage(source, dark = false, content = {
                PluginCatalogBrowser(source, navigator.home, navigator, { _, _ -> }, { _, _, _ -> })
            }) {
                awaitNode("Short 1-0"); awaitNode("Short 1-1")
                val top = android.graphics.Rect(); node("Short 1-0")!!.getBoundsInScreen(top)
                val bottom = android.graphics.Rect(); node("Short 1-2")!!.getBoundsInScreen(bottom)
                assertTrue("Distinct resources must appear in two card rows", bottom.top > top.bottom)
                val heading = android.graphics.Rect(); node("Shorts")!!.getBoundsInScreen(heading)
                val more = android.graphics.Rect(); node("More")!!.getBoundsInScreen(more)
                assertTrue("More must be right of the title", more.left > heading.right)
                assertTrue("Title and More must share one line", kotlin.math.abs(more.centerY()-heading.centerY()) < heading.height())
                pullDiscoveryDown()
                awaitNode("Short 2-0"); awaitNode("Short 2-1")
                assertNull(node("Short 1-0")); assertNull(node("Short 1-1"))
                capture("android-pull-refresh-two-rows")
            }
        } finally { app.plugins.remove(plugin); app.refreshPlugins(); plugin.dataDirectory.deleteRecursively() }
    }

    @Test fun followingLinksKeepAvatarAndWrapDescriptionAndAppendOnScroll() = runBlocking {
        val id = "following-layout-${UUID.randomUUID()}"
        val archive = ByteArrayOutputStream().apply { ZipOutputStream(this).use { zip ->
            val script = """
                export function browse({page}) {
                    if (page.startsWith('profile:')) return {title:'Creator Profile',sections:[]};
                    const start=page==='people'?1:19;
                    return {title:'Following',sections:[{links:Array.from({length:start===1?18:3},(_,i)=>({title:'Followed Creator '+(start+i),subtitle:'A complete introduction that uses the remaining row width and wraps onto additional lines instead of being truncated after two lines.',page:'profile:'+(start+i),imageUrl:'https://avatar.example.test/'+(start+i)+'.png'})),morePage:start===1?'people:2':undefined}]};
                }
                export function playlist(){return {tracks:[{id:'first',name:'Playlist First'},{id:'second',name:'Playlist Second'}]};}
            """.trimIndent()
            for ((name, text) in mapOf("plugin.json" to """{"id":"$id","name":"Following Fixture"}""", "index.js" to script)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.encodeToByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val plugin = app.plugins.install(app.plugins.prepareZip { archive.inputStream() })
        try {
            app.plugins.setEnabled(plugin, true); app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val first = source.catalog.load(PluginCatalogRequest.Browse("people", "Following"))
            assertEquals("https://avatar.example.test/1.png", first.sections.single().links.first().image?.url)
            val navigator = PluginCatalogNavigator()
            val destination = CatalogDestination.Content(PluginCatalogRequest.Browse("people", "Following"))
            withPage(source, dark = false, content = {
                PluginCatalogBrowser(source, destination, navigator, { _, _ -> }, { _, _, _ -> })
            }) {
                awaitNode("Followed Creator 1")
                assertNull(node("See More")); assertNull(node("Followed Creator 19"))
                capture("android-following-avatar-and-introduction")
                repeat(14) { if (node("Followed Creator 21") == null) swipeCatalogUp() }
                awaitNode("Followed Creator 21")
                capture("android-following-second-page")
                tap("Followed Creator 21")
                assertEquals(PluginCatalogRequest.Browse("profile:21", "Followed Creator 21"), (navigator.current as CatalogDestination.Content).request)
            }
            val playlist = CatalogDestination.Content(PluginCatalogRequest.Entity(com.xtreeroot.mb.plugin.PluginEntity(com.xtreeroot.mb.plugin.PluginEntityKind.Playlist, "list", "Playlist")))
            var selection: Pair<List<Track>, Track>? = null
            withPage(source, dark = false, content = {
                PluginCatalogBrowser(source, playlist, PluginCatalogNavigator(), { queue, track -> selection = queue to track }, { _, _, _ -> })
            }) {
                awaitNode("Playlist First"); assertNull(node("Play All"))
                tap("Playlist Second")
                assertEquals(listOf("Playlist First", "Playlist Second"), selection?.first?.map { it.title })
                assertEquals("Playlist Second", selection?.second?.title)
                capture("android-plugin-playlist-without-play-all")
            }
        } finally { app.plugins.remove(plugin); app.refreshPlugins(); plugin.dataDirectory.deleteRecursively() }
    }

    private fun swipeCatalogUp() {
        val metrics = app.resources.displayMetrics
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = if (step == 0) android.view.MotionEvent.ACTION_DOWN else if (step == 20) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
            val event = android.view.MotionEvent.obtain(down, down+step*15L, action, metrics.widthPixels*.6f, metrics.heightPixels*(.8f-.6f*step/20), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
    }

    private fun pullDiscoveryDown() {
        val metrics = app.resources.displayMetrics
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..24) {
            val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 24 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                metrics.widthPixels * .85f, metrics.heightPixels * (.13f + .66f * step / 24f), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { org.junit.Assert.assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            android.os.SystemClock.sleep(20)
        }
    }

    @Test fun pullRefreshRequestsNewHomeAndNavigationContentInsteadOfReusingCache() = runBlocking {
        val id = "refresh-ui-${UUID.randomUUID()}"
        val data = ByteArrayOutputStream().apply { ZipOutputStream(this).use { zip ->
            for ((name, text) in mapOf("plugin.json" to """{"id":"$id","name":"Refresh Fixture","navigation":[{"page":"extra","title":"Extra"}]}""",
                "index.js" to """let homes=0, extras=0, savedHome, savedExtra; export function home({refresh}={}){if(!savedHome || refresh) savedHome={sections:[{title:'Home '+(++homes),tracks:[{id:'home',name:'Home item'}]}]};return savedHome;} export function browse({refresh}={}){if(!savedExtra || refresh) savedExtra={sections:[{tracks:[{id:'extra',name:'Navigation '+(++extras)}]}]};return savedExtra;}""")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.encodeToByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val plugin = app.plugins.install(app.plugins.prepareZip { data.inputStream() })
        try {
            app.plugins.setEnabled(plugin, true); app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val navigator = PluginCatalogNavigator()
            withPage(source, dark = false, content = {
                PluginCatalogBrowser(source, navigator.home, navigator, { _, _ -> }, { _, _, _ -> })
            }) {
                awaitNode("Home 1"); awaitNode("Navigation 1")
                pullDiscoveryDown()
                awaitNode("Home 2"); awaitNode("Navigation 2")
                assertNull(node("Home 1")); assertNull(node("Navigation 1"))
                capture("android-pull-refresh-replaced-home-and-shelf")
            }
        } finally { app.plugins.remove(plugin); app.refreshPlugins(); plugin.dataDirectory.deleteRecursively() }
    }

    @Test fun videoQualityMenuPersistsSelectionForLaterVideosWithoutChangingAudioQuality() = runBlocking {
        val old = app.settings.values.value
        val source = object : AccountSource {
            override val id = "quality-ui"
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun account(): SourceAccount? = null
            override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount = error("unused")
            override suspend fun signOut() {}
        }
        try {
            app.settings.update { it.copy(videoQuality = "auto") }
            withPage(source, dark = true, content = {
                VideoQualityMenu(com.xtreeroot.mb.playback.VideoFrameState("fixture", loading = false, quality = "1080p", qualities = listOf(
                    com.xtreeroot.mb.music.VideoQualityOption("1080p", "1080p60"), com.xtreeroot.mb.music.VideoQualityOption("720p", "720p"))))
            }) {
                tap("Video Quality"); tap("720p")
                assertEquals("720p", app.settings.values.value.videoQuality)
                assertEquals("720p", com.xtreeroot.mb.preferences.AppPreferences.parse(app.settings.values.value.toJson()).videoQuality)
                assertEquals(old.quality, app.settings.values.value.quality)
                tap("Video Quality"); awaitNode("720p")
                capture("android-video-quality-remembered")
            }
        } finally { app.settings.update { old } }
    }

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
                assertNull(node("My Profile")); assertNull(node("Liked Songs"))
                tap("Channel")
                assertRequest(PluginCatalogRequest.Browse("personal", "Channel"), navigator)
                tap("Subscriptions")
                assertRequest(PluginCatalogRequest.Browse("subscriptions", "Subscriptions"), navigator)
                tap("Liked Videos")
                assertRequest(PluginCatalogRequest.Collection(PluginCollection.Liked, "Liked Videos"), navigator)
                tap("Music Hub")
                assertRequest(PluginCatalogRequest.Browse("music", "Music Hub"), navigator)
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

    private suspend fun withPage(source: AccountSource, dark: Boolean, skin: Boolean = false, content: @Composable (AccountModel) -> Unit,
                                 run: suspend () -> Unit) {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ViewModelStore()
        val background = if (skin) Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).also { bitmap ->
            val paint = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 320f, 640f,
                    intArrayOf(0xffe3efe1.toInt(), 0xffb4d5e8.toInt(), 0xffedcbb9.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            }
            android.graphics.Canvas(bitmap).drawRect(0f, 0f, 320f, 640f, paint)
        } else null
        try {
            withContext(Dispatchers.Main) {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AccountModel::class.java]
                model.activate(source)
                activity.setContent {
                    com.xtreeroot.mb.ui.components.MBTheme(dark) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            AppearanceBackdrop(background, dark, opacity = 1f) { color ->
                                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = color) { content(model) }
                            }
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
            zip.write("""{"id":"$id","name":"Fixture Music","permissions":{"network":["api.example.test"]},"navigation":[{"page":"subscriptions","title":"Subscriptions","placement":"personal"},{"page":"personal","title":"Channel","placement":"personal"},{"builtin":"liked","title":"Liked Videos","placement":"personal"},{"page":"music","title":"Music Hub","placement":"personal"}]}""".encodeToByteArray())
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
