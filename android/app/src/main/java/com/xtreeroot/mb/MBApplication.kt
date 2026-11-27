package com.xtreeroot.mb

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.xtreeroot.mb.cache.DiskResponseCache
import com.xtreeroot.mb.edge.NodeClient
import com.xtreeroot.mb.local.LocalAudioSource
import com.xtreeroot.mb.local.LocalCatalog
import com.xtreeroot.mb.local.LocalPlaylistStore
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.local.OfflineTransfers
import com.xtreeroot.mb.local.PortableLibrary
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.MusicSource
import com.xtreeroot.mb.music.PlaybackSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.TrackPlaybackIssues
import com.xtreeroot.mb.playback.StationStatus
import com.xtreeroot.mb.playback.PendingPlaybackPages
import com.xtreeroot.mb.playback.QueuePageStatus
import com.xtreeroot.mb.playback.VideoPlayback
import com.xtreeroot.mb.plugin.GitHubPluginPackages
import com.xtreeroot.mb.plugin.CompatPluginBrowserActivity
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PluginHttpTransport
import com.xtreeroot.mb.plugin.PluginInstaller
import com.xtreeroot.mb.plugin.PluginOAuthBroker
import com.xtreeroot.mb.plugin.PluginRuntime
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.plugin.PluginWebSignInBroker
import com.xtreeroot.mb.plugin.isPluginBrowserProcess
import com.xtreeroot.mb.plugin.PluginWebLink
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.AppearanceBackgroundStore
import com.xtreeroot.mb.preferences.NetworkRouting
import com.xtreeroot.mb.preferences.SettingsStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

