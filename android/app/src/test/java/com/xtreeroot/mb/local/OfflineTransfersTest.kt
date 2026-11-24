package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.Track
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineTransfersTest {
    @get:Rule val folder = TemporaryFolder()
    private val track = Track("fixture", "offline", "Offline Fixture", "Fixture Artist")

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun preparedLocalAudioIsSavedWithoutRemovingItsPlaybackCache() = runBlocking {
        val cached = folder.newFile("prepared.m4a").apply { writeText("prepared audio fixture") }
        val store = OfflineMediaStore(folder.newFolder())
        val transfers = OfflineTransfers(store, { error("Local audio must not open an HTTP connection") }) {
            MediaResource(cached.toURI().toASCIIString(), emptyMap())
        }
        transfers.downloadAndWait()
        assertNull(transfers.error.value)
        assertEquals("prepared audio fixture", store.lease(track.id, downloadsOnly = true)?.use { it.path.readText() })
        assertEquals("prepared audio fixture", cached.readText())
    }

    @Test fun followsSameOriginAndCdnRedirectsWithoutForwardingPrivateHeaders() = runBlocking {
        val start = "https://source.example/start"
        val next = "https://source.example/media/redirect"
        val cdn = "https://cdn.example/song?a=fixture"
        val initial = Reply(start, 302, location = "media/redirect")
        val hop = Reply(next, 307, location = cdn)
        val audio = Reply(cdn, 200, body = "track bytes")
        val opened = mutableListOf<Reply>()
        val store = OfflineMediaStore(folder.newFolder())
        val transfers = OfflineTransfers(store, { address ->
            listOf(initial, hop, audio).single { it.url.toString() == address.toString() }.also(opened::add)
        }) { MediaResource(start, mapOf("Authorization" to "fixture", "X-Media-Token" to "fixture-token")) }

        transfers.downloadAndWait()

        assertEquals(listOf(initial, hop, audio), opened)
        assertEquals("fixture", hop.getRequestProperty("Authorization"))
        assertEquals("fixture-token", hop.getRequestProperty("X-Media-Token"))
        assertNull(audio.getRequestProperty("Authorization"))
        assertNull(audio.getRequestProperty("X-Media-Token"))
        assertEquals("identity", audio.getRequestProperty("Accept-Encoding"))
        assertEquals("track bytes", store.lease(track.id, downloadsOnly = true)?.use { it.path.readText() })
        assertNull(transfers.error.value)
    }

    @Test fun rewritesAStagedPartialDownloadFromZeroOnTheBackup() = runBlocking {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        val primary = Reply("https://source.example/start", 200, body = "old partial", expected = 200)
        val backup = Reply("https://cdn.example/song", 200, body = "whole audio")
        val opened = mutableListOf<Reply>()
        val transfers = OfflineTransfers(store, { address ->
            listOf(primary, backup).single { it.url.toString() == address.toString() }.also(opened::add)
        }) { MediaResource(primary.url.toString(), emptyMap(), backupUrls = listOf(backup.url.toString())) }

        transfers.downloadAndWait()

        assertEquals(listOf(primary, backup), opened)
        assertEquals("whole audio", store.lease(track.id, downloadsOnly = true)?.use { it.path.readText() })
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
        assertNull(transfers.error.value)
    }

    @Test fun rejectsAnInsecureRedirectWithoutTouchingItsTargetOrPublishingAudio() = runBlocking {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        val redirect = Reply("https://source.example/start", 302, location = "http://cdn.example/song")
        val opened = mutableListOf<String>()
        val transfers = OfflineTransfers(store, { address ->
            opened += address.toString()
            check(address.toString() == redirect.url.toString())
            redirect
        }) { MediaResource(redirect.url.toString(), emptyMap()) }

        transfers.downloadAndWait()

        assertEquals(listOf(redirect.url.toString()), opened)
        assertNotNull(transfers.error.value)
        assertNull(store.lease(track.id))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun boundsAttemptsAndKeepsFailedDownloadsOutOfTheLibrary() = runBlocking {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        val primary = Reply("https://source.example/start", 403)
        val firstBackup = Reply("https://cdn.example/unavailable", 503)
        val secondBackup = Reply("https://cdn.example/truncated", 200, body = "partial", expected = 100)
        val opened = mutableListOf<String>()
        val transfers = OfflineTransfers(store, { address ->
            opened += address.toString()
            listOf(primary, firstBackup, secondBackup).single { it.url.toString() == address.toString() }
        }) { MediaResource(primary.url.toString(), emptyMap(), backupUrls =
            listOf(firstBackup.url.toString(), secondBackup.url.toString(), "https://cdn.example/too-many")) }

        transfers.downloadAndWait()

        assertEquals(listOf(primary, firstBackup, secondBackup).map { it.url.toString() }, opened)
        assertNotNull(transfers.error.value)
        assertNull(store.lease(track.id))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun cancellingDuringThePrimaryTransferDoesNotStartTheBackup() = runBlocking {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        val reading = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val primary = Reply("https://source.example/start", 200, input = {
            reading.complete(Unit)
            check(released.await(5, TimeUnit.SECONDS))
            throw SocketTimeoutException()
        })
        val opened = mutableListOf<String>()
        val transfers = OfflineTransfers(store, { address ->
            opened += address.toString()
            check(address.toString() == primary.url.toString())
            primary
        }) { MediaResource(primary.url.toString(), emptyMap(), backupUrls = listOf("https://cdn.example/song")) }

        transfers.download(track)
        try { withTimeout(3_000) { reading.await() }; transfers.cancel(track.id) }
        finally { released.countDown() }
        withTimeout(5_000) { transfers.transfers.first { track.id !in it } }

        assertEquals(listOf(primary.url.toString()), opened)
        assertNull(transfers.error.value)
        assertNull(store.lease(track.id))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    private suspend fun OfflineTransfers.downloadAndWait() {
        download(track)
        withTimeout(5_000) { transfers.first { track.id !in it } }
    }

    private class Reply(address: String, private val status: Int, body: String = "", private val location: String? = null,
                        private val expected: Long = body.encodeToByteArray().size.toLong(),
                        private val input: (() -> InputStream)? = null) : HttpsURLConnection(URL(address)) {
        private val bytes = body.encodeToByteArray()
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = location.takeIf { name.equals("Location", ignoreCase = true) }
        override fun getContentType(): String = "audio/mp4"
        override fun getContentLengthLong(): Long = expected
        override fun getInputStream(): InputStream = input?.invoke() ?: ByteArrayInputStream(bytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }
}
