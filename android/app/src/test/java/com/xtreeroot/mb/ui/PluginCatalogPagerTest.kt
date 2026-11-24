package com.xtreeroot.mb.ui

import com.xtreeroot.mb.plugin.PluginCatalogPage
import com.xtreeroot.mb.plugin.PluginCatalogSection
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCatalogLink
import com.xtreeroot.mb.plugin.continuing
import com.xtreeroot.mb.music.Track
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
        assertEquals(first.sections, pager.state.value.page?.sections)
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

    @Test fun legacyMoreRoutesAppendInPlaceDeduplicateAndStopCycles() = runBlocking {
        val pager = PluginCatalogPager()
        val first = Track("fixture", "one", "First", "", durationMs = 1000)
        val second = first.copy(sourceTrackId = "two", title = "Second")
        val start = PluginCatalogRequest.Browse("start", "")
        val rest = PluginCatalogRequest.Browse("rest", "")
        fun response(tracks: List<Track>, route: PluginCatalogRequest.Browse) = page("Items", null).let { value ->
            value.copy(sections = listOf(value.sections.single().copy(tracks = tracks, more = PluginCatalogLink("", "", route))))
        }
        pager.load { response(listOf(first), rest).continuing(start) }
        assertEquals(rest, pager.state.value.page?.nextPage)
        assertNull(pager.state.value.page?.sections?.single()?.more)
        pager.load(true) { offset -> assertEquals(0, offset); response(listOf(first, second), rest).continuing(rest) }
        assertEquals(listOf(first, second), pager.state.value.page?.sections?.single()?.tracks)
        assertNull(pager.state.value.page?.nextPage)
        pager.load(true) { throw AssertionError("A self-route must not load again") }
    }

    @Test fun offsetPagesKeepOneHeadingAndContinueFromTwentyToTwentyOne() = runBlocking {
        val pager = PluginCatalogPager()
        val tracks = (1..23).map { Track("fixture", "$it", "作品 $it", "", durationMs = 1000) }
        fun response(batch: List<Track>, next: Int?, title: String = "视频") = page(title, next).let { value ->
            value.copy(sections = listOf(value.sections.single().copy(tracks = batch)))
        }
        pager.load { response(tracks.take(20), 20) }
        pager.load(true) { offset -> assertEquals(20, offset); response(tracks.subList(19, 22), 23) }
        assertEquals("21", pager.state.value.page?.sections?.single()?.tracks?.get(20)?.sourceTrackId)
        pager.load(true) { offset -> assertEquals(23, offset); response(tracks.takeLast(2), 25, "第三页") }
        pager.load(true) { offset -> assertEquals(25, offset); response(tracks.takeLast(2), 27) }
        assertEquals("视频", pager.state.value.page?.sections?.single()?.title)
        assertEquals(tracks, pager.state.value.page?.sections?.single()?.tracks)
        assertNull(pager.state.value.page?.nextOffset)
    }

    @Test fun offsetPagesKeepDifferentGroupsEvenWhenTheirHeadingsOrTracksMatch() = runBlocking {
        val pager = PluginCatalogPager()
        val first = Track("fixture", "one", "First", "", durationMs = 1000)
        val second = first.copy(sourceTrackId = "two", title = "Second")
        val original = page("Videos", 1)
        val section = original.sections.single().copy(tracks = listOf(first))
        val artists = section.copy(title = "Artists", tracks = emptyList())
        val grid = section.copy(videos = true)
        pager.load { original.copy(sections = listOf(section, artists)) }
        pager.load(true) { original.copy(sections = listOf(grid, section.copy(tracks = listOf(first, second))), nextOffset = null) }
        assertEquals(listOf(section.copy(tracks = listOf(first, second)), artists, grid), pager.state.value.page?.sections)
    }

    private fun page(title: String, next: Int?) = PluginCatalogPage("Catalog", "", "", listOf(
        PluginCatalogSection(title, emptyList(), emptyList(), emptyList(), null, "", false),
    ), next)
}
