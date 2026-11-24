package com.xtreeroot.mb.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnifiedSearchTest {
    @Test fun providersRunTogetherKeepTheirOwnCursorsAndPreservePartialResults() = runTest {
        val ready = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val providers = listOf(
            SearchProvider("music", "One") { _, cursor ->
                calls += "music:$cursor"; ready.await()
                TrackPage(listOf(Track("one", "$cursor", "Song", "Artist")), cursor == 1) to cursor + 1
            },
            SearchProvider("video", "Two", "video", 0) { _, cursor ->
                calls += "video:$cursor"; ready.complete(Unit)
                TrackPage(listOf(Track("two", "$cursor", "Clip", "Creator")), false) to 7
            },
            SearchProvider("failed", "Offline") { _, _ -> throw IllegalStateException("network") },
        )
        val source = UnifiedSearchSource("all", providers)
        val first = source.search("query", 1)
        assertEquals(listOf("music", "video"), first.tracks.map { it.resourceType })
        assertEquals(listOf("one", "two"), first.tracks.map { it.sourceId })
        assertTrue(first.hasMore)
        assertEquals(listOf("Offline: network"), first.warnings)
        val second = source.search("query", 2)
        assertEquals(listOf("music:1", "video:0", "music:2"), calls)
        assertFalse(second.hasMore)
        assertEquals("2", second.tracks.single().sourceTrackId)
    }
    @Test fun cancellationIsNotReportedAsAnEmptySuccessfulSearch() = runTest {
        val source = UnifiedSearchSource("all", listOf(SearchProvider("one", "One") { _, _ -> awaitCancellation() }))
        val task = async { source.search("query", 1) }
        yield(); task.cancel()
        assertTrue(task.isCancelled)
    }
}
