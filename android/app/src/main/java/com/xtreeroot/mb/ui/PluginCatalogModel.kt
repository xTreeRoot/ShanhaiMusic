package com.xtreeroot.mb.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xtreeroot.mb.plugin.PluginCatalogPage
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCatalogSection
import com.xtreeroot.mb.plugin.PluginSearchKind
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.plugin.discoveryHome
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
        suspend fun refresh(source: PluginSource, preferences: com.xtreeroot.mb.plugin.DiscoveryPreferences = com.xtreeroot.mb.plugin.DiscoveryPreferences()) {
            val request = currentRequest
            val previews = filter == null
            pager.refresh({ source.catalog.invalidate() }) {
                if (request == PluginCatalogRequest.Home) source.catalog.discoveryHome(source.manifest, preferences, refresh = true)
                else source.catalog.load(request, includePreviews = previews, refresh = true)
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
                val combined = appendCatalogSections(existing.sections, next.sections)
                val duplicated = next.sections.sumOf { it.itemCount } > 0 &&
                    combined.sumOf { it.itemCount } == existing.sections.sumOf { it.itemCount }
                existing.copy(sections = combined, nextOffset = next.nextOffset.takeUnless { duplicated }, nextPage = next.nextPage.takeUnless { duplicated })
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

private val PluginCatalogSection.itemCount get() = tracks.size + entities.size + links.size

private fun appendCatalogSections(existing: List<PluginCatalogSection>, next: List<PluginCatalogSection>): List<PluginCatalogSection> = buildList {
    addAll(existing)
    next.forEachIndexed { index, incoming ->
        val matches: (PluginCatalogSection) -> Boolean = { it.title == incoming.title && it.videos == incoming.videos }
        val match = when {
            existing.size == 1 && next.size == 1 -> 0
            existing.getOrNull(index)?.let(matches) == true -> index
            next.count(matches) == 1 -> existing.indices.singleOrNull { matches(existing[it]) }
            else -> null
        }
        if (match == null) add(incoming)
        else {
            val section = get(match)
            val tracks = section.tracks.mapTo(mutableSetOf()) { it.id }
            val entities = section.entities.mapTo(mutableSetOf()) { it.kind to it.id }
            val links = section.links.mapTo(mutableSetOf()) { it.destination }
            set(match, section.copy(tracks = section.tracks + incoming.tracks.filter { tracks.add(it.id) },
                entities = section.entities + incoming.entities.filter { entities.add(it.kind to it.id) },
                links = section.links + incoming.links.filter { links.add(it.destination) }))
        }
    }
}
