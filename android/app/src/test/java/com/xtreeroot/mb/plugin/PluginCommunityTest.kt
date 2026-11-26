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

    @Test fun requestEncodesQueryAndStopsAtGitHubSearchLimit() {
        val address = PluginCommunity.address(" 中文 & radio ", 0).toString().toHttpUrl()
        assertEquals("中文 & radio topic:mygo-music-plugin archived:false", address.queryParameter("q"))
        assertEquals("1", address.queryParameter("page"))
        val item = JSONObject().put("full_name", "owner/radio").put("name", "mygo-music-plugin-radio").put("description", JSONObject.NULL)
        val json = JSONObject().put("total_count", 5000).put("items", JSONArray(List(30) { item }))
        assertTrue(PluginCommunity.decode(json, 33).more)
        assertFalse(PluginCommunity.decode(json, 34).more)
        assertEquals("radio", PluginCommunity.decode(json, 1).plugins.first().name)
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
