package com.xtreeroot.mb.cache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 按来源、账号、路由与游标复用元数据；不存媒体流或请求凭据。 */
internal class DiskResponseCache(
    private val directory: File? = null,
    private val maximumBytes: Long = 32L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val saved: Long, val data: ByteArray)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val memory = mutableMapOf<String, Entry>()

    suspend fun data(key: String, lifetimeMs: Long = 6L * 60 * 60 * 1000, load: suspend () -> ByteArray): ByteArray {
        val revision = generation.get()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (revision != generation.get()) throw CancellationException()
                val name = key(key)
                val cached = read(name)
                if (cached != null && now() - cached.saved < lifetimeMs) return@withLock cached.data
                try {
                    val data = load()
                    currentCoroutineContext().ensureActive()
                    if (revision != generation.get()) throw CancellationException()
                    if (data.isNotEmpty() && data.size + 40 <= maximumBytes) write(name, Entry(now(), data))
                    data
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (failure is CancellationException || revision != generation.get()) throw failure
                    cached?.data ?: throw failure
                }
            }
        }
    }

    suspend fun invalidate() {
        generation.incrementAndGet()
        withContext(Dispatchers.IO) { mutex.withLock { memory.clear(); directory?.deleteRecursively() } }
    }

    private fun read(key: String): Entry? {
        if (directory == null) return memory[key]
        val file = File(directory, key)
        if (file.length() !in 41..maximumBytes) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use {
                val saved = it.readLong()
                val checksum = ByteArray(32).apply(it::readFully)
                val data = it.readBytes()
                check(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(data)))
                Entry(saved, data)
            }
        }.onFailure { file.delete() }.getOrNull()?.also { file.setLastModified(now()) }
    }

    private fun write(key: String, entry: Entry) {
        val folder = directory
        if (folder == null) {
            memory[key] = entry
            if (memory.values.sumOf { it.data.size.toLong() } > maximumBytes) { memory.clear(); memory[key] = entry }
            return
        }
        runCatching {
            check(folder.isDirectory || folder.mkdirs())
            val temporary = File.createTempFile(".response-", ".tmp", folder)
            try {
                DataOutputStream(temporary.outputStream().buffered()).use {
                    it.writeLong(entry.saved)
                    it.write(MessageDigest.getInstance("SHA-256").digest(entry.data))
                    it.write(entry.data)
                }
                val target = File(folder, key)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target.setLastModified(now())
            } finally { temporary.delete() }
            val files = folder.listFiles().orEmpty().filter { it.isFile }.sortedBy(File::lastModified)
            var bytes = files.sumOf(File::length)
            for (file in files) if (bytes > maximumBytes) { val size = file.length(); if (file.delete()) bytes -= size }
        }
    }

    companion object {
        fun key(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
            .digest(JSONArray(parts.toList()).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
