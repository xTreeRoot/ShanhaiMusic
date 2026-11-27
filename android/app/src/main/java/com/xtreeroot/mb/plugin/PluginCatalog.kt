package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private val keywordHighlight = Regex("""(?is)<em\s+class\s*=\s*(?:"keyword"|'keyword'|keyword)\s*>(.*?)</em\s*>""")

internal fun pluginPlainTitle(title: String): String = keywordHighlight.replace(title, "$1")

internal fun JSONObject.catalogNextOffset(offset: Int, count: Int): Int? =
    (if (has("nextOffset")) optInt("nextOffset", offset) else offset + count).takeIf { optBoolean("more") && it > offset }

internal enum class PluginEntityKind(val method: String, val key: String) {
    Album("album", "albums"), Artist("artist", "artists"), Playlist("playlist", "playlists"),
    Show("show", "shows"), Station("stationNext", "stations"),
}

internal enum class PluginCollection(val method: String) {
    Playlists("playlists"), Liked("liked"), Recent("recent"), SavedAlbums("savedAlbums"), Shows("shows"), Stations("stations"),
}

internal enum class PluginSearchKind(val key: String, val method: String = "search") {
    Tracks("tracks"), Albums("albums"), Artists("artists"), Playlists("playlists"), Shows("shows"), Videos("tracks", "searchVideos"),
}

internal data class PluginImage(val url: String, val headers: Map<String, String>)

internal data class PluginEntity(val kind: PluginEntityKind, val id: String, val title: String,
                                 val subtitle: String = "", val image: PluginImage? = null,
                                 val saved: Boolean = false, val owned: Boolean = false, val fixed: Boolean = false, val addable: Boolean = false, val removable: Boolean = false) {
    val canRemoveTracks: Boolean get() = kind == PluginEntityKind.Playlist && owned && (!fixed || removable)
    val editable: Boolean get() = kind == PluginEntityKind.Playlist && owned && !fixed
    val saveMethod: String? get() = when (kind) {
        PluginEntityKind.Album -> "saveAlbum"
        PluginEntityKind.Artist -> "followArtist"
        PluginEntityKind.Playlist -> if (owned) null else "savePlaylist"
        PluginEntityKind.Show -> "subscribeShow"
        PluginEntityKind.Station -> null
    }
}

internal sealed interface PluginCatalogRequest {
    data object Home : PluginCatalogRequest
    data class Browse(val page: String, val title: String) : PluginCatalogRequest
    data class Collection(val collection: PluginCollection, val title: String) : PluginCatalogRequest
    data class Entity(val entity: PluginEntity) : PluginCatalogRequest
    data class Search(val query: String, val kind: PluginSearchKind) : PluginCatalogRequest
}

internal data class PluginCatalogLink(val title: String, val subtitle: String, val destination: PluginCatalogRequest,
                                     val image: PluginImage? = null)

internal data class PluginCatalogSection(val title: String, val tracks: List<Track>, val entities: List<PluginEntity>,
                                         val links: List<PluginCatalogLink>, val more: PluginCatalogLink?,
                                         val empty: String, val videos: Boolean) {
    val hasContent get() = tracks.isNotEmpty() || entities.isNotEmpty() || links.isNotEmpty() || more != null || empty.isNotBlank()
}

internal data class PluginCatalogPreview(val page: PluginCatalogPage?, val error: String? = null)

internal data class PluginCatalogPage(val title: String, val subtitle: String, val description: String,
                                      val sections: List<PluginCatalogSection>, val nextOffset: Int? = null,
                                      val navigationPreviews: Map<PluginCatalogRequest, PluginCatalogPreview> = emptyMap(),
                                      val tabs: List<PluginCatalogLink> = emptyList(), val selectedTab: String? = null,
                                      val nextPage: PluginCatalogRequest.Browse? = null)

