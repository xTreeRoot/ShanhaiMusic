package com.xtreeroot.mb.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

internal data class SearchProvider(val id: String, val name: String, val type: String = "music", val initialCursor: Int = 1,
                                   val fetch: suspend (String, Int) -> Pair<TrackPage, Int>) {
    constructor(source: MusicSource, name: String) : this(source.id, name, fetch = { q, p -> source.search(q, p) to p + 1 })
}

/** 各平台并发查询并独立分页；单个平台失败保留其余结果。 */
internal class UnifiedSearchSource(override val id: String, private val providers: List<SearchProvider>) : MusicSource {
    private val cursors = ConcurrentHashMap<Pair<String, Int>, Map<String, Int>>()
    override suspend fun search(query: String, page: Int): TrackPage = coroutineScope {
        val current = if (page == 1) providers.associate { it.id to it.initialCursor } else cursors[query to page].orEmpty()
        data class Batch(val provider: SearchProvider, val page: TrackPage?, val next: Int, val error: String? = null)
        val pending = providers.mapNotNull { provider -> current[provider.id]?.let { cursor -> async {
            try {
                val (result, next) = provider.fetch(query, cursor)
                Batch(provider, result.copy(tracks = result.tracks.map { it.copy(resourceType = it.resourceType ?: provider.type) },
                    hasMore = result.hasMore && next > cursor), next)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Batch(provider, null, cursor, "${provider.name}: ${error.message ?: "搜索失败"}") }
        } } }
        val batches = pending.map { it.await() }
        coroutineContext.ensureActive()
        val warnings = batches.mapNotNull { it.error }
        if (batches.isNotEmpty() && batches.all { it.page == null }) throw IllegalStateException(warnings.first())
        val remaining = batches.filter { it.page?.hasMore == true }.associate { it.provider.id to it.next }
        cursors[query to (page + 1)] = remaining
        TrackPage(batches.flatMap { it.page?.tracks.orEmpty() }.distinctBy(Track::id), remaining.isNotEmpty(), warnings)
    }
}
