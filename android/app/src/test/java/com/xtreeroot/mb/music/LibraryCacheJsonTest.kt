package com.xtreeroot.mb.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryCacheJsonTest {
    @Test fun pagesRoundTripAllRoutesCoversAndPaginationWithoutPrivateHeaders() {
        val routes = listOf(LibraryRoute.Favorites, LibraryRoute.Liked, LibraryRoute.History, LibraryRoute.Following,
            LibraryRoute.Folder("12"), LibraryRoute.Creator("34"))
        val track = Track("fixture", "1", "歌曲", "歌手", "https://image.example/song", 1234,
            artworkHeaders = mapOf("Referer" to "https://source.example", "Cookie" to "must-not-persist"))
        val page = LibraryPage("我的", listOf(track), routes.mapIndexed { index, route ->
            LibraryLink("$index", "列表 $index", "说明", "https://image.example/$index", route)
        }, "next-page")
        val data = page.toCacheJson().toString()
        assertFalse(data.contains("must-not-persist"))
        assertEquals(page.copy(tracks = listOf(track.copy(artworkHeaders = mapOf("Referer" to "https://source.example")))),
            JSONObject(data).toLibraryCachePage())
        assertEquals(LibraryPage(), JSONObject(LibraryPage().toCacheJson().toString()).toLibraryCachePage())
    }
}
