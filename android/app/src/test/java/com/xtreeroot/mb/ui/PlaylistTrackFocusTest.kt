package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogPage
import com.xtreeroot.mb.plugin.PluginCatalogSection
import org.junit.Assert.*
import org.junit.Test

class PlaylistTrackFocusTest {
    private val target = Track("one", "same", "Song", "")
    private val other = Track("two", "same", "Song", "")

    @Test fun matchesSourceAndContinuesPagesOnlyUntilFound() {
        val focus = PlaylistTrackFocus(target.id)
        assertNull(focus.locate(listOf(other), hasMore = true))
        assertFalse(focus.finished)
        assertEquals(1, focus.locate(listOf(other, target), hasMore = false))
        assertNull(focus.locate(listOf(target), hasMore = false))
    }

    @Test fun missingAndManualScrollingDoNotPullTheListBack() {
        val missing = PlaylistTrackFocus(target.id)
        assertNull(missing.locate(listOf(other), hasMore = false))
        assertTrue(missing.finished)
        val stopped = PlaylistTrackFocus(target.id)
        stopped.stop()
        assertNull(stopped.locate(listOf(target), hasMore = true))
        assertTrue(PlaylistTrackFocus(null).finished)
    }

    @Test fun gridCountsHeadersAndEarlierSections() {
        val page = PluginCatalogPage("", "", "", listOf(
            PluginCatalogSection("First", listOf(other), emptyList(), emptyList(), null, "", false),
            PluginCatalogSection("Second", listOf(target), emptyList(), emptyList(), null, "", false)))
        assertEquals(4, playlistGridTrackIndex(page, target.id, resumeHeader = true))
        assertEquals(3, playlistGridTrackIndex(page, target.id, resumeHeader = false))
    }
}
