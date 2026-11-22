package com.xtreeroot.mb.playback

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 通用非分片 ISO BMFF AAC CENC 解码；内容密钥必须由信息源本次授权提供。 */
internal object CencAudio {
    private data class Box(val type: String, val start: Int, val body: Int, val end: Int)
    private data class Run(val first: Int, val count: Int)
    private data class Sample(val offset: Int, val size: Int)
    private fun invalid(): Nothing = throw IOException("Unsupported or invalid CENC audio")
    private fun requireValid(value: Boolean) { if (!value) invalid() }

    fun key(hex: String): ByteArray {
        requireValid(hex.matches(Regex("[0-9a-fA-F]{32}")))
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun integer(data: ByteArray, at: Int, width: Int, end: Int): Int {
        requireValid(width > 0 && at >= 0 && at <= end && width <= end - at && end <= data.size)
        var value = 0L
        for (index in at until at + width) {
            value = value * 256 + (data[index].toInt() and 255)
            requireValid(value <= Int.MAX_VALUE)
        }
        return value.toInt()
    }
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        requireValid(from >= 0 && from <= to && to <= data.size)
        val result = mutableListOf<Box>()
        var at = from
        while (at < to) {
            requireValid(to - at >= 8)
            var size = integer(data, at, 4, to)
            var header = 8
            if (size == 1) { size = integer(data, at + 8, 8, to); header = 16 }
            if (size == 0) size = to - at
            requireValid(size >= header && size <= to - at)
            result += Box(String(data, at + 4, 4, Charsets.US_ASCII), at, at + header, at + size)
            at += size
        }
        return result
    }
    private fun List<Box>.one(type: String): Box = filter { it.type == type }.singleOrNull() ?: invalid()
    private fun Box.children(data: ByteArray) = boxes(data, body, end)
    private fun Box.word(data: ByteArray, offset: Int, width: Int = 4) = integer(data, body + offset, width, end)

    private fun samples(data: ByteArray, tables: List<Box>, media: List<Box>): List<Sample> {
        val sizes = tables.one("stsz")
        val fixed = sizes.word(data, 4)
        val count = sizes.word(data, 8)
        requireValid(sizes.word(data, 0) == 0 && count in 1..1_000_000)
        val lengths = IntArray(count) { if (fixed > 0) fixed else sizes.word(data, 12 + it * 4) }
        requireValid(lengths.all { it > 0 })
        val offsets = tables.filter { it.type in listOf("stco", "co64") }.singleOrNull() ?: invalid()
        val chunks = offsets.word(data, 4)
        val stride = if (offsets.type == "co64") 8 else 4
        requireValid(offsets.word(data, 0) == 0 && chunks in 1..count)
        val mapping = tables.one("stsc")
        val runCount = mapping.word(data, 4)
        requireValid(mapping.word(data, 0) == 0 && runCount in 1..chunks)
        val runs = mutableListOf<Run>()
        repeat(runCount) { index ->
            val first = mapping.word(data, 8 + index * 12)
            val amount = mapping.word(data, 12 + index * 12)
            requireValid(first in 1..chunks && first > (runs.lastOrNull()?.first ?: 0) && amount in 1..count && mapping.word(data, 16 + index * 12) == 1)
            runs += Run(first, amount)
        }
        requireValid(runs.first().first == 1)
        val result = ArrayList<Sample>(count)
        var sample = 0
        var run = 0
        var priorEnd = 0
        for (chunk in 1..chunks) {
            if (run + 1 < runs.size && runs[run + 1].first == chunk) run++
            var offset = offsets.word(data, 8 + (chunk - 1) * stride, stride)
            requireValid(offset >= priorEnd && runs[run].count <= count - sample)
            repeat(runs[run].count) {
                val size = lengths[sample]
                requireValid(media.any { offset >= it.body && offset <= it.end && size <= it.end - offset })
                result += Sample(offset, size)
                offset += size
                sample++
            }
            priorEnd = offset
        }
        requireValid(sample == count)
        return result
    }

    fun decode(input: ByteArray, key: ByteArray, checkCancellation: () -> Unit = {}): ByteArray {
        requireValid(key.size == 16 && input.size <= 128 shl 20)
        val data = input.copyOf()
        val top = boxes(data, 0, data.size)
        val movie = top.one("moov").children(data)
        val media = top.filter { it.type == "mdat" }
        requireValid(media.isNotEmpty() && top.none { it.type == "moof" } && movie.none { it.type == "mvex" })
        var decoded = 0
        for (track in movie.filter { it.type == "trak" }) {
            val mdia = track.children(data).one("mdia").children(data)
            if (mdia.one("hdlr").word(data, 8) != 0x736f756e) continue
            val tables = mdia.one("minf").children(data).one("stbl").children(data)
            val descriptions = tables.one("stsd")
            requireValid(descriptions.word(data, 0) == 0 && descriptions.word(data, 4) == 1)
            val entry = boxes(data, descriptions.body + 8, descriptions.end).one("enca")
            requireValid(entry.word(data, 8, 2) == 0)
            val protection = boxes(data, entry.body + 28, entry.end).one("sinf")
            val protections = protection.children(data)
            requireValid(protections.one("frma").word(data, 0) == 0x6d703461 && protections.one("schm").word(data, 4) == 0x63656e63)
            val defaults = protections.one("schi").children(data).one("tenc")
            val ivSize = defaults.word(data, 7, 1)
            requireValid(defaults.word(data, 0, 1) == 0 && defaults.word(data, 6, 1) == 1 && ivSize in listOf(8, 16) && defaults.end - defaults.body >= 24)
            val encryption = tables.one("senc")
            val flags = encryption.word(data, 0)
            val samples = samples(data, tables, media)
            requireValid(flags in listOf(0, 2) && encryption.word(data, 4) == samples.size)
            var cursor = encryption.body + 8
            for ((index, sample) in samples.withIndex()) {
                if (index % 128 == 0) checkCancellation()
                requireValid(cursor <= encryption.end && ivSize <= encryption.end - cursor)
                val iv = data.copyOfRange(cursor, cursor + ivSize).copyOf(16)
                cursor += ivSize
                val spans = mutableListOf<IntRange>()
                if (flags == 2) {
                    val count = integer(data, cursor, 2, encryption.end)
                    cursor += 2
                    var offset = sample.offset
                    if (count == 0) spans += offset until offset + sample.size
                    repeat(count) {
                        val clear = integer(data, cursor, 2, encryption.end)
                        val encrypted = integer(data, cursor + 2, 4, encryption.end)
                        cursor += 6
                        requireValid(clear <= sample.offset + sample.size - offset)
                        offset += clear
                        requireValid(encrypted <= sample.offset + sample.size - offset)
                        spans += offset until offset + encrypted
                        offset += encrypted
                    }
                    requireValid(count == 0 || offset == sample.offset + sample.size)
                } else spans += sample.offset until sample.offset + sample.size
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                for (span in spans.filterNot { it.isEmpty() }) {
                    requireValid(span.first >= sample.offset && span.last < sample.offset + sample.size)
                    val clear = cipher.update(data, span.first, span.count())
                    requireValid(clear != null && clear.size == span.count())
                    clear.copyInto(data, span.first)
                }
                requireValid(cipher.doFinal().isEmpty())
            }
            requireValid(cursor == encryption.end)
            "mp4a".toByteArray().copyInto(data, entry.start + 4)
            for (box in listOf(protection) + tables.filter { it.type in listOf("senc", "saiz", "saio") }) {
                "free".toByteArray().copyInto(data, box.start + 4)
            }
            decoded++
        }
        requireValid(decoded == 1)
        return data
    }
}
