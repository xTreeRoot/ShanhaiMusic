package com.xtreeroot.mb.edge

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal class NodeSession(val format: Int, val accountId: String, val label: String?, val data: JSONObject) {
    init { require(format in 1..65535 && accountId.isNotEmpty() && accountId.encodeToByteArray().size <= 1024) }

    fun payload(): JSONObject = JSONObject().put("account_id", accountId).put("session", data).apply {
        if (label != null) put("account_label", label)
    }

    fun arguments(verified: JSONObject? = null): JSONObject = payload().put("format", format).apply {
        if (verified != null) put("account", verified)
    }

    companion object {
        fun parse(value: JSONObject, format: Int): NodeSession = NodeSession(format, value.getString("account_id"),
            value.optString("account_label").takeIf(String::isNotEmpty), value.getJSONObject("session"))
    }
}

internal class NodeSessionCodec(private val binding: NodeBinding, private val platform: String) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init { PlatformDataKey(platform, "sessions") }

    fun reference(account: String): String = encoder.encodeToString(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(derivePlatformKey(binding.recoveryKey, binding.space, "account-ref"), "HmacSHA256"))
        doFinal("$platform\n$account".encodeToByteArray())
    })

    fun seal(value: NodeSession, ref: String): PlatformEnvelope {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val plain = value.payload().toString().encodeToByteArray()
        require(plain.size <= maxPlatformEnvelope)
        val sealed = cipher(Cipher.ENCRYPT_MODE, value.format, ref, nonce).doFinal(plain)
        return PlatformEnvelope(value.format, encoder.encodeToString(nonce), encoder.encodeToString(sealed))
    }

    fun open(envelope: PlatformEnvelope, ref: String): NodeSession {
        val plain = cipher(Cipher.DECRYPT_MODE, envelope.format, ref, decodeBase64(envelope.nonce)).doFinal(decodeBase64(envelope.ciphertext))
        return NodeSession.parse(JSONObject(plain.decodeToString(throwOnInvalidSequence = true)), envelope.format).also {
            require(reference(it.accountId) == ref)
        }
    }

    private fun cipher(mode: Int, format: Int, ref: String, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(derivePlatformKey(binding.recoveryKey, binding.space, "session"), "AES"), GCMParameterSpec(128, nonce))
        updateAAD("mb-session-v1\n${binding.space}\n$platform\n$ref\n$format".encodeToByteArray())
    }
}
