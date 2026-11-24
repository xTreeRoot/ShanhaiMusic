package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PendingPlaybackPages
import com.xtreeroot.mb.playback.playbackPages
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPaginationTest {
    @Test fun paginationUsesRawOffsetAndStopsOnFinalPage() {
        assertEquals(20, JSONObject("""{"more":true,"nextOffset":20}""").catalogNextOffset(0, 1))
        assertEquals(25, JSONObject("""{"more":true}""").catalogNextOffset(20, 5))
        assertNull(JSONObject("""{"more":false,"nextOffset":40}""").catalogNextOffset(20, 5))
        assertNull(JSONObject("""{"more":true,"nextOffset":20}""").catalogNextOffset(20, 5))
    }

    @Test fun highlightsAreTextWithoutChangingLiteralAngleBrackets() {
        assertEquals("1 < 2 入戏 <em>原文</em>", pluginPlainTitle("""1 < 2 <em class="keyword">入戏</em> <em>原文</em>"""))
    }

    @Test fun serviceHandoffConsumesOneTokenAndStopsRepeatedCursor() = runBlocking {
        val calls = mutableListOf<Int>()
        val continuation = checkNotNull(playbackPages(20) { offset ->
            calls += offset
            listOf(Track("fixture", "$offset", "$offset", "")) to if (offset == 20) 40 else 20
        })
        val pending = PendingPlaybackPages()
        val token = pending.offer(continuation)
        val first = checkNotNull(pending.take(token)).load()
        assertNull(pending.take(token))
        assertEquals("20", first.tracks.single().title)
        val last = checkNotNull(first.next).load()
        assertNull(last.next)
        assertEquals(listOf(20, 40), calls)
    }
}
