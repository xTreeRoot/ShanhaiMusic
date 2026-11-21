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
        suspend fun refresh(source: PluginSource) = pager.refresh({ source.catalog.invalidate() }) {
            source.catalog.load(request, includePreviews = true)
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

    fun invalidate() {
        generation++
        mutable.update { it.copy(busy = false, appending = false, needsReload = true, revision = generation) }
    }

    suspend fun refresh(invalidateCache: suspend () -> Unit, fetch: suspend (Int) -> PluginCatalogPage) {
        val previous = mutable.value
        val ticket = ++generation
        mutable.value = previous.copy(busy = true, error = null, appending = false, needsReload = false, revision = ticket)
        replace(previous, false, ticket) { offset -> invalidateCache(); fetch(offset) }
    }

    suspend fun load(append: Boolean = false, fetch: suspend (Int) -> PluginCatalogPage) {
        val previous = mutable.value
        if (previous.busy || append && previous.page?.nextOffset == null) return
        val ticket = generation
        mutable.value = previous.copy(busy = true, error = null, appending = append, needsReload = false)
        replace(previous, append, ticket, fetch)
    }

    private suspend fun replace(previous: PluginCatalogStatus, append: Boolean, ticket: Long, fetch: suspend (Int) -> PluginCatalogPage) {
        val offset = if (append) previous.page?.nextOffset ?: return else 0
        try {
            val next = fetch(offset)
            currentCoroutineContext().ensureActive()
            if (ticket != generation) return
            val page = previous.page?.takeIf { append }?.let { existing ->
                existing.copy(sections = existing.sections + next.sections, nextOffset = next.nextOffset)
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
