package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.local.parseLrc
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.CaptionWord
import com.xtreeroot.mb.music.CommentPage
import com.xtreeroot.mb.music.InteractionCapabilities
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.TrackUnavailableReason
import com.xtreeroot.mb.music.PlaybackRecorder
import com.xtreeroot.mb.music.StationPlaybackSource
import com.xtreeroot.mb.music.SuggestionSource
import com.xtreeroot.mb.music.PlaybackSource
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackComment
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.music.VideoSource
import java.net.URI
import com.xtreeroot.mb.cache.DiskResponseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONTokener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class PluginSource(
    val manifest: PluginManifest,
    private val runtime: PluginRuntime,
) : PlaybackSource, VideoSource, com.xtreeroot.mb.music.AccountCacheResetSource, com.xtreeroot.mb.music.SignInMethodSource, InteractionSource, PlaybackRecorder, StationPlaybackSource, SuggestionSource {
    override val id: String get() = manifest.id
    val catalog = PluginCatalog(manifest, runtime, ::tracks, ::image, ::catalogInvoke)
    private val accountCacheLock = Any()
    private var cacheAccount: String? = null
    private var accountGeneration = 0L
    override val mediaRevision: Long get() = synchronized(accountCacheLock) { accountGeneration }

    private fun rememberAccount(account: SourceAccount?, force: Boolean = false) = synchronized(accountCacheLock) {
        val key = account?.let { "user:${it.id}" } ?: "guest"
        if (force || cacheAccount != null && cacheAccount != key) accountGeneration++
        cacheAccount = key
    }

    private suspend fun catalogInvoke(method: String, arguments: JSONObject): Any {
        if (synchronized(accountCacheLock) { cacheAccount == null }) {
            try { account(); synchronized(accountCacheLock) { if (cacheAccount == null) cacheAccount = "guest" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return runtime.invoke(method, arguments) }
        }
        val (account, revision) = synchronized(accountCacheLock) { cacheAccount.orEmpty() to accountGeneration }
        val parameters = arguments.keys().asSequence().filter { it != "refresh" }.sorted().flatMap { sequenceOf(it, arguments.get(it).toString()) }.toList()
        val key = DiskResponseCache.key(*(listOf(manifest.id, manifest.version.orEmpty(), account, method) + parameters).toTypedArray())
        val data = runtime.catalogCache.data(key, lifetimeMs = if (arguments.optBoolean("refresh")) 0 else 6L * 60 * 60 * 1000) {
            synchronized(accountCacheLock) { if (revision != accountGeneration) throw CancellationException() }
            val result = runtime.invoke(method, arguments)
            synchronized(accountCacheLock) { if (revision != accountGeneration) throw CancellationException() }
            result.toString().toByteArray(Charsets.UTF_8)
        }
        currentCoroutineContext().ensureActive()
        synchronized(accountCacheLock) { if (revision != accountGeneration) throw CancellationException() }
        return JSONTokener(data.toString(Charsets.UTF_8)).nextValue()
    }
    private val searchLock = Mutex()
    private val offsets = mutableMapOf<String, MutableMap<Int, Int>>()

    override suspend fun suggestions(query: String): List<String> {
        if (!runtime.supports("suggest")) return emptyList()
        val rows = runtime.invoke("suggest", JSONObject().put("query", query)) as? JSONArray ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.opt(it) as? String }
    }

    override suspend fun search(query: String, page: Int): TrackPage = searchLock.withLock {
        val term = query.trim()
        if (term.isEmpty()) return TrackPage(emptyList(), false)
        val offset = if (page == 1) 0 else offsets[term]?.get(page) ?: throw PluginException("Search page has no previous cursor")
        val result = runtime.invoke("search", JSONObject().put("query", term).put("kind", "tracks").put("offset", offset)) as? JSONObject
            ?: throw PluginException("Invalid plugin search response")
        val values = result.optJSONArray("tracks") ?: JSONArray()
        val tracks = tracks(values)
        val next = result.catalogNextOffset(offset, values.length())
        if (next != null) offsets.getOrPut(term) { mutableMapOf() }[page + 1] = next
        TrackPage(tracks, next != null)
    }

    override suspend fun audio(trackId: String): MediaResource = media("stream", JSONObject().put("id", trackId).put("quality", ""))

    override suspend fun audio(trackId: String, quality: AudioQuality): MediaResource =
        media("stream", JSONObject().put("id", trackId).put("quality", quality.name.lowercase()))

    override suspend fun video(trackId: String): MediaResource = media("video", JSONObject().put("id", trackId).put("quality", ""))

    override suspend fun video(trackId: String, quality: AudioQuality): MediaResource =
        media("video", JSONObject().put("id", trackId).put("quality", quality.name.lowercase()))

    override suspend fun video(trackId: String, qualityID: String): MediaResource {
        require(com.xtreeroot.mb.music.VideoQualityOption.valid(qualityID))
        return media("video", JSONObject().put("id", trackId).put("quality", qualityID).put("network", com.xtreeroot.mb.music.MediaNetworkConditions.snapshot()))
    }

    override suspend fun supportsVideo(): Boolean = runtime.supports("video")

    suspend fun supportsAccount() = runtime.supports("account")
    override suspend fun supportsSignIn(): Boolean = runtime.supports("signIn")
    override suspend fun supportsSignOut(): Boolean = runtime.supports("signOut")

    suspend fun supportsSessionSync(): Boolean = listOf("exportSession", "validateSession", "commitSession").all { runtime.supports(it) }

    suspend fun verifyNodeSessionPlugin(client: com.xtreeroot.mb.edge.NodeClient, binding: com.xtreeroot.mb.edge.NodeBinding): String {
        val version = manifest.version ?: throw com.xtreeroot.mb.edge.NodeException(com.xtreeroot.mb.edge.NodeFailure.UntrustedPlugin)
        val digest = runtime.nodeContentDigest()
        client.verifySessionPlugin(binding, id, version, digest)
        return digest
    }

    suspend fun exportNodeSession(accountId: String, digest: String): com.xtreeroot.mb.edge.NodeSession {
        val value = runtime.invoke("exportSession", verifiedNodeDigest = digest) as? JSONObject ?: error("插件没有返回可同步的会话")
        val session = com.xtreeroot.mb.edge.NodeSession.parse(value, value.get("format") as? Int ?: error("会话格式无效"))
        check(session.accountId == accountId) { "会话与本机登录账号不匹配" }
        return session
    }

    suspend fun applyNodeSession(session: com.xtreeroot.mb.edge.NodeSession, digest: String, stillCurrent: () -> Boolean): SourceAccount? {
        val verified = runtime.invoke("validateSession", session.arguments(), verifiedNodeDigest = digest) as? JSONObject ?: return null
        if (verified.optString("id") != session.accountId || !stillCurrent()) return null
        val committed = account(runtime.invoke("commitSession", session.arguments(verified), verifiedNodeDigest = digest)) ?: return null
        if (committed.id != session.accountId || !stillCurrent()) return null
        rememberAccount(committed, force = true)
        return committed
    }

    override suspend fun account(): SourceAccount? {
        if (!supportsAccount()) return null
        val revision = synchronized(accountCacheLock) { accountGeneration }
        val found = account(runtime.invoke("account"))
        synchronized(accountCacheLock) {
            if (revision != accountGeneration) throw CancellationException()
            rememberAccount(found)
        }
        return found
    }

    override val signInMethods get() = manifest.signInMethods
    override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount = signInWithArguments(JSONObject(), prompt)
    override suspend fun signIn(method: String, prompt: suspend (SignInPrompt) -> Unit): SourceAccount {
        if (signInMethods.none { it.id == method }) throw PluginException("Unknown sign-in method")
        return signInWithArguments(JSONObject().put("method", method), prompt)
    }
    private suspend fun signInWithArguments(arguments: JSONObject, prompt: suspend (SignInPrompt) -> Unit): SourceAccount = coroutineScope {
        val prompts = Channel<PluginPrompt>(Channel.CONFLATED)
        val observer = launch {
            for (item in prompts) prompt(SignInPrompt(item.qrUrl, message = item.message))
        }
        try {
            val found = account(runtime.signIn(arguments) { prompts.trySend(it) }) ?: throw PluginException("Sign-in returned no account")
            rememberAccount(found, force = true)
            found
        }
        finally { observer.cancel(); prompts.cancel() }
    }

    override suspend fun signOut() { runtime.invoke("signOut"); rememberAccount(null, force = true) }

    override suspend fun supportsAccountCacheReset(): Boolean = true
    override suspend fun signOutAndClearCache() {
        synchronized(accountCacheLock) { accountGeneration++; cacheAccount = null }
        runtime.clearLoginCache()
        rememberAccount(null, force = true)
        searchLock.withLock { offsets.clear() }
        catalog.invalidate()
    }

    override suspend fun interactionCapabilities() = InteractionCapabilities(
        likes = runtime.supports("like"), comments = runtime.supports("comments"),
        posting = runtime.supports("postComment"), commentLikes = runtime.supports("likeComment"),
    )

    override suspend fun interactionSummary(trackId: String): com.xtreeroot.mb.music.TrackInteractionSummary {
        val method = if (runtime.supports("interactionSummary")) "interactionSummary" else if (runtime.supports("comments")) "comments"
            else return com.xtreeroot.mb.music.TrackInteractionSummary()
        val value = runtime.invoke(method, JSONObject().put("trackId", trackId).put("offset", 0)) as? JSONObject
            ?: throw PluginException("Invalid interaction summary")
        fun count(key: String): Long? = (value.opt(key) as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0
        }?.toLong()
        return if (method == "comments") com.xtreeroot.mb.music.TrackInteractionSummary(comments = count("total"))
        else com.xtreeroot.mb.music.TrackInteractionSummary(count("likes"), count("comments"))
    }

    override suspend fun isLiked(trackId: String): Boolean {
        if (!runtime.supports("isLiked")) return false
        val value = runtime.invoke("isLiked", JSONObject().put("ids", JSONArray(listOf(trackId)))) as? JSONObject
        return value?.opt(trackId) == true
    }

    override suspend fun like(trackId: String, enabled: Boolean) {
        runtime.invoke("like", JSONObject().put("id", trackId).put("on", enabled))
        runtime.catalogCache.invalidate()
    }

    override suspend fun comments(trackId: String, cursor: Int?): CommentPage {
        val offset = cursor ?: 0
        val args = JSONObject().put("trackId", trackId).put("offset", offset)
        val value = runtime.invoke("comments", args) as? JSONObject ?: throw PluginException("Invalid comments response")
        val raw = value.optJSONArray("comments") ?: JSONArray()
        val comments = (0 until raw.length()).mapNotNull { raw.optJSONObject(it)?.let(::comment) }
        val next = (offset + raw.length()).takeIf { raw.length() > 0 && it > offset && value.opt("more") == true }
        return CommentPage(comments, value.optInt("total").coerceAtLeast(0), next)
    }

    override suspend fun postComment(trackId: String, text: String): TrackComment {
        val value = runtime.invoke("postComment", JSONObject().put("trackId", trackId).put("text", text))
        return (value as? JSONObject)?.let(::comment) ?: throw PluginException("Invalid comment response")
    }

    override suspend fun likeComment(trackId: String, commentId: String, enabled: Boolean) {
        runtime.invoke("likeComment", JSONObject().put("trackId", trackId).put("commentId", commentId).put("on", enabled))
    }

    override suspend fun played(trackId: String) {
        if (runtime.supports("played")) runtime.invoke("played", JSONObject().put("id", trackId))
    }

    override suspend fun stationNext(id: String): List<Track> {
        val rows = runtime.invoke("stationNext", JSONObject().put("id", id)) as? JSONArray
            ?: throw PluginException("Invalid station response")
        return tracks(rows)
    }

    private fun comment(value: JSONObject): TrackComment? {
        val id = (value.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return null
        val text = value.opt("text") as? String ?: return null
        return TrackComment(id, (value.opt("author") as? String).orEmpty(), value.opt("avatarUrl") as? String,
            text, value.optInt("likes").coerceAtLeast(0), value.optLong("time"), value.opt("liked") == true)
    }

    private suspend fun account(result: Any): SourceAccount? {
        val value = result as? JSONObject ?: return null
        val id = value.opt("id") as? String ?: return null
        val name = value.opt("name") as? String ?: return null
        val avatar = image(value.opt("imageUrl") as? String)
        return SourceAccount(id, name, avatar?.url, value.opt("page") as? String, avatar?.headers.orEmpty())
    }

    override suspend fun captions(trackId: String): List<CaptionLine> {
        if (!runtime.supports("lyrics")) return emptyList()
        val value = runtime.invoke("lyrics", JSONObject().put("id", trackId)) as? JSONObject
            ?: throw PluginException("Invalid plugin lyrics response")
        val lines = value.optJSONArray("lines")
        if (lines != null && lines.length() > 0) return captionLines(lines)
        return parseLrc(value.optString("lrc"), value.optString("translation"))
    }

    suspend fun close() = runtime.close()

    suspend fun configure(settings: Map<String, String>, language: String) {
        synchronized(accountCacheLock) { cacheAccount = null; accountGeneration++ }
        runtime.catalogCache.invalidate()
        runtime.configure(settings, language)
    }

    private suspend fun media(method: String, arguments: JSONObject): MediaResource {
        val result = if (method == "stream") runtime.stream(arguments) else runtime.invoke(method, arguments)
        if (method == "stream" && (result === JSONObject.NULL || result is JSONObject &&
                (result.isNull("url") || (result.opt("url") as? String)?.isBlank() == true))) {
            throw PlaybackIssueException(PlaybackIssue.SourceUnavailable, "Plugin returned no audio stream")
        }
        val value = result as? JSONObject ?: throw PluginException("Invalid plugin media response")
        val address = value.optString("url")
        val parsed = uri(address)
        if (!parsed.isHttp()) throw PluginException("Invalid plugin media URL")
        val headers = runtime.headersFor(parsed, "audio").toMutableMap()
        val extra = value.optJSONObject("headers")
        extra?.keys()?.forEach { name ->
            val text = extra.opt(name) as? String ?: throw PluginException("Invalid plugin media header")
            if (!allowedPluginHeader(name) || text.any { it == '\r' || it == '\n' }) throw PluginException("Invalid plugin media header")
            headers.keys.removeAll { it.equals(name, true) }
            headers[name] = text
        }
        val defaultExtension = if (method == "video") "mp4" else "m4a"
        val extension = value.optString("type", defaultExtension).lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: defaultExtension
        val choices = value.optJSONArray("videoQualities")
        val qualities = (0 until minOf(32, choices?.length() ?: 0)).mapNotNull { index ->
            val option = choices?.optJSONObject(index) ?: return@mapNotNull null
            val id = option.optString("id")
            val label = option.optString("label").trim().take(64)
            if (!com.xtreeroot.mb.music.VideoQualityOption.valid(id) || id == "auto" || label.isEmpty()) null
            else com.xtreeroot.mb.music.VideoQualityOption(id, label)
        }.distinctBy { it.id }
        val actual = (value.opt("videoQuality") as? String)?.takeIf(com.xtreeroot.mb.music.VideoQualityOption::valid)
        if (!value.isNull("encryption")) {
            if (method != "stream" || extension != "m4a") throw PluginException("不支持的加密音频容器")
            val encryption = value.optJSONObject("encryption") ?: throw PluginException("无效的音频授权")
            val local = runtime.prepareCencAudio(parsed, headers, encryption)
            return MediaResource(local.toURI().toASCIIString(), emptyMap(), "m4a")
        }
        return MediaResource(parsed.toASCIIString(), headers, extension, videoQuality = actual, videoQualities = qualities)
    }

    private suspend fun tracks(values: JSONArray): List<Track> =
        (0 until values.length()).mapNotNull { index -> values.optJSONObject(index)?.let { track(it) } }

    private suspend fun image(address: String?): PluginImage? {
        val url = address?.let { runCatching { uri(it) }.getOrNull() }?.takeIf { it.isHttp() } ?: return null
        return PluginImage(url.toASCIIString(), artworkHeaders(url))
    }

    suspend fun artworkHeaders(address: URI): Map<String, String> = runtime.headersFor(address, "image")

    private suspend fun track(value: JSONObject): Track? {
        val id = (value.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return null
        val title = (value.opt("name") as? String)?.takeIf(String::isNotBlank) ?: return null
        val artists = value.optJSONArray("artists") ?: JSONArray()
        val artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ", transform = ::pluginPlainTitle)
        val album = value.optJSONObject("album")
        val image = image(album?.opt("imageUrl") as? String)
        val page = value.opt("url") as? String
        val artistId = artists.optJSONObject(0)?.opt("id") as? String
        return Track(sourceId = manifest.id, sourceTrackId = id, title = pluginPlainTitle(title), artist = artist,
            artworkUrl = image?.url, durationMs = value.optLong("duration").coerceAtLeast(0), pageUrl = page,
            artistId = artistId, artworkHeaders = image?.headers.orEmpty(),
            resourceType = value.optString("resourceType").takeIf { it.isNotBlank() },
            resourceUrl = value.optString("resourceUrl").takeIf { link -> runCatching { val uri = java.net.URI(link); uri.scheme == "https" && uri.host != null }.getOrDefault(false) },
            videoWidth = value.optJSONObject("video")?.optInt("width")?.coerceAtLeast(0) ?: 0,
            videoHeight = value.optJSONObject("video")?.optInt("height")?.coerceAtLeast(0) ?: 0,
            shortVideo = value.optJSONObject("video")?.optBoolean("short") == true,
            unavailable = value.optBoolean("unavailable"),
            unavailableReason = TrackUnavailableReason.fromCode(value.optString("unavailableReason")))
    }

    private fun captionLines(values: JSONArray): List<CaptionLine> {
        val ordered = (0 until values.length()).mapNotNull { values.optJSONObject(it) }
            .filter { it.optLong("start", -1) >= 0 }.sortedBy { it.optLong("start") }
        return ordered.mapIndexedNotNull { index, line ->
            val start = line.optLong("start")
            val nextStart = ordered.getOrNull(index + 1)?.optLong("start") ?: start + 6_000
            val end = line.optLong("end").takeIf { it > start } ?: minOf(start + 6_000, nextStart)
            val words = line.optJSONArray("words")?.let { list -> (0 until list.length()).mapNotNull { wordIndex ->
                val word = list.optJSONObject(wordIndex) ?: return@mapNotNull null
                val from = word.optLong("start", -1)
                val to = word.optLong("end", -1)
                val text = word.optString("text").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                if (from < 0 || to < from) null else CaptionWord(from, to, text)
            } }.orEmpty()
            val text = line.optString("text").trim().ifEmpty { words.joinToString("") { it.text }.trim() }
            if (text.isEmpty()) null else CaptionLine(start, maxOf(start, end), text,
                line.optString("translation").takeIf(String::isNotBlank), words)
        }
    }
}
