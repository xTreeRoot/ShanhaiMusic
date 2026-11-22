package com.xtreeroot.mb.plugin

import android.content.res.AssetManager
import com.xtreeroot.mb.cache.DiskResponseCache
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.TrackUnavailableReason
import com.xtreeroot.mb.playback.CencAudio
import com.xtreeroot.mb.playback.PreparedCencAudio
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
    audioCacheDirectory: File? = null,
) {
    private val preparedAudio = audioCacheDirectory?.let(::PreparedCencAudio)
    val catalogCache = DiskResponseCache(File(dataDirectory, "catalog-cache"))
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private class Lane { val calls = Mutex(); var machine: Machine? = null }
    private val catalog = Lane()
    private val playback = Lane()
    private fun lane(name: String) = if (name == "stream" || name == "video") playback else catalog
    private var host: PluginHostState? = null
    private var settings = settings.toMap()
    private var language = language
    private var closed = false

    private class Machine(val context: QuickJsContext) {
        val events = mutableMapOf<Int, Job>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var signInRequests: MutableSet<Int>? = null
        var signInDiagnostics: PluginSignInDiagnostics? = null
        var status = 0
        var failure: Exception? = null
    }

    suspend fun supports(name: String): Boolean = useMachine(lane(name)) { it.context.supports(name) }

    suspend fun headersFor(address: URI, kind: String): Map<String, String> = withContext(dispatcher) {
        check(!closed) { "Plugin runtime is closed" }
        hostState().headers(address, manifest, kind)
    }

    /** 平台只交付本次授权，宿主检查每跳网络权限并在后台准备通用 AAC 容器。 */
    suspend fun prepareCencAudio(address: URI, headers: Map<String, String>, encryption: JSONObject): File {
        val cache = preparedAudio ?: throw PluginException("当前宿主未启用授权音频播放")
        if (encryption.opt("scheme") != "cenc") throw PluginException("不支持的音频加密格式")
        val key = CencAudio.key(encryption.optString("key"))
        fun validate(target: URI, redirect: Boolean) {
            if (target.scheme != "https" || target.rawUserInfo != null) throw PluginException("音频地址必须使用 HTTPS，且不能包含账号密码")
            if (!manifest.reaches(target.host.orEmpty())) {
                val label = if (redirect) "音频重定向域名不在插件网络权限内" else "音频域名不在插件网络权限内"
                throw PluginException("$label：${target.host.orEmpty()}")
            }
        }
        validate(address, false)
        return cache.prepare(address.toASCIIString(), headers, key) {
            var request = PluginFetchRequest(address, "GET", headers.filterKeys { !it.equals("Range", true) && !it.equals("If-Range", true) } +
                ("Accept-Encoding" to "identity"), null, PluginRedirectMode.Manual)
            repeat(10) {
                coroutineContext.ensureActive()
                validate(request.url, request.url != address)
                val response = transport.fetch(request, manifest)
                if (response.bytes.size > 16 shl 20) throw PluginException("授权音频超过准备上限")
                val next = request.copy(redirectMode = PluginRedirectMode.Follow).redirect(response.status, response.headers.entries.firstOrNull {
                    it.key.equals("location", true)
                }?.value)
                if (next != null) request = next.copy(redirectMode = PluginRedirectMode.Manual)
                else {
                    if (response.status != 200) throw PluginException("音频请求失败（HTTP ${response.status}）")
                    return@prepare response.bytes
                }
            }
            throw PluginException("音频重定向次数过多")
        }
    }

    suspend fun invoke(name: String, arguments: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Any = useMachine(lane(name)) { runtime ->
        try { invoke(runtime, name, arguments, timeoutMs) }
        finally {
            if (name == "signOut" || name == "signOutAndClearCache") reset(playback)
            if (name == "signOutAndClearCache") reset(catalog)
        }
    }

    suspend fun stream(arguments: JSONObject): Any = useMachine(playback) { runtime ->
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
            if (requests.isNotEmpty()) reset(catalog)
            reset(playback)
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
        reset(playback)
        this@PluginRuntime.settings = settings.toMap()
        this@PluginRuntime.language = language
        host?.let {
            it.settings = settings.toMap()
            it.language = language
        }
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        closed = true
        reset(catalog); reset(playback)
        host = null
        scope.cancel()
    }

    /** 宿主能力，不要求插件实现清理导出；终止旧 VM，防止迟到响应重新写入登录状态。 */
    suspend fun clearLoginCache() {
        withContext(dispatcher) {
            check(!closed) { "Plugin runtime is closed" }
            reset(catalog); reset(playback)
            catalog.calls.withLock { playback.calls.withLock { hostState().clearLoginStorage() } }
        }
        // 缓存加载可能正等待 VM，不能持有执行通道锁等待缓存锁。
        catalogCache.invalidate()
    }

    private suspend fun <T> useMachine(lane: Lane = catalog, operation: suspend (Machine) -> T): T = withContext(dispatcher) {
        lane.calls.withLock {
            check(!closed) { "Plugin runtime is closed" }
            try {
                lane.machine?.failure?.let { reset(lane) }
                operation(lane.machine ?: load(lane))
            } catch (error: Throwable) {
                reset(lane)
                throw error
            }
        }
    }

    private fun hostState(): PluginHostState = host ?: PluginHostState(directory, dataDirectory, settings, language).also {
        it.loadStorage()
        host = it
    }

    private suspend fun load(lane: Lane): Machine {
        val state = hostState()
        val runtime = Machine(QuickJsContext(state)).also { lane.machine = it }
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
            val android = """platform: "android", arch: "$arch",
                oauthSignIn: options => request({ kind: "oauthSignIn", options })"""
            runtime.status = runtime.context.evaluate(bootstrap.replace(platform, android).replace("cencAudio: true", "cencAudio: ${preparedAudio != null}"), "host.js", false, 100)
            finish(runtime)
            val entry = JSONObject.quote("./${manifest.entry}")
            runtime.status = runtime.context.evaluate("export * from $entry;", ".mb-android-entry.js", true, 500)
            finish(runtime)
        }
        return runtime
    }

    private fun reset(lane: Lane) {
        val runtime = lane.machine ?: return
        lane.machine = null
        runtime.events.values.forEach(Job::cancel)
        runtime.wake.close()
        runtime.context.close()
    }

    private suspend fun finish(runtime: Machine): Any {
        while (true) {
            coroutineContext.ensureActive()
            if (catalog.machine !== runtime && playback.machine !== runtime) throw CancellationException("Plugin session changed")
            runtime.failure?.let { throw it }
            checkStatus(runtime)
            schedule(runtime)
            if (runtime.status > 0) return JSONTokener(runtime.context.result()).nextValue() ?: JSONObject.NULL
            // 重置会关闭旧通道；回到循环按 VM 身份报告取消，不把关停误报成执行错误。
            runtime.wake.receiveCatching()
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
                if (catalog.machine !== runtime && playback.machine !== runtime) return@launch
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
