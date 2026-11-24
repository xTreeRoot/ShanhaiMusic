package com.xtreeroot.mb.plugin

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal const val pluginOAuthRedirectScheme = "com.xtreeroot.mb"
internal const val pluginOAuthRedirect = "$pluginOAuthRedirectScheme:/oauth2redirect"
internal const val oauthResponseLimit = 64 * 1024

internal class PluginOAuthRequest private constructor(
    val title: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val clientId: String,
    val scopes: List<String>,
) {
    companion object {
        fun parse(event: JSONObject, manifest: PluginManifest): PluginOAuthRequest {
            val options = event.optJSONObject("options") ?: throw PluginException("Invalid OAuth request")
            fun endpoint(key: String): URI {
                val address = (options.opt(key) as? String)?.takeIf { it.length <= 4096 }?.let(::uri)
                return address?.takeIf { it.isHttp() && it.scheme.equals("https", true) && it.rawFragment == null &&
                    (it.port == -1 || it.port == 443) && manifest.reaches(it.host.orEmpty()) }
                    ?: throw PluginException("OAuth endpoint outside plugin network permissions")
            }
            val authorization = endpoint("authorizationEndpoint")
            val token = endpoint("tokenEndpoint")
            val client = (options.opt("clientID") as? String)?.takeIf { it.isOAuthText(512) }
                ?: throw PluginException("Invalid OAuth client")
            if (options.opt("redirectURI") != pluginOAuthRedirect) throw PluginException("OAuth callback is not registered for Shanhai Android")
            val values = options.optJSONArray("scopes")?.takeIf { it.length() in 1..16 }
                ?: throw PluginException("Invalid OAuth scopes")
            val scopes = List(values.length()) { index ->
                (values.opt(index) as? String)?.takeIf { it.isOAuthText(512) } ?: throw PluginException("Invalid OAuth scope")
            }
            if (scopes.distinct().size != scopes.size) throw PluginException("Duplicate OAuth scope")
            return PluginOAuthRequest(manifest.name, authorization, token, client, scopes)
        }
    }
}

internal fun oauthToken(response: PluginFetchResponse): JSONObject {
    val bytes = response.bytes
    val token = if (response.status == 200 && bytes.size <= oauthResponseLimit) {
        runCatching { JSONObject(bytes.decodeToString(throwOnInvalidSequence = true)) }.getOrNull()
    } else null
    if (token == null || !(token.opt("access_token") as? String).orEmpty().isOAuthText(oauthResponseLimit) ||
        !(token.opt("token_type") as? String).equals("bearer", true)) throw PluginException("OAuth server did not return a valid token")
    return token
}

private fun String.isOAuthText(limit: Int): Boolean = length in 1..limit && none { it.isWhitespace() || it.isISOControl() }

private class PluginOAuthFlow(private val request: PluginOAuthRequest) {
    private val verifier = nonce()
    private val state = nonce()

    val authorizationUrl: URI = request.authorizationEndpoint.toASCIIString().toHttpUrl().newBuilder().query(null)
        .addQueryParameter("client_id", request.clientId)
        .addQueryParameter("redirect_uri", pluginOAuthRedirect)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", request.scopes.joinToString(" "))
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())))
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("access_type", "offline")
        .addQueryParameter("prompt", "consent")
        .build().toUri()

    fun exchange(callback: URI): Result<PluginFetchRequest>? {
        if (callback.rawFragment != null || callback.toASCIIString().substringBefore('?') != pluginOAuthRedirect) return null
        val query = callback.rawQuery?.takeIf { it.length <= 8192 } ?: return null
        val values = runCatching {
            query.split('&').map { pair ->
                val parts = pair.split('=', limit = 2)
                URLDecoder.decode(parts.first(), "UTF-8") to URLDecoder.decode(parts.getOrNull(1).orEmpty(), "UTF-8")
            }.groupBy({ it.first }, { it.second })
        }.getOrNull() ?: return null
        val supplied = values["state"]?.singleOrNull() ?: return null
        if (!MessageDigest.isEqual(state.encodeToByteArray(), supplied.encodeToByteArray())) return null
        if ("error" in values && "code" !in values && values["error"]?.singleOrNull()?.isOAuthText(256) == true) {
            return Result.failure(PluginException("OAuth authorization did not complete"))
        }
        if ("error" in values) return null
        val code = values["code"]?.singleOrNull()?.takeIf { it.isOAuthText(4096) } ?: return null
        val body = listOf("client_id" to request.clientId, "code" to code, "redirect_uri" to pluginOAuthRedirect,
            "grant_type" to "authorization_code", "code_verifier" to verifier).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return Result.success(PluginFetchRequest(request.tokenEndpoint, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body, PluginRedirectMode.Error, oauthResponseLimit))
    }

    companion object {
        private val random = SecureRandom()
        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun nonce() = base64Url(ByteArray(32).also(random::nextBytes))
    }
}

internal data class PluginOAuthPage(val id: UUID, val title: String, val host: String)

internal class PluginOAuthBroker {
    private class Attempt(request: PluginOAuthRequest) {
        val page = PluginOAuthPage(UUID.randomUUID(), request.title, request.authorizationEndpoint.host)
        val flow = PluginOAuthFlow(request)
        val result = CompletableDeferred<PluginFetchRequest>()
        val started = AtomicBoolean(false)
    }

    private val pending = MutableStateFlow<Attempt?>(null)
    val requests = pending.map { it?.page }

    suspend fun authorize(request: PluginOAuthRequest): PluginFetchRequest {
        val attempt = Attempt(request)
        if (!pending.compareAndSet(null, attempt)) throw PluginException("Another authorization is already open")
        try { return attempt.result.await() }
        finally { pending.compareAndSet(attempt, null) }
    }

    fun begin(id: UUID): URI? {
        val active = pending.value?.takeIf { it.page.id == id && !it.result.isCompleted } ?: return null
        return active.flow.authorizationUrl.takeIf { active.started.compareAndSet(false, true) }
    }

    fun callback(uri: URI): Boolean {
        val active = pending.value?.takeIf { it.started.get() && !it.result.isCompleted } ?: return false
        val response = active.flow.exchange(uri) ?: return false
        return response.fold(active.result::complete, active.result::completeExceptionally)
    }

    fun fail(id: UUID, message: String) {
        pending.value?.takeIf { it.page.id == id }?.result?.completeExceptionally(PluginException(message))
    }
}