internal class MBApplication : Application(), ImageLoaderFactory {
    val bassLevel = MutableStateFlow(0f)
    val trackPlaybackIssues = TrackPlaybackIssues()
    val skippedPlaybackTracks = MutableStateFlow<Set<String>>(emptySet())
    val audioFocusBlockedTrack = MutableStateFlow<String?>(null)
    val videoPlayback = MutableStateFlow<VideoPlayback?>(null)
    val stationStatus = MutableStateFlow(StationStatus())
    val pendingPlaybackPages = PendingPlaybackPages()
    val playlistHistory by lazy { com.xtreeroot.mb.playback.PlaylistResumeStore(getSharedPreferences("playlist-resume", MODE_PRIVATE)) }
    val queuePageStatus = MutableStateFlow(QueuePageStatus())
    val settings by lazy { SettingsStore(this) }
    val appearanceBackground by lazy { AppearanceBackgroundStore(this) }
    val network by lazy { NetworkRouting { settings.values.value.proxy } }
    val http by lazy {
        OkHttpClient.Builder().proxySelector(network.proxySelector).followSslRedirects(false).addNetworkInterceptor { chain ->
            val request = chain.request()
            val origin = chain.call().request()
            if (origin.url.host != request.url.host || origin.url.port != request.url.port || origin.url.scheme != request.url.scheme) {
                val forwarded = request.newBuilder()
                origin.headers.names().filterNot { name -> name.equals("Range", true) || name.equals("Accept-Encoding", true) }
                    .forEach { name -> if (!name.equals("Host", true)) forwarded.removeHeader(name) }
                chain.proceed(forwarded.build())
            } else chain.proceed(request)
        }.build()
    }
    val plugins by lazy { PluginInstaller(File(filesDir, "plugins")) }
    val nodeClient by lazy { NodeClient(http) }
    val pluginPackages by lazy { GitHubPluginPackages(PluginHttpTransport(network.proxySelector), Build.SUPPORTED_ABIS.toList()) }
    private data class ActivePlugin(val revision: UUID, val source: PluginSource, val values: Map<String, String>, val language: String)
    private val synchronizePlugins = Mutex()
    private val activePlugins = MutableStateFlow(emptyMap<String, ActivePlugin>())
    private val installed = MutableStateFlow(emptyList<InstalledPlugin>())
    val installedPlugins = installed.asStateFlow()
    val local by lazy { LocalAudioSource(this, LocalCatalog(this)) }
    val resourceBookmarks by lazy { com.xtreeroot.mb.local.ResourceBookmarks(File(filesDir, "resource-bookmarks.json")) }
    val playlists by lazy { LocalPlaylistStore(File(filesDir, "local-playlists.json")) }
    val offline by lazy {
        OfflineTransfers(OfflineMediaStore(File(filesDir, "offline-media")), network::open) { track ->
            playbackSource(track.sourceId)?.audio(track.sourceTrackId, settings.values.value.quality)
                ?: throw IOException("音源不可用")
        }
    }
    lateinit var portableLibrary: PortableLibrary
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isPluginBrowserProcess(base)) CompatPluginBrowserActivity.prepareProcess(base)
    }

    override fun onCreate() {
        super.onCreate()
        if (isPluginBrowserProcess(this)) return
        portableLibrary = PortableLibrary(this, local, offline.store, playlists, settings, plugins, appearanceBackground)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val languages = settings.values.map { it.language }.distinctUntilChanged()
            combine(plugins.changes, languages) { _, _ -> Unit }.collect {
                try { refreshPlugins() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { installed.value = emptyList() }
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).okHttpClient(http)
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "artwork")).maxSizeBytes(256L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()

    val pluginWebSignIn = PluginWebSignInBroker()
    val pluginOAuth = PluginOAuthBroker()
    private val webLink = MutableStateFlow<PluginWebLink?>(null)
    val pluginWebLink = webLink.asStateFlow()

    fun closePluginWebLink(id: UUID) {
        webLink.update { current -> current?.takeUnless { it.id == id } }
    }

    fun playbackSource(id: String): PlaybackSource? = activePlugins.value[id]?.source ?: when (id) {
        localSourceId -> local
        else -> null
    }

    fun musicSource(id: String): MusicSource? = playbackSource(id)

    fun observeCaptions(track: Track): Flow<List<CaptionLine>> =
        if (track.sourceId == localSourceId) local.observeCaptions(track.sourceTrackId)
        else flow { emit(playbackSource(track.sourceId)?.captions(track.sourceTrackId).orEmpty()) }

    suspend fun retirePluginForDataRestore(id: String) = synchronizePlugins.withLock {
        val current = activePlugins.value[id]
        activePlugins.value = activePlugins.value - id
        current?.source?.close()
    }

    suspend fun refreshPlugins(forceReload: Boolean = false, reloadIds: Set<String> = emptySet()) = synchronizePlugins.withLock {
        val records = plugins.installed()
        records.forEach { com.xtreeroot.mb.plugin.LegacyPluginStorage.migrate(this, it) }
        val configurations = records.filter(InstalledPlugin::enabled).map { it to plugins.settings(it) }
        val language = when (settings.values.value.language) {
            AppLanguage.Chinese -> "zh-CN"
            AppLanguage.English -> "en"
            AppLanguage.System -> if (resources.configuration.locales[0].language == "zh") "zh-CN" else "en"
        }
        val previous = activePlugins.value
        val next = configurations.associate { (plugin, values) ->
            val current = previous[plugin.id]?.takeIf { !forceReload && plugin.id !in reloadIds && it.revision == plugin.revision }
            val source = if (current == null) {
                PluginSource(plugin.manifest, PluginRuntime(assets, plugin.directory, plugin.dataDirectory,
                    plugin.manifest, values, language, PluginHttpTransport(network.proxySelector), pluginWebSignIn::show,
                    openURL = { link -> webLink.value = link }, oauthSignIn = pluginOAuth::authorize,
                    audioCacheDirectory = File(cacheDir, "cenc-audio")))
            } else current.source.also {
                if (current.language != language || current.values != values) it.configure(values, language)
            }
            plugin.id to ActivePlugin(plugin.revision, source, values, language)
        }
        activePlugins.value = next
        installed.value = records
        previous.forEach { (id, item) -> if (item.source !== next[id]?.source) item.source.close() }
    }

    fun acceptsTrack(track: Track): Boolean = playbackSource(track.sourceId) != null ||
        offline.store.snapshot.value.downloads.any { it.track.id == track.id }
}