internal class PluginCatalog(
    private val manifest: PluginManifest,
    private val runtime: PluginRuntime,
    private val readTracks: suspend (JSONArray) -> List<Track>,
    private val readImage: suspend (String?) -> PluginImage?,
    private val cachedInvoke: suspend (String, JSONObject) -> Any,
    private val mutate: suspend (String, JSONObject) -> Any = { method, args -> runtime.invoke(method, args) },
) {
    suspend fun invalidate() = runtime.catalogCache.invalidate()
    suspend fun supports(method: String) = runtime.supports(method)

    suspend fun navigation(placement: PluginNavigationPlacement = PluginNavigationPlacement.Discovery): List<PluginCatalogLink> = manifest.navigation.filter { it.placement == placement }.mapNotNull { item ->
        val page = item.page?.takeIf(String::isNotBlank)
        if (page != null) {
            val title = item.title ?: page
            PluginCatalogLink(title, "", PluginCatalogRequest.Browse(page, title))
        } else {
            val collection = PluginCollection.entries.find { it.method == item.builtin } ?: return@mapNotNull null
            if (!supports(collection.method)) return@mapNotNull null
            val title = item.title.orEmpty()
            PluginCatalogLink(title, "", PluginCatalogRequest.Collection(collection, title))
        }
    }

    suspend fun load(request: PluginCatalogRequest, offset: Int = 0, includePreviews: Boolean = false, refresh: Boolean = false): PluginCatalogPage = when (request) {
        PluginCatalogRequest.Home -> {
            val home = if (supports("home")) invoke("home", JSONObject(), refresh).objectValue() else JSONObject()
            val result = page(home, manifest.name)
            if (includePreviews) withNavigationPreviews(result, refresh) else result
        }
        is PluginCatalogRequest.Browse -> page(invoke("browse", arguments(offset).put("page", request.page), refresh).objectValue(), request.title, offset).continuing(request)
        is PluginCatalogRequest.Collection -> collection(request, offset, refresh)
        is PluginCatalogRequest.Entity -> entity(request.entity, offset, refresh)
        is PluginCatalogRequest.Search -> {
            val args = arguments(offset).put("query", request.query)
            if (request.kind != PluginSearchKind.Videos) args.put("kind", request.kind.key)
            val value = runtime.invoke(request.kind.method, args).objectValue()
            val count = value.list(request.kind.key).length()
            if (request.kind == PluginSearchKind.Videos) value.put("trackLayout", "videos")
            single(request.query, value, next = value.catalogNextOffset(offset, count))
        }
    }

    /** 一次刷新等待首页与独立导航栏目全部完成，返回失败栏目而不覆盖已有内容。 */
    private suspend fun withNavigationPreviews(home: PluginCatalogPage, refresh: Boolean): PluginCatalogPage = coroutineScope {
        val links = navigation().filter { link -> home.sections.none { section ->
            section.more?.destination == link.destination || link.title.isNotBlank() && section.title == link.title
        } }
        val previews = links.map { link -> async {
            val result = try { PluginCatalogPreview(load(link.destination, refresh = refresh)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { PluginCatalogPreview(null, error.message ?: "加载失败") }
            link.destination to result
        } }.awaitAll().toMap()
        home.copy(navigationPreviews = previews)
    }

    suspend fun setSaved(entity: PluginEntity, saved: Boolean) {
        val method = entity.saveMethod ?: throw PluginException("This item cannot be saved")
        runtime.invoke(method, JSONObject().put("id", entity.id).put("on", saved))
        invalidate()
    }

    suspend fun namePlaylist(entity: PluginEntity?, name: String) {
        val args = JSONObject().put("name", name)
        if (entity != null) args.put("id", entity.id)
        runtime.invoke(if (entity == null) "createPlaylist" else "renamePlaylist", args)
        invalidate()
    }

    suspend fun deletePlaylist(entity: PluginEntity) {
        runtime.invoke("deletePlaylist", JSONObject().put("id", entity.id))
        invalidate()
    }

    suspend fun changePlaylistTracks(entity: PluginEntity, ids: List<String>, add: Boolean) {
        mutate(if (add) "addToPlaylist" else "removeFromPlaylist",
            JSONObject().put("id", entity.id).put("trackIds", JSONArray(ids)))
        invalidate()
    }

    suspend fun movePlaylistTrack(entity: PluginEntity, from: Int, to: Int) {
        runtime.invoke("movePlaylistTrack", JSONObject().put("id", entity.id).put("from", from).put("to", to))
        invalidate()
    }

    private suspend fun collection(request: PluginCatalogRequest.Collection, offset: Int, refresh: Boolean): PluginCatalogPage {
        val name = request.collection.method
        val value = invoke(name, arguments(offset), refresh)
        val section = when (request.collection) {
            PluginCollection.Playlists, PluginCollection.Shows, PluginCollection.Stations -> JSONObject().put(name, value.arrayValue())
            PluginCollection.Recent -> JSONObject().put("tracks", value.arrayValue())
            PluginCollection.Liked, PluginCollection.SavedAlbums -> value.objectValue()
        }
        val count = section.list("tracks").length() + PluginEntityKind.entries.sumOf { section.list(it.key).length() }
        return single(request.title.ifBlank { manifest.name }, section,
            next = (value as? JSONObject)?.catalogNextOffset(offset, count))
    }

    private suspend fun entity(entity: PluginEntity, offset: Int, refresh: Boolean): PluginCatalogPage {
        val args = arguments(offset).put("id", entity.id)
        val value = if (entity.kind == PluginEntityKind.Station) runtime.invoke(entity.kind.method, args)
            else invoke(entity.kind.method, args, refresh)
        val detailsPage = value as? JSONObject ?: JSONObject()
        val section = when (entity.kind) {
            PluginEntityKind.Station -> JSONObject().put("tracks", value.arrayValue())
            PluginEntityKind.Show -> JSONObject().put("tracks", detailsPage.list("episodes"))
            else -> value.objectValue()
        }
        val details = detailsPage.optJSONObject(entity.kind.method)
        val count = section.list("tracks").length()
        val next = if (entity.kind == PluginEntityKind.Station) (offset + count).takeIf { count > 0 }
            else detailsPage.catalogNextOffset(offset, count)
        return single(details?.text("name")?.let(::pluginPlainTitle) ?: entity.title, section, entity.subtitle, details?.text("description").orEmpty(), next)
    }

    private suspend fun page(value: JSONObject, fallbackTitle: String, offset: Int = 0): PluginCatalogPage = PluginCatalogPage(
        value.text("title") ?: fallbackTitle, value.text("subtitle").orEmpty(), value.text("description").orEmpty(),
        value.list("sections").objects().map { section(it) },
        value.catalogNextOffset(offset, value.list("sections").objects().sumOf { row -> row.list("tracks").length() + row.list("links").length() + PluginEntityKind.entries.sumOf { row.list(it.key).length() } }),
        tabs = value.list("tabs").objects().take(32).mapNotNull { tab ->
            val title = tab.text("title") ?: return@mapNotNull null
            val page = tab.text("page") ?: return@mapNotNull null
            PluginCatalogLink(title, "", PluginCatalogRequest.Browse(page, title))
        }.distinctBy { it.destination }, selectedTab = value.text("selectedTab"),
    )

    private suspend fun single(title: String, value: JSONObject, subtitle: String = "", description: String = "",
                               next: Int? = null): PluginCatalogPage =
        PluginCatalogPage(title, subtitle, description, listOf(section(value)), next)

    private suspend fun section(value: JSONObject): PluginCatalogSection {
        val entities = PluginEntityKind.entries.flatMap { kind -> value.list(kind.key).objects().mapNotNull { entity ->
            val id = entity.text("id") ?: return@mapNotNull null
            val title = entity.text("name") ?: return@mapNotNull null
            val subtitle = entity.text("description") ?: entity.text("publisher")
                ?: entity.list("artists").objects().mapNotNull { it.text("name") }.joinToString(", ")
            PluginEntity(kind, id, pluginPlainTitle(title), pluginPlainTitle(subtitle), readImage(entity.text("imageUrl")),
                entity.optBoolean("saved") || entity.optBoolean("following") || entity.optBoolean("subscribed"),
                entity.optBoolean("mine"), entity.optBoolean("fixed"), entity.optBoolean("addable"), entity.optBoolean("removable"))
        } }
        val links = value.list("links").objects().mapNotNull { link ->
            val title = link.text("title") ?: return@mapNotNull null
            val page = link.text("page") ?: return@mapNotNull null
            PluginCatalogLink(title, link.text("subtitle").orEmpty(), PluginCatalogRequest.Browse(page, title), readImage(link.text("imageUrl")))
        }
        val more = value.text("morePage")?.let { PluginCatalogLink("", "", PluginCatalogRequest.Browse(it, "")) }
        return PluginCatalogSection(value.text("title").orEmpty(), readTracks(value.list("tracks")), entities,
            links, more, value.text("empty").orEmpty(), value.text("trackLayout") == "videos")
    }

    /** 显式刷新同时绕过宿主缓存并告知插件，避免插件自己的首屏快照被当成新数据。 */
    private suspend fun invoke(method: String, arguments: JSONObject, refresh: Boolean): Any =
        cachedInvoke(method, if (refresh) arguments.put("refresh", true) else arguments)

    private fun arguments(offset: Int) = JSONObject().put("offset", offset)
    private fun Any.objectValue(): JSONObject = this as? JSONObject ?: throw PluginException("Invalid plugin catalog response")
    private fun Any.arrayValue(): JSONArray = this as? JSONArray ?: throw PluginException("Invalid plugin catalog response")
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)
    private fun JSONObject.list(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
}

/** 旧插件的单列表 morePage 表示剩余内容，作为同页续页；首页分区入口仍由发现页展示。 */
internal fun PluginCatalogPage.continuing(request: PluginCatalogRequest.Browse): PluginCatalogPage {
    val result = copy(nextPage = request.takeIf { nextOffset != null })
    val section = sections.singleOrNull() ?: return result
    val destination = section.more?.destination as? PluginCatalogRequest.Browse
    val next = result.nextPage ?: destination?.takeIf {
        it.page != request.page && (section.tracks.isNotEmpty() || section.entities.isNotEmpty() || section.links.isNotEmpty())
    }
    return result.copy(sections = listOf(section.copy(more = null)), nextPage = next)
}
