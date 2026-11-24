package com.xtreeroot.mb.music

import org.json.JSONArray
import org.json.JSONObject

/** 只保存个人目录和曲目元数据，沿用既有曲目序列化过滤私有请求头。 */
internal fun LibraryPage.toCacheJson(): JSONObject = JSONObject().apply {
    put("title", title)
    put("tracks", JSONArray(tracks.map { it.toMetadataJson() }))
    put("links", JSONArray(links.map { link -> JSONObject().put("id", link.id).put("title", link.title)
        .put("subtitle", link.subtitle).put("image", link.imageUrl).put("route", link.route.toCacheJson()) }))
    put("next", nextCursor)
}

internal fun JSONObject.toLibraryCachePage(): LibraryPage {
    val rows = getJSONArray("tracks")
    val entries = getJSONArray("links")
    return LibraryPage(nullableString("title"), (0 until rows.length()).map { rows.getJSONObject(it).toTrackMetadata() },
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            LibraryLink(item.getString("id"), item.getString("title"), item.getString("subtitle"), item.nullableString("image"),
                item.getJSONObject("route").toLibraryCacheRoute())
        }, nullableString("next"))
}

internal fun LibraryRoute.toCacheJson(): JSONObject = when (this) {
    LibraryRoute.Favorites -> JSONObject().put("kind", "favorites")
    LibraryRoute.Liked -> JSONObject().put("kind", "liked")
    LibraryRoute.History -> JSONObject().put("kind", "history")
    LibraryRoute.Following -> JSONObject().put("kind", "following")
    is LibraryRoute.Folder -> JSONObject().put("kind", "folder").put("id", id)
    is LibraryRoute.Creator -> JSONObject().put("kind", "creator").put("id", id)
}

private fun JSONObject.toLibraryCacheRoute(): LibraryRoute = when (getString("kind")) {
    "favorites" -> LibraryRoute.Favorites
    "liked" -> LibraryRoute.Liked
    "history" -> LibraryRoute.History
    "following" -> LibraryRoute.Following
    "folder" -> LibraryRoute.Folder(getString("id"))
    "creator" -> LibraryRoute.Creator(getString("id"))
    else -> error("Invalid cached library route")
}
private fun JSONObject.nullableString(key: String) = (opt(key) as? String)?.takeIf(String::isNotEmpty)
