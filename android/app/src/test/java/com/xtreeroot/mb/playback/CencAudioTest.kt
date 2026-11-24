package com.xtreeroot.mb.playback

import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CencAudioTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = CencAudio.key("00112233445566778899aabbccddeeff")
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/cenc-aac.m4a")).use { it.readBytes() }

    @Test fun rejectsInvalidKeysAndTruncatedContainers() {
        for (text in listOf("", "zz".repeat(16), "0011")) assertThrows(IOException::class.java) { CencAudio.key(text) }
        val input = fixture()
        for (size in listOf(0, 4, 16, input.size - 1)) {
            assertThrows(IOException::class.java) { CencAudio.decode(input.copyOf(size), key) }
        }
        assertThrows(IOException::class.java) { CencAudio.decode(input, byteArrayOf()) }
    }

    @Test fun restoresAacDescriptionWithoutChangingContainerOffsetsOrTimeTables() {
        val input = fixture()
        val decoded = CencAudio.decode(input, key)
        assertEquals(input.size, decoded.size)
        assertFalse(input.contentEquals(decoded))
        assertFalse(decoded.toString(Charsets.ISO_8859_1).contains("enca"))
        assertFalse(decoded.toString(Charsets.ISO_8859_1).contains("senc"))
        val timeAt = input.toString(Charsets.ISO_8859_1).indexOf("stts")
        assertTrue(timeAt > 4)
        val size = ByteBuffer.wrap(input, timeAt - 4, 4).int
        assertArrayEquals(input.copyOfRange(timeAt - 4, timeAt - 4 + size), decoded.copyOfRange(timeAt - 4, timeAt - 4 + size))
    }

    private fun word(value: Int, width: Int = 4) = ByteArray(width) { (value ushr ((width - 1 - it) * 8)).toByte() }
    private fun box(name: String, bytes: ByteArray) = word(bytes.size + 8) + name.toByteArray() + bytes

    @Test fun nistCtrVectorDecodesInterleavedSamplesAndLeavesClearSubsamplesUntouched() {
        val vectorKey = CencAudio.key("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = ByteArray(16) { (0xf0 + it).toByte() }
        val cipher = CencAudio.key("874d6191b620e3261bef6864990db6ce")
        val plain = CencAudio.key("6bc1bee22e409f96e93d7e117393172a")
        val text = "keep text".toByteArray()
        val prefix = byteArrayOf(7, 8, 9)
        val mdat = box("mdat", text + cipher + text + prefix + cipher)
        val stsz = box("stsz", word(0) + word(0) + word(2) + word(16) + word(19))
        val stco = box("stco", word(0) + word(2) + word(8 + text.size) + word(8 + text.size * 2 + 16))
        val stsc = box("stsc", word(0) + word(1) + word(1) + word(1) + word(1))
        val senc = box("senc", word(2) + word(2) + iv + word(1, 2) + word(0, 2) + word(16) + iv + word(1, 2) + word(3, 2) + word(16))
        val tenc = box("tenc", word(0) + byteArrayOf(0, 0, 1, 16) + ByteArray(16))
        val sinf = box("sinf", box("frma", "mp4a".toByteArray()) + box("schm", word(0) + "cenc".toByteArray() + word(0)) + box("schi", tenc))
        val stsd = box("stsd", word(0) + word(1) + box("enca", ByteArray(28) + sinf))
        val stbl = box("stbl", stsd + stsz + stco + stsc + senc)
        val hdlr = box("hdlr", word(0) + word(0) + "soun".toByteArray())
        val movie = box("moov", box("trak", box("mdia", hdlr + box("minf", stbl))))
        val input = mdat + movie
        val decoded = CencAudio.decode(input, vectorKey)
        assertArrayEquals(text + plain + text + prefix + plain, decoded.copyOfRange(8, mdat.size))
        val oversized = input.copyOf().apply { word(Int.MAX_VALUE).copyInto(this) }
        assertThrows(IOException::class.java) { CencAudio.decode(oversized, vectorKey) }
    }

    @Test fun cancellationStopsContainerProcessing() {
        assertThrows(CancellationException::class.java) {
            CencAudio.decode(fixture(), key) { throw CancellationException() }
        }
    }

    @Test fun concurrentPreparationSharesCacheAndNeverTouchesAccountFiles() = runBlocking {
        val cache = folder.newFolder("cache")
        val account = folder.newFile("storage.json").apply { writeText("private-fixture") }
        val prepared = PreparedCencAudio(cache)
        var calls = 0
        suspend fun load(headers: Map<String, String> = emptyMap()) = prepared.prepare("https://media.example.test/audio", headers, key) { calls++; fixture() }
        val first = async { load() }
        val second = async { load() }
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
        assertArrayEquals(CencAudio.decode(fixture(), key), first.await().readBytes())
        assertNotEquals(first.await(), load(mapOf("X-Session" to "another-fixture")))
        assertEquals(2, calls)
        assertEquals("private-fixture", account.readText())
        assertFalse(cache.listFiles().orEmpty().any { it.extension == "partial" })
    }

    @Test fun failedPreparationDoesNotPublishPartialFiles() = runBlocking {
        val cache = folder.newFolder()
        val prepared = PreparedCencAudio(cache)
        try {
            prepared.prepare("https://media.example.test/audio", emptyMap(), key) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
}
