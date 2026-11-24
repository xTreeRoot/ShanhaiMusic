package com.xtreeroot.mb.playback

import com.xtreeroot.mb.music.MediaResource
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextAudioPreloaderTest {
    @get:Rule val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(2, SECONDS).build()

    @Test fun alreadyPreparedLocalAudioDoesNotOpenAnHttpRangeRequest() = runBlocking {
        assertNull(primeAudio(client, MediaResource("file:///cache/prepared-fixture.m4a", emptyMap())))
        assertEquals(0, server.requestCount)
    }

    @Test fun triggersOnlyWhilePlayingFromSeventyPercentWithoutVideo() {
        assertFalse(canPrefetchAudio(699, 1_000, playing = true, videoActive = false))
        assertTrue(canPrefetchAudio(700, 1_000, playing = true, videoActive = false))
        assertFalse(canPrefetchAudio(900, 1_000, playing = false, videoActive = false))
        assertFalse(canPrefetchAudio(900, 1_000, playing = true, videoActive = true))
        assertFalse(canPrefetchAudio(900, 0, playing = true, videoActive = false))
    }

    @Test fun lateCompletionCannotReplaceNewTargetAndOnlyMatchingHandoffIsTaken() = runTest {
        val delayed = CompletableDeferred<String>()
        val loader = NextAudioPreloader(this, { testScheduler.currentTime }) { key: String ->
            if (key == "old") withContext(NonCancellable) { delayed.await() } else "prepared $key"
        }
        loader.update("old")
        runCurrent()
        loader.update("new")
        runCurrent()
        delayed.complete("late")
        runCurrent()
        loader.advance("new")
        assertNull(loader.take("old"))
        assertEquals("prepared new", loader.take("new"))
        assertNull(loader.take("new"))
    }

    @Test fun timeoutAndCancellationDiscardCachedAudio() = runTest {
        var time = 0L
        val loader = NextAudioPreloader(this, { time }) { key: String -> "prepared $key" }
        loader.update("next")
        runCurrent()
        time = preparedAudioLifetimeMs
        assertNull(loader.take("next"))
        loader.update("replacement")
        runCurrent()
        loader.advance("replacement")
        loader.cancel()
        assertNull(loader.take("replacement"))
    }

    @Test fun boundedPrefixIsReusedAndTheTailUsesTheSameValidator() = runBlocking {
        val head = "a".repeat(audioPrefixBytes)
        val whole = head + "end"
        server.enqueue(range(0, head, whole.length, "\"same\""))
        server.enqueue(range(head.length, "end", whole.length, "\"same\""))
        val prepared = checkNotNull(primeAudio(client, resource()))
        assertEquals(audioPrefixBytes, prepared.prefix.bytes.size)
        assertEquals(whole, playback(prepared))
        assertEquals("bytes=0-${head.length - 1}", server.takeRequest(1, SECONDS)?.getHeader("Range"))
        val tail = checkNotNull(server.takeRequest(1, SECONDS))
        assertEquals("bytes=${head.length}-", tail.getHeader("Range"))
        assertEquals("\"same\"", tail.getHeader("If-Range"))
        assertEquals(2, server.requestCount)
    }

    @Test fun changedTailNeverJoinsStaleBytesAndNormalPlaybackStartsAtZero() = runBlocking {
        val head = "a".repeat(audioPrefixBytes)
        val size = head.length + 3
        server.enqueue(range(0, head, size, "\"old\""))
        server.enqueue(range(head.length, "end", size, "\"changed\""))
        server.enqueue(MockResponse().setBody("fresh representation"))
        assertEquals("fresh representation", playback(checkNotNull(primeAudio(client, resource()))))
        repeat(2) { assertTrue(server.takeRequest(1, SECONDS)?.getHeader("Range")?.startsWith("bytes=") == true) }
        assertNull(checkNotNull(server.takeRequest(1, SECONDS)).getHeader("Range"))
    }

    @Test fun serverIgnoringRangeDoesNotProduceAPreparedPrefix() = runBlocking {
        server.enqueue(MockResponse().setBody("no range support"))
        assertNull(primeAudio(client, resource()))
        server.enqueue(range(0, "tiny audio", 10, "\"small\""))
        assertEquals("tiny audio", playback(checkNotNull(primeAudio(client, resource()))))
        assertEquals(2, server.requestCount)
    }

    private fun resource() = MediaResource(server.url("/track").toString(), mapOf("Referer" to "fixture"))

    private fun playback(prepared: PreparedAudio): String {
        val guard = MediaRangeGuard().apply { offer(prepared.prefix) }
        val request = Request.Builder().url(prepared.resource.url).build()
        return guardedMediaClient(client, guard).newCall(request).execute().use { checkNotNull(it.body).string() }
    }

    private fun range(start: Int, body: String, total: Int, etag: String) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $start-${start + body.length - 1}/$total").setHeader("ETag", etag).setBody(body)
}
