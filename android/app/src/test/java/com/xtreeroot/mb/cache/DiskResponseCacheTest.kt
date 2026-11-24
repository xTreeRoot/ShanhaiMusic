package com.xtreeroot.mb.cache

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskResponseCacheTest {
    @Test fun freshPagesSurviveReopenAndExpiredPagesRefreshOrFallBack() = runBlocking {
        val directory = Files.createTempDirectory("mb-catalog").toFile()
        var clock = 100L
        try {
            val first = DiskResponseCache(directory, now = { clock })
            assertEquals("first", first.data("page", 10) { "first".toByteArray() }.decodeToString())
            val reopened = DiskResponseCache(directory, now = { clock })
            assertEquals("first", reopened.data("page", 10) { error("must use disk") }.decodeToString())
            clock += 11
            assertEquals("first", reopened.data("page", 10) { throw IOException("offline") }.decodeToString())
            assertEquals("new", reopened.data("page", 10) { "new".toByteArray() }.decodeToString())
            reopened.invalidate()
            assertEquals("after refresh", reopened.data("page") { "after refresh".toByteArray() }.decodeToString())
        } finally { directory.deleteRecursively() }
    }

    @Test fun coalescesConcurrentRequestsForOnePage() = runBlocking {
        val cache = DiskResponseCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val requests = List(8) { async {
            cache.data("same") { loads++; entered.complete(Unit); release.await(); "rows".toByteArray() }.decodeToString()
        } }
        entered.await()
        release.complete(Unit)
        assertEquals(List(8) { "rows" }, requests.awaitAll())
        assertEquals(1, loads)
    }

    @Test fun invalidationRejectsLateResponsesInsteadOfRepopulatingTheCache() = runBlocking {
        val cache = DiskResponseCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async {
            cache.data("page") { entered.complete(Unit); release.await(); "stale".toByteArray() }
        }
        entered.await()
        val invalidation = launch(start = CoroutineStart.UNDISPATCHED) { cache.invalidate() }
        release.complete(Unit)
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        invalidation.join()
        assertEquals("fresh", cache.data("page") { "fresh".toByteArray() }.decodeToString())
    }

    @Test fun cancellationDoesNotReturnExpiredRows() = runBlocking {
        var clock = 0L
        val cache = DiskResponseCache(now = { clock })
        cache.data("page", 1) { "old".toByteArray() }
        clock = 2
        val failure = runCatching { cache.data("page", 1) { throw CancellationException("cancelled") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun damagedFilesReloadAndCapacityEvictsTheLeastRecentlyUsedPage() = runBlocking {
        val directory = Files.createTempDirectory("mb-catalog").toFile()
        var clock = 10L
        val cache = DiskResponseCache(directory, maximumBytes = 90, now = { clock++ })
        try {
            cache.data("a") { "aaaa".toByteArray() }
            cache.data("b") { "bbbb".toByteArray() }
            cache.data("a") { error("cached") }
            cache.data("c") { "cccc".toByteArray() }
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 90)
            assertFalse(directory.resolve(DiskResponseCache.key("b")).exists())
            val file = directory.resolve(DiskResponseCache.key("a"))
            val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(damaged)
            assertEquals("new", cache.data("a") { "new".toByteArray() }.decodeToString())
        } finally { directory.deleteRecursively() }
    }

    @Test fun keysSeparateSourcesAccountsPaginationAndPrivateArtworkHeaders() {
        assertNotEquals(DiskResponseCache.key("a", "bc"), DiskResponseCache.key("ab", "c"))
        val first = DiskResponseCache.key("plugin-a", "user-1", "liked", "0")
        assertNotEquals(first, DiskResponseCache.key("plugin-b", "user-1", "liked", "0"))
        assertNotEquals(first, DiskResponseCache.key("plugin-a", "user-2", "liked", "0"))
        assertNotEquals(first, DiskResponseCache.key("plugin-a", "user-1", "liked", "20"))
        val address = "https://images.example/cover"
        assertEquals(artworkCacheKey(address, mapOf("Cookie" to "a", "Referer" to "site")),
            artworkCacheKey(address, mapOf("referer" to "site", "cookie" to "a")))
        assertNotEquals(artworkCacheKey(address, mapOf("Cookie" to "a")), artworkCacheKey(address, mapOf("Cookie" to "b")))
        assertNotEquals(artworkCacheKey("$address?v=1", emptyMap()), artworkCacheKey("$address?v=2", emptyMap()))
        assertTrue(artworkCacheKey(address, mapOf("Cookie" to "private-cookie")).matches(Regex("[a-f0-9]{64}")))
    }
}
