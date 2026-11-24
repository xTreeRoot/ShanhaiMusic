package com.xtreeroot.mb.playback

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 可重新获取的授权音频缓存；只访问独立 cacheDir，不保存内容密钥或账号。 */
internal class PreparedCencAudio(private val directory: File) {
    private val lock = Mutex()

    suspend fun prepare(address: String, headers: Map<String, String>, key: ByteArray, fetch: suspend () -> ByteArray): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val identity = buildString {
                append(address).append('\u0000').append(key.joinToString("") { "%02x".format(it) })
                headers.toSortedMap().forEach { (name, value) -> append('\u0000').append(name).append('\u0000').append(value) }
            }
            val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            val target = File(directory, "$name.m4a")
            currentCoroutineContext().ensureActive()
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val context = currentCoroutineContext()
            val decoded = CencAudio.decode(fetch(), key) { context.ensureActive() }
            context.ensureActive()
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create prepared audio cache" }
            val temporary = File(directory, ".${UUID.randomUUID()}.partial")
            try {
                temporary.outputStream().use { it.write(decoded); it.fd.sync() }
                context.ensureActive()
                check(temporary.renameTo(target)) { "Cannot save prepared audio" }
            } finally { temporary.delete() }
            prune(target)
            target
        }
    }

    private fun prune(current: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "m4a" }?.sortedBy(File::lastModified) ?: return
        var size = files.sumOf(File::length)
        for (file in files) {
            if (size <= 256L shl 20) break
            val bytes = file.length()
            if (file != current && System.currentTimeMillis() - file.lastModified() > 3_600_000 && file.delete()) size -= bytes
        }
    }
}
