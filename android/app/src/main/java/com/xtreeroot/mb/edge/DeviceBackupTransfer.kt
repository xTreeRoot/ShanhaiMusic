package com.xtreeroot.mb.edge

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val maxBackupObject = 4 * 1024 * 1024
internal const val backupChunkBytes = 2 * 1024 * 1024
internal const val backupArchiveLimit = 16L * 1024 * 1024 * 1024
internal fun validBackupKey(key: String) = Regex("[a-f0-9]{32}").matches(key)
internal fun NodeBinding.backupOwner() = device.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

internal data class DeviceBackupReference(val index: String, val platform: String, val created: Long,
                                        val bytes: Long, val sha256: String, val plugins: Int) {
    init {
        require(validBackupKey(index) && Regex("[a-f0-9]{64}").matches(sha256) && bytes in 1..backupArchiveLimit &&
            platform in listOf("desktop", "ios", "android") && plugins >= 0)
    }
    fun encode() = JSONObject().put("index",index).put("platform",platform).put("created",created)
        .put("bytes",bytes).put("sha256",sha256).put("plugins",plugins)
    companion object {
        fun decode(value: JSONObject) = DeviceBackupReference(value.getString("index"),value.getString("platform"),
            value.getLong("created"),value.getLong("bytes"),value.getString("sha256"),value.getInt("plugins"))
    }
}

/** ZIP 只在暂存目录读写；下载的所有认证分块、总长度和 SHA-256 通过后才交给恢复流程。 */
internal class DeviceBackupTransfer(private val client: NodeClient, private val node: NodeBinding) {
    private val codec = PlatformDataCodec(node.recoveryKey, node.space, maximumBytes = maxBackupObject)
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    private suspend fun put(bytes: ByteArray): String {
        require(bytes.size <= backupChunkBytes)
        val key = UUID.randomUUID().toString().replace("-", "")
        val envelope = codec.seal(PlatformDataKey("backup-${node.backupOwner()}", key), 1,
            JSONObject().put("value", JSONObject().put("data", base64.encodeToString(bytes))))
        try { client.putBackup(node,key,envelope) }
        catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { client.deleteBackup(node,key) } }
            throw failure
        }
        return key
    }

    private suspend fun get(owner: String, key: String): ByteArray {
        val envelope = client.getBackup(node,owner,key)
        val payload = codec.open(PlatformDataKey("backup-$owner",key),1,envelope).getJSONObject("value")
        return decodeBase64(payload.getString("data")).also { require(it.size <= backupChunkBytes) }
    }

    suspend fun upload(file: File, platform: String, plugins: Int): DeviceBackupReference = withContext(Dispatchers.IO) {
        val keys = mutableListOf<String>()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(backupChunkBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var count = 0
                    while (count < buffer.size) {
                        val n = input.read(buffer,count,buffer.size-count)
                        if (n < 0) break
                        count += n
                    }
                    if (count == 0) break
                    bytes += count
                    require(bytes <= backupArchiveLimit)
                    hash.update(buffer,0,count)
                    keys += put(buffer.copyOf(count))
                    if (count < buffer.size) break
                }
            }
            require(bytes > 0)
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            val index = JSONObject().put("version",1).put("parts",JSONArray(keys)).put("bytes",bytes).put("sha256",digest)
            val key = put(index.toString().toByteArray(Charsets.UTF_8));keys += key
            DeviceBackupReference(key,platform,System.currentTimeMillis()/1000,bytes,digest,plugins)
        } catch (failure: Exception) {
            withContext(NonCancellable) { keys.forEach { runCatching { client.deleteBackup(node,it) } } }
            throw failure
        }
    }

    private suspend fun index(owner: String, ref: DeviceBackupReference): List<String> {
        val value = JSONObject(get(owner,ref.index).toString(Charsets.UTF_8))
        require(value.getInt("version")==1 && value.getLong("bytes")==ref.bytes && value.getString("sha256")==ref.sha256)
        val rows = value.getJSONArray("parts")
        val keys = (0 until rows.length()).map(rows::getString)
        require(keys.size.toLong()==(ref.bytes+backupChunkBytes-1)/backupChunkBytes && keys.toSet().size==keys.size &&
            keys.all(::validBackupKey) && ref.index !in keys)
        return keys
    }

    suspend fun download(owner: String, ref: DeviceBackupReference, file: File) = withContext(Dispatchers.IO) {
        val keys = index(owner,ref)
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.outputStream().use { output ->
            for (key in keys) {
                currentCoroutineContext().ensureActive()
                val data = get(owner,key)
                require(data.size.toLong()==minOf(backupChunkBytes.toLong(),ref.bytes-bytes))
                output.write(data);hash.update(data);bytes += data.size
            }
            output.fd.sync()
        }
        require(bytes==ref.bytes && hash.digest().joinToString("") { "%02x".format(it) } == ref.sha256)
    }

    suspend fun delete(ref: DeviceBackupReference) {
        val keys = index(node.backupOwner(),ref)+ref.index
        for (key in keys) client.deleteBackup(node,key)
    }
}
