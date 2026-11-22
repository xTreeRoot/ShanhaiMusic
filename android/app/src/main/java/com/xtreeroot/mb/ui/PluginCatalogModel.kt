package com.xtreeroot.mb.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xtreeroot.mb.plugin.PluginCatalogPage
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginSearchKind
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PluginCatalogNavigator {
    val home = CatalogDestination.Content(PluginCatalogRequest.Home)
    private val stack = mutableStateListOf<CatalogDestination>()
    val current get() = stack.lastOrNull()

    fun open(request: PluginCatalogRequest) { stack.add(CatalogDestination.Content(request)) }
    fun search() { stack.add(CatalogDestination.Search()) }
    fun back(refresh: Boolean = false) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        if (refresh) ((current as? CatalogDestination.Content) ?: home).pager.invalidate()
    }
    fun reset() { stack.clear() }
}

internal sealed interface CatalogDestination {
    class Content(val request: PluginCatalogRequest) : CatalogDestination {
        val pager = PluginCatalogPager()
        var savedOverride by mutableStateOf<Boolean?>(null)
        var filter by mutableStateOf<PluginCatalogRequest.Browse?>(null)
            private set
        val currentRequest get() = filter ?: request
        var tabs by mutableStateOf<List<com.xtreeroot.mb.plugin.PluginCatalogLink>>(emptyList())
        private var homeTab: String? = null
        fun rememberTabs(page: PluginCatalogPage?) {
            if (page == null) return
            if (page.tabs.isNotEmpty()) tabs = page.tabs
            if (request == PluginCatalogRequest.Home && filter == null) homeTab = page.selectedTab
        }
        fun selectFilter(next: PluginCatalogRequest.Browse) {
            val selected = next.takeUnless { request == PluginCatalogRequest.Home && it.page == homeTab }
            if (filter == selected) return
            filter = selected; pager.invalidate(clear = true)
        }
        suspend fun refresh(source: PluginSource) {
            val request = currentRequest
            val previews = filter == null
            pager.refresh({ source.catalog.invalidate() }) {
                source.catalog.load(request, includePreviews = previews, refresh = true)
            }
        }
    }
    class Search : CatalogDestination {
        var input by mutableStateOf("")
        var kind by mutableStateOf(PluginSearchKind.Tracks)
            private set
        var results by mutableStateOf<Content?>(null)
            private set

        fun submit() = setResults(input.trim(), kind)

        fun select(next: PluginSearchKind) {
            kind = next
            val query = (results?.request as? PluginCatalogRequest.Search)?.query.orEmpty()
            setResults(query, next)
        }

        private fun setResults(query: String, kind: PluginSearchKind) {
            val request = PluginCatalogRequest.Search(query, kind)
            if (request != results?.request) results = Content(request).takeIf { query.isNotEmpty() }
        }
    }
}

internal data class PluginCatalogStatus(val page: PluginCatalogPage? = null, val busy: Boolean = false,
                                       val error: String? = null, val appendRetry: Boolean = false,
                                       val needsReload: Boolean = true, val appending: Boolean = false, val revision: Long = 0)

internal class PluginCatalogPager {
    private val mutable = MutableStateFlow(PluginCatalogStatus())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private val visitedPages = mutableSetOf<Pair<String, Int>>()

    fun invalidate(clear: Boolean = false) {
        generation++; visitedPages.clear()
        mutable.update { if (clear) PluginCatalogStatus(revision = generation) else it.copy(busy = false, appending = false, needsReload = true, revision = generation) }
    }

    suspend fun refresh(invalidateCache: suspend () -> Unit, fetch: suspend (Int) -> PluginCatalogPage) {
        val previous = mutable.value
        visitedPages.clear()
        val ticket = ++generation
        mutable.value = previous.copy(busy = true, error = null, appending = false, needsReload = false, revision = ticket)
        replace(previous, false, ticket) { offset -> invalidateCache(); fetch(offset) }
    }

    suspend fun load(append: Boolean = false, fetch: suspend (Int) -> PluginCatalogPage) {
        val previous = mutable.value
        if (previous.busy || append && previous.page?.nextOffset == null && previous.page?.nextPage == null) return
        val ticket = generation
        mutable.value = previous.copy(busy = true, error = null, appending = append, needsReload = false)
        replace(previous, append, ticket, fetch)
    }

    private suspend fun replace(previous: PluginCatalogStatus, append: Boolean, ticket: Long, fetch: suspend (Int) -> PluginCatalogPage) {
        val offset = if (append) previous.page?.nextOffset ?: previous.page?.nextPage?.let { 0 } ?: return else 0
        try {
            var next = fetch(offset)
            currentCoroutineContext().ensureActive()
            if (ticket != generation) return
            if (append) previous.page?.nextPage?.page?.let { visitedPages.add(it to offset) }
            if (next.nextPage?.page?.let { it to (next.nextOffset ?: 0) } in visitedPages) next = next.copy(nextPage = null, nextOffset = null)
            val page = previous.page?.takeIf { append }?.let { existing ->
                existing.copy(sections = appendCatalogSections(existing.sections, next.sections), nextOffset = next.nextOffset, nextPage = next.nextPage)
            } ?: next
            mutable.value = PluginCatalogStatus(page.copy(nextOffset = page.nextOffset?.takeIf { it > offset }), needsReload = false, revision = ticket)
        } catch (cancelled: CancellationException) {
            if (ticket == generation) mutable.value = previous.copy(busy = false, appending = false, revision = ticket)
            throw cancelled
        } catch (error: Exception) {
            if (ticket == generation) mutable.value = previous.copy(busy = false, appending = false,
                error = error.message ?: "Plugin catalog request failed", appendRetry = append, needsReload = false, revision = ticket)
        }
    }
}

/** 旧 morePage 可能包含首屏摘要，按真实资源标识去重后原地追加。 */
private fun appendCatalogSections(existing: List<com.xtreeroot.mb.plugin.PluginCatalogSection>, next: List<com.xtreeroot.mb.plugin.PluginCatalogSection>): List<com.xtreeroot.mb.plugin.PluginCatalogSection> {
    val tracks = existing.flatMap { it.tracks }.mapTo(mutableSetOf()) { it.id }
    val entities = existing.flatMap { it.entities }.mapTo(mutableSetOf()) { it.kind to it.id }
    val links = existing.flatMap { it.links }.mapTo(mutableSetOf()) { it.destination }
    return existing + next.map { section -> section.copy(tracks = section.tracks.filter { tracks.add(it.id) },
        entities = section.entities.filter { entities.add(it.kind to it.id) }, links = section.links.filter { links.add(it.destination) }) }
}
