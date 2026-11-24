package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.LibraryPage
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPagesTest {
    @Test fun onlyExplicitRefreshInvalidatesTheDiskCache() = runTest {
        var invalidations = 0
        val model = LibraryPages(this, { invalidations++ }) { _, cursor -> page(cursor ?: "first", next = "more") }
        model.open(LibraryRoute.Favorites)
        runCurrent()
        model.loadMore()
        runCurrent()
        assertEquals(0, invalidations)
        model.refresh()
        runCurrent()
        assertEquals(1, invalidations)
    }

    @Test fun failedRefreshAndAppendKeepOldRowsAndRetryTheFailedCursor() = runTest {
        val cursors = mutableListOf<String?>()
        val model = LibraryPages(this) { _, cursor ->
            cursors += cursor
            when (cursors.size) {
                1 -> page("old", next = "more-old")
                2 -> error("refresh failed")
                3 -> page("new", next = "more-new")
                4 -> error("append failed")
                else -> page("new", "added", next = cursor)
            }
        }
        model.open(LibraryRoute.Liked)
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(listOf("old"), model.state.value.value.tracks.map(Track::title))
        assertEquals("refresh failed", model.state.value.error)
        assertFalse(model.state.value.retryingAppend)

        model.retry()
        runCurrent()
        model.loadMore()
        runCurrent()
        assertEquals(listOf("new"), model.state.value.value.tracks.map(Track::title))
        assertEquals("append failed", model.state.value.error)
        assertTrue(model.state.value.retryingAppend)

        model.retry()
        runCurrent()
        assertEquals(listOf(null, null, null, "more-new", "more-new"), cursors)
        assertEquals(listOf("new", "added"), model.state.value.value.tracks.map(Track::title))
        assertNull(model.state.value.value.nextCursor)
        assertNull(model.state.value.error)
    }

    @Test fun refreshAndNavigationDiscardLateRowsEvenIfFetchCompletesWhenCancelled() = runTest {
        val oldAppend = CompletableDeferred<LibraryPage>()
        val oldRoute = CompletableDeferred<LibraryPage>()
        var favoritesCalls = 0
        val model = LibraryPages(this) { route, cursor ->
            when {
                route == LibraryRoute.History -> withContext(NonCancellable) { oldRoute.await() }
                route == LibraryRoute.Following -> page("following")
                cursor != null -> withContext(NonCancellable) { oldAppend.await() }
                favoritesCalls++ == 0 -> page("original", next = "append")
                else -> page("refreshed")
            }
        }
        model.open(LibraryRoute.Favorites)
        runCurrent()
        model.loadMore()
        runCurrent()
        model.refresh()
        runCurrent()
        oldAppend.complete(page("stale append"))
        runCurrent()
        assertEquals(listOf("refreshed"), model.state.value.value.tracks.map(Track::title))

        model.open(LibraryRoute.History)
        runCurrent()
        model.open(LibraryRoute.Following)
        runCurrent()
        oldRoute.complete(page("stale route"))
        runCurrent()
        assertEquals(listOf("following"), model.state.value.value.tracks.map(Track::title))
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
    }

    private fun page(vararg titles: String, next: String? = null) = LibraryPage(
        tracks = titles.map { Track("test", it, it, "") }, nextCursor = next,
    )
}
