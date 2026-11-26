package com.xtreeroot.mb.plugin

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginCommunityTest {
    private fun plugin(repo: String) = CommunityPlugin(repo, repo, "", null, 0)

    @Test fun searchAndPaginationStayInsideMaintainedList() {
        assertTrue(PluginCommunity.catalog.startsWith("https://github.com/stars/xTreeRoot/lists/"))
        val catalog = List(32) { plugin("owner/plugin-$it") }
        assertEquals(30, PluginCommunity.page(catalog, "", 1).plugins.size)
        assertTrue(PluginCommunity.page(catalog, "", 1).more)
        assertEquals(2, PluginCommunity.page(catalog, "", 2).plugins.size)
        assertFalse(PluginCommunity.page(catalog, "", 2).more)
        assertEquals(listOf("owner/plugin-31"), PluginCommunity.page(catalog, "PLUGIN-31", 1).plugins.map { it.repository })
        assertTrue(PluginCommunity.page(catalog, "outside-directory", 1).plugins.isEmpty())
    }

    @Test fun paginationRetriesAndDeduplicatesWithoutLosingTheFirstPage() = runTest {
        var fail = true
        val model = PluginCommunityPager(this) { _, page ->
            if (page == 2 && fail) { fail = false; error("offline") }
            PluginCommunityPage(if (page == 1) listOf(plugin("o/one")) else listOf(plugin("O/ONE"), plugin("o/two")), page == 1)
        }
        model.search("radio"); runCurrent()
        model.loadMore(); runCurrent()
        assertEquals(1, model.state.value.plugins.size)
        assertNotNull(model.state.value.error)
        model.loadMore(); runCurrent()
        assertEquals(listOf("o/one", "o/two"), model.state.value.plugins.map { it.repository })
        assertFalse(model.state.value.more)
    }

    @Test fun aReplacedSearchCannotOverwriteNewResults() = runTest {
        val old = CompletableDeferred<PluginCommunityPage>()
        val model = PluginCommunityPager(this) { query, _ ->
            if (query == "old") old.await() else PluginCommunityPage(listOf(plugin("o/new")), false)
        }
        model.search("old"); runCurrent()
        model.search("new"); runCurrent()
        old.complete(PluginCommunityPage(listOf(plugin("o/old")), false)); runCurrent()
        assertEquals(listOf("o/new"), model.state.value.plugins.map { it.repository })
    }

    @Test fun iconPathsCannotEscapeThePluginDirectory() {
        val root = Files.createTempDirectory("plugin-icons").toFile()
        try {
            for (icon in listOf("art/icon.png", "../outside.png", "/outside.png", "bad\\icon.png")) {
                val manifest = PluginManifest.parse(JSONObject().put("id", "fixture").put("icon", icon))
                val plugin = InstalledPlugin(manifest, UUID.randomUUID(), root, root, null, true)
                if (icon == "art/icon.png") assertEquals(File(root, icon).canonicalFile.toURI().toString(), plugin.iconAddress())
                else assertNull(plugin.iconAddress())
            }
        } finally { root.deleteRecursively() }
    }
}
