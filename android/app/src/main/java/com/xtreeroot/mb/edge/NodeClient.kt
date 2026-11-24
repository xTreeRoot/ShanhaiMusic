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

internal enum class NodeFailure { Unauthorized, BadPassword, Unsupported, Protocol, Conflict, Rejected, Retryable, Deleted, OtherAccount, KeyChanged, UntrustedPlugin }

internal class NodeException(val failure: NodeFailure, val status: Int? = null) : IOException(failure.name)
internal data class CloudRecord(val kind: String, val platform: String, val key: String?, val count: Int)
internal data class SessionRecord(val ref: String, val tag: String, val format: Int, val deleted: Boolean)

internal sealed interface PlatformRecord {
    data object Missing : PlatformRecord
    data class Deleted(val tag: String) : PlatformRecord
    data class Present(val tag: String, val envelope: PlatformEnvelope) : PlatformRecord
}

internal class NodeClient(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).retryOnConnectionFailure(false).cache(null).callTimeout(20, TimeUnit.SECONDS).build()

    private val backupClient = client.newBuilder().callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()

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

    suspend fun renameDevice(binding: NodeBinding, name: String) {
        exchange(request(binding, "v1/sync/device").patch(JSONObject().put("name", name.trim()).toString().toRequestBody(jsonType)).build()) { response ->
            if (response.code != 204) throw response.failure()
        }
    }

    suspend fun settingsDevices(binding: NodeBinding): List<SettingsDevice> =
        exchange(request(binding, "v1/sync/devices").build()) { response ->
            if (response.code != 200) throw response.failure()
            parseResponse {
                val rows = response.json().getJSONArray("devices")
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    SettingsDevice(row.getString("key"), row.getString("name"), row.getLong("updated_at"),row.getBoolean("current"))
                }
            }
        }

    private fun backupPath(owner: String, key: String): String {
        require(Regex("[a-f0-9]{44}").matches(owner) && validBackupKey(key))
        return "v1/backups/$owner/$key"
    }

    suspend fun putBackup(binding: NodeBinding, key: String, envelope: PlatformEnvelope) {
        val body = envelope.encode()
        require(body.length <= maxBackupObject)
        exchange(request(binding, backupPath(binding.backupOwner(), key)).put(body.toRequestBody(jsonType)).build()) { response ->
            if (response.code != 201 && response.code != 204) throw response.failure()
        }
    }

    suspend fun getBackup(binding: NodeBinding, owner: String, key: String): PlatformEnvelope =
        exchange(request(binding, backupPath(owner, key)).build()) { response ->
            if (response.code != 200) throw response.failure()
            parseResponse { PlatformEnvelope.parse(response.json(maxBackupObject), maxBackupObject) }
        }

    suspend fun deleteBackup(binding: NodeBinding, key: String) {
        exchange(request(binding, backupPath(binding.backupOwner(), key)).delete().build()) { response ->
            if (response.code != 204) throw response.failure()
        }
    }

    suspend fun overview(binding: NodeBinding): List<CloudRecord> = exchange(request(binding, "v1/sync/overview").build()) { response ->
        if (response.code != 200) throw response.failure()
        parseResponse {
            val records = response.json().getJSONArray("records")
            List(records.length()) { index ->
                val value = records.getJSONObject(index)
                val record = CloudRecord(value.getString("kind"), value.getString("platform"),
                    value.optString("key").takeIf(String::isNotEmpty), value.getInt("count"))
                PlatformDataKey(record.platform, record.key ?: "sessions")
                require(record.kind == "sessions" && record.key == null && record.count > 0 ||
                    record.kind == "data" && record.key != null && record.count == 1)
                record
            }.also { records -> require(records.distinctBy { Triple(it.kind, it.platform, it.key) }.size == records.size) }
        }
    }

    suspend fun verifySessionPlugin(binding: NodeBinding, id: String, version: String, digest: String) {
        val url = binding.origin.newBuilder().addPathSegments("v1/catalog").build()
        exchange(Request.Builder().url(url).header("Cache-Control", "no-store").build()) { response ->
            if (response.code != 200) throw response.failure()
            val allowed = runCatching { NodePluginContent.matches(response.json(1 shl 20), binding.catalogPublicKey, id, version, digest) }.getOrDefault(false)
            if (!allowed) throw NodeException(NodeFailure.UntrustedPlugin)
        }
    }

    suspend fun sessions(binding: NodeBinding, platform: String): List<SessionRecord> = exchange(sessionRequest(binding, platform).build()) { response ->
        if (response.code != 200) throw response.failure()
        parseResponse {
            val records = response.json().getJSONArray("sessions")
            List(records.length()) { index ->
                val value = records.getJSONObject(index)
                SessionRecord(value.getString("account_ref"), verifiedTag(value.getString("etag")), value.getInt("format"), value.getBoolean("deleted")).also {
                    require(decodeBase64(it.ref).size == 32 && if (it.deleted) it.format == 0 else it.format in 1..65535)
                }
            }.also { records -> require(records.distinctBy { it.ref }.size == records.size) }
        }
    }

    suspend fun fetchSession(binding: NodeBinding, platform: String, record: SessionRecord): PlatformEnvelope =
        exchange(sessionRequest(binding, platform, record.ref).build()) { response ->
            if (response.code != 200) throw response.failure()
            if (response.tag() != record.tag) throw NodeException(NodeFailure.Conflict)
            parseResponse { PlatformEnvelope.parse(response.json()).also { require(it.format == record.format) } }
        }

    suspend fun publishSession(binding: NodeBinding, platform: String, ref: String, previous: SessionRecord?, envelope: PlatformEnvelope) {
        if (previous?.deleted == true) throw NodeException(NodeFailure.Deleted)
        val request = sessionRequest(binding, platform, ref).put(envelope.encode().toRequestBody(jsonType))
        if (previous == null) change(request.header("If-None-Match", "*"), 201)
        else change(request.header("If-Match", previous.tag), 204)
    }

    private fun sessionRequest(binding: NodeBinding, platform: String, ref: String? = null): Request.Builder {
        PlatformDataKey(platform, "sessions")
        if (ref != null) require(decodeBase64(ref).size == 32)
        return request(binding, "v1/sessions/$platform" + (ref?.let { "/$it" } ?: ""))
    }

    private suspend fun change(request: Request.Builder, expected: Int): String = exchange(request.build()) { response ->
        if (response.code != expected) throw response.failure()
        response.tag()
    }

    private fun request(binding: NodeBinding, key: PlatformDataKey): Request.Builder = request(binding, "v1/data/${key.platform}/${key.info}")

    private fun request(binding: NodeBinding, path: String): Request.Builder {
        if (binding.expiresAt <= System.currentTimeMillis() / 1_000) throw NodeException(NodeFailure.Unauthorized)
        val address = binding.origin.newBuilder().addPathSegments(path).build()
        return Request.Builder().url(address).header("Authorization", "Bearer ${binding.token}")
            .header("Cache-Control", "no-store")
    }

    private suspend fun <Result> exchange(request: Request, read: (Response) -> Result): Result =
        suspendCancellableCoroutine { continuation ->
            val transport = if (request.url.encodedPath.startsWith("/v1/backups/")) backupClient else client
            val call = transport.newCall(request)
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

    private fun Response.json(limit: Int = maxPlatformEnvelope): JSONObject {
        val source = body?.source() ?: throw NodeException(NodeFailure.Protocol, code)
        if (source.request(limit.toLong() + 1)) throw NodeException(NodeFailure.Protocol, code)
        return JSONObject(source.readByteArray().decodeToString(throwOnInvalidSequence = true))
    }

    private fun Response.errorCode(): String? = try { json().optString("error") }
        catch (_: org.json.JSONException) { null }
        catch (_: java.nio.charset.CharacterCodingException) { null }

    private fun Response.tag(): String = parseResponse { verifiedTag(header("ETag").orEmpty()) }

    private fun Response.failure() = NodeException(when (code) {
        401 -> NodeFailure.Unauthorized
        404 -> NodeFailure.Unsupported
        410 -> NodeFailure.Deleted
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
