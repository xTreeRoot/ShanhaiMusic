package com.xtreeroot.mb.edge

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject

internal object NodePluginContent {
    private val portable = Regex("[A-Za-z0-9_./@+ -]+")
    private val hex = "0123456789abcdef"

    fun digest(directory: File): String {
        val root = directory.toPath()
        val files = sortedMapOf<String, ByteArray>()
        var remaining = 32L shl 20
        Files.walk(root).use { entries ->
            entries.forEach { file ->
                if (Files.isDirectory(file, NOFOLLOW_LINKS)) return@forEach
                val name = root.relativize(file).toString()
                if (name == ".origin.json" || name == ".mb-origin.json") return@forEach
                require(Files.isRegularFile(file, NOFOLLOW_LINKS) && portable.matches(name) && files.size < 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        remaining -= count
                        require(remaining >= 0)
                        digest.update(buffer, 0, count)
                    }
                }
                files[name] = digest.digest()
            }
        }
        require(files.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, content) ->
            digest.update(name.encodeToByteArray())
            digest.update(0.toByte())
            digest.update(content)
        }
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }

    fun matches(signed: JSONObject, publicKey: String, id: String, version: String, digest: String): Boolean {
        val key = decodeBase64(publicKey)
        val payload = decodeBase64(signed.getString("payload"))
        val signature = decodeBase64(signed.getString("signature"))
        if (key.size != 32 || signature.size != 64 || !Ed25519.verify(signature, 0, key, 0, payload, 0, payload.size)) return false
        val catalog = JSONObject(payload.decodeToString(throwOnInvalidSequence = true))
        if (catalog.getInt("protocol") != 1 || catalog.getLong("revision") < 1L) return false
        val entries = catalog.getJSONArray("plugins")
        val seen = mutableSetOf<String>()
        var found = false
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val entryId = entry.getString("id")
            if (!seen.add(entryId)) return false
            if (entryId == id && entry.getString("version") == version && entry.optString("content_sha256") == digest) found = true
        }
        return found
    }
}
