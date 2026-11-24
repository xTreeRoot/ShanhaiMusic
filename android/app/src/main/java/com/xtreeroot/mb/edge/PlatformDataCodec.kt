package com.xtreeroot.mb.edge

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal const val maxPlatformEnvelope = 128 * 1024
private val identifier = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private val base64Url = Base64.getUrlEncoder().withoutPadding()

internal data class PlatformDataKey(val platform: String, val info: String) {
    init { require(identifier.matches(platform) && identifier.matches(info)) }
}

internal data class PlatformEnvelope(val format: Int, val nonce: String, val ciphertext: String,
                                     private val maximumBytes: Int = maxPlatformEnvelope) {
    init {
        require(format in 1..65535)
        require(nonce.length == 16 && decodeBase64(nonce).size == 12)
        require(ciphertext.length in 23..maximumBytes && decodeBase64(ciphertext).size > 16)
    }

    fun encode(): String = JSONObject().put("format", format).put("nonce", nonce).put("ciphertext", ciphertext)
        .toString().also { require(it.length <= maximumBytes) }

    companion object {
        fun parse(value: JSONObject, maximumBytes: Int = maxPlatformEnvelope): PlatformEnvelope = PlatformEnvelope(
            value.get("format") as? Int ?: throw IllegalArgumentException("Invalid envelope format"),
            value.get("nonce") as? String ?: throw IllegalArgumentException("Invalid envelope nonce"),
            value.get("ciphertext") as? String ?: throw IllegalArgumentException("Invalid envelope ciphertext"),
            maximumBytes,
        )
    }
}

internal class PlatformDataCodec(recoveryKey: String, private val space: String,
                                 private val maximumBytes: Int = maxPlatformEnvelope,
                                 private val nonce: () -> ByteArray = { ByteArray(12).also(SecureRandom()::nextBytes) }) {
    private val key = SecretKeySpec(derivePlatformKey(recoveryKey, space), "AES")

    fun seal(path: PlatformDataKey, format: Int, value: JSONObject): PlatformEnvelope {
        val plain = value.toString().encodeToByteArray()
        require(value.has("value") && plain.size <= maximumBytes)
        val iv = nonce()
        val bytes = cipher(Cipher.ENCRYPT_MODE, path, format, iv).doFinal(plain)
        return PlatformEnvelope(format, base64Url.encodeToString(iv), base64Url.encodeToString(bytes), maximumBytes).also { it.encode() }
    }

    fun open(path: PlatformDataKey, format: Int, envelope: PlatformEnvelope): JSONObject {
        require(envelope.format == format) { "Unsupported platform data format" }
        val bytes = cipher(Cipher.DECRYPT_MODE, path, format, decodeBase64(envelope.nonce))
            .doFinal(decodeBase64(envelope.ciphertext))
        return JSONObject(bytes.decodeToString(throwOnInvalidSequence = true)).also { require(it.has("value")) }
    }

    private fun cipher(mode: Int, path: PlatformDataKey, format: Int, iv: ByteArray): Cipher {
        require(iv.size == 12 && format in 1..65535)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD("mb-data-v1\n$space\n${path.platform}\n${path.info}\n$format".encodeToByteArray())
        }
    }
}

internal fun derivePlatformKey(recoveryKey: String, space: String, purpose: String = "platform-data"): ByteArray {
    require(space.isNotBlank() && '\n' !in space && '\r' !in space)
    val material = decodeBase64(recoveryKey)
    require(material.size == 32)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec("mb-sync-v1\n$space".encodeToByteArray(), "HmacSHA256"))
    val extracted = mac.doFinal(material)
    mac.init(SecretKeySpec(extracted, "HmacSHA256"))
    return mac.doFinal("$purpose\u0001".encodeToByteArray())
}

internal fun decodeBase64(value: String): ByteArray = Base64.getUrlDecoder().decode(value).also {
    require(base64Url.encodeToString(it) == value) { "Invalid base64url encoding" }
}
