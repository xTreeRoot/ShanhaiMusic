package com.xtreeroot.mb.plugin

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal enum class PluginRedirectMode { Follow, Manual, Error }

private val redirectStatuses = setOf(301, 302, 303, 307, 308)

internal data class PluginFetchRequest(val url: URI, val method: String, val headers: Map<String, String>, val body: String?,
                                       val redirectMode: PluginRedirectMode = PluginRedirectMode.Follow,
                                       val maximumResponseBytes: Int = responseLimit) {
    fun redirect(status: Int, location: String?): PluginFetchRequest? {
        if (status !in redirectStatuses || redirectMode == PluginRedirectMode.Manual) return null
        if (redirectMode == PluginRedirectMode.Error) throw PluginException("Unexpected plugin redirect")
        val target = url.resolve(uri(location ?: throw PluginException("Missing redirect location")))
        if (!target.isHttp() || (url.scheme.equals("https", true) && !target.scheme.equals("https", true))) {
            throw PluginException("Invalid plugin redirect")
        }
        fun URI.effectivePort() = if (port != -1) port else if (scheme.equals("https", true)) 443 else 80
        val sameOrigin = url.host.equals(target.host, true) && url.scheme.equals(target.scheme, true) && url.effectivePort() == target.effectivePort()
        val preserve = status == 307 || status == 308
        if (preserve && !sameOrigin && body != null) throw PluginException("Plugin redirect cannot forward a request body to another origin")
        val nextBody = if (preserve) body else null
        val nextHeaders = if (!sameOrigin) emptyMap() else headers.filterKeys { nextBody != null || !it.equals("content-type", true) }
        val nextMethod = if (!preserve && (status == 303 || method == "POST")) "GET" else method
        return copy(url = target, method = nextMethod, headers = nextHeaders, body = nextBody)
    }

    companion object {
        fun parse(event: JSONObject, manifest: PluginManifest): PluginFetchRequest {
            val url = uri(event.opt("url") as? String ?: throw PluginException("Invalid plugin URL"))
            requireNetworkPermission(url, manifest)
            val headers = event.optJSONObject("headers")?.let { entries ->
                entries.keys().asSequence().associateWith { name ->
                    val value = entries.opt(name) as? String ?: throw PluginException("Invalid header value")
                    if (!allowedPluginHeader(name) ||
                        value.any { it == '\r' || it == '\n' }) throw PluginException("Invalid plugin request header")
                    value
                }
            }.orEmpty()
            val body = event.opt("body") as? String
            if (body != null && body.encodeToByteArray().size > 8 * 1024 * 1024) throw PluginException("Plugin request body too large")
            val method = (event.opt("method") as? String ?: "GET").uppercase(Locale.ROOT)
            val redirectMode = when (event.opt("redirect")) {
                null, "follow" -> PluginRedirectMode.Follow
                "manual" -> PluginRedirectMode.Manual
                "error" -> PluginRedirectMode.Error
                else -> throw PluginException("Invalid fetch redirect mode")
            }
            return PluginFetchRequest(url, method, headers, body, redirectMode)
        }
    }
}

internal data class PluginFetchResponse(
    val status: Int,
    val url: URI,
    val headers: Map<String, String>,
    val cookies: List<String>,
    val bytes: ByteArray,
) {
    fun toJson(): JSONObject {
        if (bytes.size > responseLimit) throw PluginException("Plugin response body too large")
        return JSONObject().put("status", status).put("url", url.toASCIIString()).put("headers", JSONObject(headers))
            .put("setCookies", JSONArray(cookies)).put("body", bytes.decodeToString())
            .put("base64", Base64.getEncoder().encodeToString(bytes))
    }
}

internal fun interface PluginTransport {
    suspend fun fetch(request: PluginFetchRequest, manifest: PluginManifest): PluginFetchResponse
}

internal const val pluginNetworkCode = "network"

internal class PluginHttpTransport(proxySelector: ProxySelector? = null, callTimeoutSeconds: Long = 30) : PluginTransport {
    private val client = OkHttpClient.Builder().let { builder ->
        if (proxySelector == null) builder.proxy(Proxy.NO_PROXY) else builder.proxySelector(proxySelector)
    }.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS).build()

    override suspend fun fetch(request: PluginFetchRequest, manifest: PluginManifest): PluginFetchResponse =
        receive(request, { manifest.reaches(it.host.orEmpty()) }) { response, url -> response.read(url, request.maximumResponseBytes) }

    suspend fun <Value> receive(request: PluginFetchRequest, permits: (URI) -> Boolean, read: (Response, URI) -> Value): Value {
        var current = request
        repeat(10) {
            if (!current.url.isHttp() || !permits(current.url)) throw PluginException("Request outside plugin network permissions")
            val body = current.body?.toRequestBody() ?: if (current.method in methodsRequiringBody) "".toRequestBody() else null
            val builder = Request.Builder().url(current.url.toASCIIString()).method(current.method, body)
            current.headers.forEach(builder::header)
            when (val exchange = exchange(builder.build(), current, read)) {
                is Exchange.Redirect -> current = exchange.request
                is Exchange.Result -> return exchange.response
            }
        }
        throw PluginException("Too many plugin redirects")
    }

    private sealed interface Exchange<out Value> {
        data class Redirect(val request: PluginFetchRequest) : Exchange<Nothing>
        data class Result<Value>(val response: Value) : Exchange<Value>
    }

    private suspend fun <Value> exchange(request: Request, current: PluginFetchRequest,
                                         read: (Response, URI) -> Value): Exchange<Value> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(networkFailure(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        val redirect = current.redirect(response.code, response.header("Location"))
                        if (redirect != null) Exchange.Redirect(redirect) else Exchange.Result(read(response, current.url))
                    }
                }.recoverCatching { failure ->
                    throw if (failure is IOException) networkFailure(failure) else failure
                })
            }
        })
    }

    private fun networkFailure(cause: IOException) = PluginException("Plugin network request failed", pluginNetworkCode, cause)

    private fun Response.read(url: URI, maximumBytes: Int): PluginFetchResponse {
        val headers = headers.names().associate { name -> name.lowercase(Locale.ROOT) to headers(name).joinToString(", ") }
        val bytes = body?.let { incoming ->
            if (incoming.contentLength() > maximumBytes) throw PluginException("Plugin response body too large")
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            incoming.byteStream().use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (data.size() + count > maximumBytes) throw PluginException("Plugin response body too large")
                    data.write(buffer, 0, count)
                }
            }
            data.toByteArray()
        } ?: byteArrayOf()
        return PluginFetchResponse(code, url, headers, headers("Set-Cookie"), bytes)
    }

    companion object {
        private val methodsRequiringBody = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}

private const val responseLimit = 16 * 1024 * 1024

private fun requireNetworkPermission(url: URI, manifest: PluginManifest) {
    if (!url.isHttp() || !manifest.reaches(url.host.orEmpty())) throw PluginException("Request outside plugin network permissions")
}
