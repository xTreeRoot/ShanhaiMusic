package com.xtreeroot.mb.plugin

import android.content.res.AssetManager
import com.xtreeroot.mb.cache.DiskResponseCache
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.TrackUnavailableReason
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.coroutineContext

internal class PluginRuntime(
    private val assets: AssetManager,
    private val directory: File,
    private val dataDirectory: File,
    private val manifest: PluginManifest,
    settings: Map<String, String> = emptyMap(),
    language: String = "zh-CN",
    private val transport: PluginTransport = PluginHttpTransport(),
    private val webSignIn: suspend (PluginWebSignIn) -> String = { throw PluginException("Web sign-in is unavailable") },
    private val openURL: suspend (PluginWebLink) -> Unit = { throw PluginException("Opening web links is unavailable") },
    private val oauthSignIn: suspend (PluginOAuthRequest) -> PluginFetchRequest = { throw PluginException("OAuth is unavailable") },
) {
    val catalogCache = DiskResponseCache(File(dataDirectory, "catalog-cache"))
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val calls = Mutex()
    private var host: PluginHostState? = null
    private var settings = settings.toMap()
    private var language = language
    private var machine: Machine? = null
    private var closed = false

    private class Machine(val context: QuickJsContext) {
        val events = mutableMapOf<Int, Job>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var signInRequests: MutableSet<Int>? = null
        var signInDiagnostics: PluginSignInDiagnostics? = null
        var status = 0
        var failure: Exception? = null
    }

    suspend fun supports(name: String): Boolean = useMachine { it.context.supports(name) }

    suspend fun headersFor(address: URI, kind: String): Map<String, String> = withContext(dispatcher) {
        check(!closed) { "Plugin runtime is closed" }
        hostState().headers(address, manifest, kind)
    }

    suspend fun invoke(name: String, arguments: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Any = useMachine { runtime ->
        invoke(runtime, name, arguments, timeoutMs)
    }

    suspend fun stream(arguments: JSONObject): Any = useMachine { runtime ->
        if (!runtime.context.supports("stream")) {
            throw PlaybackIssueException(PlaybackIssue.SourceUnavailable, "Plugin does not provide audio streams")
        }
        invoke(runtime, "stream", arguments, 30_000)
    }

    suspend fun signIn(arguments: JSONObject = JSONObject(), prompt: (PluginPrompt) -> Unit): Any = useMachine { runtime ->
        val requests = mutableSetOf<Int>()
        val diagnostics = PluginSignInDiagnostics(dataDirectory)
        runtime.signInRequests = requests
        runtime.signInDiagnostics = diagnostics
        hostState().onPrompt = prompt
        var completed = false
        try { invoke(runtime, "signIn", arguments, 300_000).also { completed = true } }
        finally {
            runtime.signInDiagnostics = null
            diagnostics.finish(completed)
            if (requests.isNotEmpty()) reset()
            runtime.signInRequests = null
            hostState().onPrompt = null
        }
    }

    private suspend fun invoke(runtime: Machine, name: String, arguments: JSONObject, timeoutMs: Long): Any = withTimeout(timeoutMs) {
        runtime.status = runtime.context.start(name, arguments.toString())
        finish(runtime)
    }

    suspend fun configure(settings: Map<String, String>, language: String) = withContext(dispatcher) {
        check(!closed) { "Plugin runtime is closed" }
        this@PluginRuntime.settings = settings.toMap()
        this@PluginRuntime.language = language
        host?.let {
            it.settings = settings.toMap()
            it.language = language
        }
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        calls.withLock {
            closed = true
            reset()
            host = null
            scope.cancel()
        }
    }

    private suspend fun <T> useMachine(operation: suspend (Machine) -> T): T = withContext(dispatcher) {
        calls.withLock {
            check(!closed) { "Plugin runtime is closed" }
            try {
                machine?.failure?.let { reset() }
                operation(machine ?: load())
            } catch (error: Throwable) {
                reset()
                throw error
            }
        }
    }

    private fun hostState(): PluginHostState = host ?: PluginHostState(directory, dataDirectory, settings, language).also {
        it.loadStorage()
        host = it
    }

    private suspend fun load(): Machine {
        val state = hostState()
        val runtime = Machine(QuickJsContext(state)).also { machine = it }
        val bootstrap = assets.open("PluginBootstrap.js").bufferedReader().use { it.readText() }
        val platform = "platform: \"ios\", arch: \"arm64\""
        check(bootstrap.indexOf(platform) >= 0 && bootstrap.indexOf(platform) == bootstrap.lastIndexOf(platform)) {
            "Unsupported plugin bootstrap"
        }
        val arch = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm64"
            "armv7l", "armv8l", "arm" -> "arm"
            "amd64", "x86_64" -> "x64"
            "x86", "i686" -> "ia32"
            else -> "unknown"
        }
        withTimeout(10_000) {
            val android = """platform: "android", arch: "$arch", capabilities: Object.freeze({ webSignInHTML: true }),
                oauthSignIn: options => request({ kind: "oauthSignIn", options })"""
            runtime.status = runtime.context.evaluate(bootstrap.replace(platform, android), "host.js", false, 100)
            finish(runtime)
            val entry = JSONObject.quote("./${manifest.entry}")
            runtime.status = runtime.context.evaluate("export * from $entry;", ".mb-android-entry.js", true, 500)
            finish(runtime)
        }
        return runtime
    }

    private fun reset() {
        val runtime = machine ?: return
        machine = null
        runtime.events.values.forEach(Job::cancel)
        runtime.wake.close()
        runtime.context.close()
    }

    private suspend fun finish(runtime: Machine): Any {
        while (true) {
            coroutineContext.ensureActive()
            runtime.failure?.let { throw it }
            checkStatus(runtime)
            schedule(runtime)
            if (runtime.status > 0) return JSONTokener(runtime.context.result()).nextValue() ?: JSONObject.NULL
            runtime.wake.receive()
        }
    }

    private fun checkStatus(runtime: Machine) {
        if (runtime.status == -2) throw PluginException("Invalid plugin invocation input")
        if (runtime.status < 0) {
            val message = runtime.context.result()
            val code = runtime.context.errorCode()
            val issue = code?.let { TrackUnavailableReason.fromCode(it)?.issue }
                ?: PlaybackIssue.Network.takeIf { code == pluginNetworkCode }
            if (issue != null) throw PlaybackIssueException(issue, message)
            throw PluginException(message)
        }
    }

    private suspend fun fetch(event: JSONObject, diagnostics: PluginSignInDiagnostics?): String {
        val request = PluginFetchRequest.parse(event, manifest)
        try {
            val response = transport.fetch(request, manifest)
            return response.toJson().toString().also { diagnostics?.record(request.url, response = response) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics?.record(request.url, failure = error.cause ?: error)
            throw error
        }
    }

    private fun schedule(runtime: Machine) {
        while (true) {
            val event = JSONObject(runtime.context.event() ?: return)
            val kind = event.optString("kind")
            if (kind == "cancel") {
                val cancelled = event.optInt("request")
                runtime.events.remove(cancelled)?.cancel()
                runtime.signInRequests?.remove(cancelled)
                continue
            }
            if (runtime.events.size >= 512) throw PluginException("Plugin host request limit exceeded")
            val id = event.getInt("id")
            if (id <= 0 || runtime.events.containsKey(id)) throw PluginException("Invalid plugin host request")
            val signInAllowed = (kind == "webSignIn" || kind == "oauthSignIn") &&
                runtime.signInRequests?.takeIf { it.isEmpty() }?.add(id) == true
            val diagnostics = runtime.signInDiagnostics
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val (success, response) = try {
                    val value = when (kind) {
                        "timer" -> {
                            delay(event.optLong("delay").coerceIn(0, 300_000))
                            "null"
                        }
                        "fetch" -> fetch(event, diagnostics)
                        "webSignIn" -> {
                            if (!signInAllowed) throw PluginException("Web sign-in requires an active sign-in request")
                            val cookie = webSignIn(PluginWebSignIn.parse(event, manifest))
                            JSONObject().put("cookie", cookie).toString()
                        }
                        "oauthSignIn" -> {
                            if (!signInAllowed) throw PluginException("OAuth requires an active sign-in request")
                            val exchange = oauthSignIn(PluginOAuthRequest.parse(event, manifest))
                            oauthToken(transport.fetch(exchange, manifest)).toString()
                        }
                        "openURL" -> {
                            val address = (event.opt("url") as? String)?.let(::uri)?.takeIf(URI::isHttp)
                                ?: throw PluginException("Not a web address")
                            if (!address.scheme.equals("https", true)) throw PluginException(
                                if (language == "en") "In-app web links require HTTPS" else "应用内网页链接需要使用 HTTPS")
                            openURL(PluginWebLink(manifest.name, address))
                            "null"
                        }
                        else -> throw PluginException("Host request is unavailable on Android")
                    }
                    true to value
                } catch (error: CancellationException) {
                    if (!coroutineContext[Job]!!.isActive) return@launch
                    false to JSONObject.quote("Plugin host request timed out")
                } catch (error: PluginException) {
                    false to JSONObject().put("message", error.message).put("code", error.code).toString()
                } catch (_: Exception) {
                    false to JSONObject.quote("Plugin host request failed")
                }
                if (machine !== runtime) return@launch
                runtime.events.remove(id)
                if (signInAllowed) runtime.signInRequests?.remove(id)
                try {
                    runtime.status = runtime.context.settle(id, success, response)
                    checkStatus(runtime)
                    schedule(runtime)
                } catch (error: Exception) {
                    runtime.failure = error
                    runtime.events.values.forEach(Job::cancel)
                    runtime.events.clear()
                } finally {
                    runtime.wake.trySend(Unit)
                }
            }
            runtime.events[id] = job
            job.start()
        }
    }
}
