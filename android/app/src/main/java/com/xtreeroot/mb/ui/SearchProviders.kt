package com.xtreeroot.mb.ui

import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.*
import com.xtreeroot.mb.plugin.*
import kotlinx.coroutines.CancellationException

internal suspend fun searchSource(app: MBApplication, target: String, names: Map<String, String>): MusicSource {
    val providers = buildList {
        for ((id, name) in names) {
            if (target != "all" && target != id) continue
            val source = app.musicSource(id) ?: continue
            val plugin = source as? PluginSource
            try {
                if (plugin == null || plugin.catalog.supports("search")) add(SearchProvider(source, name))
                if (plugin?.catalog?.supports("searchVideos") == true) add(SearchProvider("$id:video", name, "video", 0) { query, cursor ->
                    val result = plugin.catalog.load(PluginCatalogRequest.Search(query, PluginSearchKind.Videos), cursor)
                    TrackPage(result.sections.flatMap { it.tracks }, result.nextOffset != null) to (result.nextOffset ?: cursor)
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { add(SearchProvider("$id:error", name) { _, _ -> throw error }) }
        }
    }
    return UnifiedSearchSource("search:$target", providers)
}
