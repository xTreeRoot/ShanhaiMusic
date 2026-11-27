package com.xtreeroot.mb.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginLikesTest {
    @Test fun readsEveryPageUsingThePluginCursor() = runBlocking {
        val offsets = mutableListOf<Int>()
        val ids = readPluginLikedIDs { offset ->
            offsets += offset
            JSONObject(if (offset == 0) """{"tracks":[{"id":"first"}],"more":true,"nextOffset":5}"""
                else """{"tracks":[{"id":"tail"}],"more":false}""")
        }
        assertEquals(listOf(0, 5), offsets)
        assertEquals(setOf("first", "tail"), ids)
    }
    @Test fun missingOrRepeatedPagesCannotBecomeNegativeState() = runBlocking {
        for (value in listOf("{}", """{"tracks":[{"id":"same"}],"more":true,"nextOffset":1}""")) {
            try { readPluginLikedIDs { JSONObject(value) }; fail("Incomplete response was accepted") }
            catch (_: PluginException) { }
        }
    }
    @Test fun temporaryFailureCanRetryWithoutKeepingAnEmptySnapshot() = runBlocking {
        var failed = false
        try { readPluginLikedIDs { throw PluginException("temporary") } }
        catch (_: PluginException) { failed = true }
        assertTrue(failed)
        assertEquals(setOf("saved"), readPluginLikedIDs { JSONObject("""{"tracks":[{"id":"saved"}],"more":false}""") })
    }
}
