package com.xtreeroot.mb.ui

import com.xtreeroot.mb.plugin.PluginCatalogPage
import com.xtreeroot.mb.plugin.PluginCatalogSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogPagerTest {
    @Test fun returningToTheHomeTabRestoresTheHomeRequestInsteadOfABrowseSubset() = runBlocking {
        val destination = CatalogDestination.Content(com.xtreeroot.mb.plugin.PluginCatalogRequest.Home)
        destination.pager.load { page("Home", null).copy(selectedTab = "all") }
        destination.rememberTabs(destination.pager.state.value.page)
        destination.selectFilter(com.xtreeroot.mb.plugin.PluginCatalogRequest.Browse("music", "Music"))
        destination.pager.load { page("Music", null).copy(selectedTab = "music") }
        destination.rememberTabs(destination.pager.state.value.page)
        destination.selectFilter(com.xtreeroot.mb.plugin.PluginCatalogRequest.Browse("all", "All"))
        assertEquals(com.xtreeroot.mb.plugin.PluginCatalogRequest.Home, destination.currentRequest)
        assertNull(destination.filter)
        assertNull(destination.pager.state.value.page)
        assertTrue(destination.pager.state.value.needsReload)
    }

    @Test fun filterChangeClearsOldPageAndRejectsItsLateResponse() = runBlocking {
        val destination = CatalogDestination.Content(com.xtreeroot.mb.plugin.PluginCatalogRequest.Home)
        destination.pager.load { page("Original", 1) }
        val entered = CompletableDeferred<Unit>()
        val late = CompletableDeferred<PluginCatalogPage>()
        val loading = launch { destination.pager.load(append = true) { entered.complete(Unit); late.await() } }
        entered.await()
        val filter = com.xtreeroot.mb.plugin.PluginCatalogRequest.Browse("music", "Music")
        destination.selectFilter(filter)
        assertNull(destination.pager.state.value.page)
        assertEquals(filter, destination.currentRequest)
        destination.pager.load { offset -> assertEquals(0, offset); page("Music", null) }
        late.complete(page("Old", null)); loading.join()
        assertEquals("Music", destination.pager.state.value.page?.sections?.single()?.title)
    }

    @Test fun refreshDuringPendingRequestRejectsItsLateResultAndKeepsNewestContent() = runBlocking {
        val pager = PluginCatalogPager()
        pager.load { page("Original", 3) }
        val entered = CompletableDeferred<Unit>()
        val old = CompletableDeferred<PluginCatalogPage>()
        val loading = launch { pager.load(append = true) { entered.complete(Unit); old.await() } }
        entered.await()
        var invalidated = false
        pager.refresh({ invalidated = true }) { offset ->
            assertTrue(invalidated)
            assertEquals(0, offset)
            assertTrue(pager.state.value.busy)
            page("Refreshed", null)
        }
        old.complete(page("Stale", 6)); loading.join()
        assertEquals("Refreshed", pager.state.value.page?.sections?.single()?.title)
        assertFalse(pager.state.value.busy)
    }

    @Test fun invalidationDuringBusyLoadIsNotLostAndCancelledOldWorkCannotRestoreStaleState() = runBlocking {
        val pager = PluginCatalogPager()
        val entered = CompletableDeferred<Unit>()
        val loading = launch { pager.load { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        pager.invalidate()
        pager.load { page("Latest", null) }
        loading.cancelAndJoin()
        assertEquals("Latest", pager.state.value.page?.sections?.single()?.title)
        assertFalse(pager.state.value.needsReload)
    }
    @Test fun refreshRetainsLoadedPagesUntilTheReplacementSucceedsAndRestartsAtZero() = runBlocking {
        val pager = PluginCatalogPager()
        pager.load { page("First", 3) }
        pager.load(append = true) { page("Second", 6) }
        val paginated = pager.state.value.page
        pager.invalidate()
        assertTrue(pager.state.value.needsReload)
        assertEquals(paginated, pager.state.value.page)

        pager.load { offset ->
            assertEquals(0, offset)
            assertTrue(pager.state.value.busy)
            assertFalse(pager.state.value.appending)
            assertEquals(paginated, pager.state.value.page)
            error("offline")
        }
        assertEquals(paginated, pager.state.value.page)
        assertEquals("offline", pager.state.value.error)
        assertFalse(pager.state.value.needsReload)
        assertFalse(pager.state.value.appendRetry)

        val replacement = page("New", 2)
        pager.invalidate()
        pager.load { offset -> assertEquals(0, offset); replacement }
        assertEquals(replacement, pager.state.value.page)
        assertFalse(pager.state.value.needsReload)
    }

    @Test fun failedAppendRetriesAtSameOffsetAndDoesNotDropTheCurrentPage() = runBlocking {
        val pager = PluginCatalogPager()
        val first = page("First", 3)
        val second = page("Second", null)
        pager.load { offset -> assertEquals(0, offset); first }
        pager.load(append = true) { offset -> assertEquals(3, offset); error("offline") }
        assertEquals(first, pager.state.value.page)
        assertTrue(pager.state.value.appendRetry)
        assertEquals("offline", pager.state.value.error)
        pager.load(append = true) { offset -> assertEquals(3, offset); second }
        assertEquals(first.sections + second.sections, pager.state.value.page?.sections)
        assertNull(pager.state.value.error)
        assertFalse(pager.state.value.appendRetry)
    }

    @Test fun cancelledRetryRestoresErrorAndAStalledCursorStopsPaging() = runBlocking {
        val pager = PluginCatalogPager()
        pager.load { page("First", 3) }
        pager.load(append = true) { error("offline") }
        val previous = pager.state.value
        val started = CompletableDeferred<Unit>()
        val retry = launch {
            pager.load(append = true) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        retry.cancelAndJoin()
        assertEquals(previous, pager.state.value)
        pager.load(append = true) { offset -> page("", offset) }
        assertNull(pager.state.value.page?.nextOffset)
        pager.load(append = true) { throw AssertionError("A stalled page should not trigger another request") }
    }

    private fun page(title: String, next: Int?) = PluginCatalogPage("Catalog", "", "", listOf(
        PluginCatalogSection(title, emptyList(), emptyList(), emptyList(), null, "", false),
    ), next)
}
