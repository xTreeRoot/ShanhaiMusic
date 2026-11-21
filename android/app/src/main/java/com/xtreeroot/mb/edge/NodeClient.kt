package com.xtreeroot.mb.edge

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal class NodeBinding(val origin: HttpUrl, val device: String, val space: String, val token: String,
                           val expiresAt: Long, val recoveryKey: String, val catalogPublicKey: String) {
    init {
        require(origin == nodeOrigin(origin.toString()) && expiresAt > 0)
        require(device.isNotBlank() && space.isNotBlank() && space.none { it.isISOControl() })
        require(decodeBase64(recoveryKey).size == 32 && decodeBase64(catalogPublicKey).size == 32)
        require(token.isNotEmpty() && token.length <= 512 && decodeBase64(token).isNotEmpty())
    }
}

internal fun nodeOrigin(input: String): HttpUrl {
    val address = input.trim()
    val url = (if ("://" in address) address else "https://$address").toHttpUrlOrNull()
    require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
        url.fragment == null && url.encodedPath == "/" && '@' !in address) { "Invalid HTTPS node address" }
    return url
}

internal enum class NodeFailure { Unauthorized, BadPassword, Unsupported, Protocol, Conflict, Rejected, Retryable, Deleted, OtherAccount, KeyChanged }

internal class NodeException(val failure: NodeFailure, val status: Int? = null) : IOException(failure.name)

internal sealed interface PlatformRecord {
    data object Missing : PlatformRecord
    data class Deleted(val tag: String) : PlatformRecord
    data class Present(val tag: String, val envelope: PlatformEnvelope) : PlatformRecord
}

internal class NodeClient(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).retryOnConnectionFailure(false).cache(null).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun join(origin: HttpUrl, password: String, name: String): NodeBinding {
        require(origin == nodeOrigin(origin.toString()))
        val body = JSONObject().put("password", password).put("name", name).put("platform", "android")
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/join").build())
            .post(body.toString().toRequestBody(jsonType)).build()
        return exchange(request) { response ->
            if (response.code != 201) throw if (response.code == 403) NodeException(NodeFailure.BadPassword, 403)
                else response.failure()
            parseResponse {
                val value = response.json()
                NodeBinding(origin, value.getString("device_id"), value.getString("space_id"), value.getString("token"),
                    value.getLong("expires_at"), value.getString("recovery_key"), value.getString("catalog_public_key"))
            }
        }
    }

    suspend fun read(binding: NodeBinding, key: PlatformDataKey): PlatformRecord = exchange(request(binding, key).build()) { response ->
        when (response.code) {
            200 -> parseResponse { PlatformRecord.Present(response.tag(), PlatformEnvelope.parse(response.json())) }
            404 -> if (response.errorCode() == "not_found") PlatformRecord.Missing else throw response.failure()
            410 -> if (response.errorCode() == "deleted") PlatformRecord.Deleted(response.tag()) else throw response.failure()
            else -> throw response.failure()
        }
    }

    suspend fun create(binding: NodeBinding, key: PlatformDataKey, envelope: PlatformEnvelope): String =
        change(request(binding, key).header("If-None-Match", "*").put(envelope.encode().toRequestBody(jsonType)), 201)

    suspend fun replace(binding: NodeBinding, key: PlatformDataKey, tag: String, envelope: PlatformEnvelope): String =
        change(request(binding, key).header("If-Match", verifiedTag(tag)).put(envelope.encode().toRequestBody(jsonType)), 204)

    suspend fun delete(binding: NodeBinding, key: PlatformDataKey, tag: String): String =
        change(request(binding, key).header("If-Match", verifiedTag(tag)).delete(), 204)

    private suspend fun change(request: Request.Builder, expected: Int): String = exchange(request.build()) { response ->
        if (response.code != expected) throw response.failure()
        response.tag()
    }

    private fun request(binding: NodeBinding, key: PlatformDataKey): Request.Builder {
        if (binding.expiresAt <= System.currentTimeMillis() / 1_000) throw NodeException(NodeFailure.Unauthorized)
        val address = binding.origin.newBuilder().addPathSegments("v1/data").addPathSegment(key.platform).addPathSegment(key.info).build()
        return Request.Builder().url(address).header("Authorization", "Bearer ${binding.token}")
            .header("Cache-Control", "no-store")
    }

    private suspend fun <Result> exchange(request: Request, read: (Response) -> Result): Result =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(NodeException(NodeFailure.Retryable))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(read) }
                    result.fold(continuation::resume) { failure ->
                        continuation.resumeWithException(if (failure is IOException && failure !is NodeException)
                            NodeException(NodeFailure.Retryable) else failure)
                    }
                }
            })
        }

    private fun Response.json(): JSONObject {
        val source = body?.source() ?: throw NodeException(NodeFailure.Protocol, code)
        if (source.request(maxPlatformEnvelope.toLong() + 1)) throw NodeException(NodeFailure.Protocol, code)
        return JSONObject(source.readByteArray().decodeToString(throwOnInvalidSequence = true))
    }

    private fun Response.errorCode(): String? = try { json().optString("error") }
        catch (_: org.json.JSONException) { null }
        catch (_: java.nio.charset.CharacterCodingException) { null }

    private fun Response.tag(): String = parseResponse { verifiedTag(header("ETag").orEmpty()) }

    private fun Response.failure() = NodeException(when (code) {
        401 -> NodeFailure.Unauthorized
        404 -> NodeFailure.Unsupported
        412 -> NodeFailure.Conflict
        408, 429, in 500..599 -> NodeFailure.Retryable
        in 400..499 -> if (code == 404 || code == 410) NodeFailure.Protocol else NodeFailure.Rejected
        else -> NodeFailure.Protocol
    }, code)

    private inline fun <Result> parseResponse(parse: () -> Result): Result = try { parse() }
        catch (_: IllegalArgumentException) { throw NodeException(NodeFailure.Protocol) }
        catch (_: org.json.JSONException) { throw NodeException(NodeFailure.Protocol) }
        catch (_: java.nio.charset.CharacterCodingException) { throw NodeException(NodeFailure.Protocol) }

    private fun verifiedTag(tag: String): String = tag.also { require(strongTag.matches(it)) { "Invalid node revision" } }

    private companion object {
        val jsonType = "application/json".toMediaType()
        val strongTag = Regex("\"[A-Za-z0-9_-]{1,128}\"")
    }
}
