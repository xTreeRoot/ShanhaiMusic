package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SearchResult(val sourceId: String = "", val query: String = "", val tracks: List<Track> = emptyList(),
                                 val cursor: Int, val hasMore: Boolean = false, val warnings: List<String> = emptyList())

internal class SearchPages(private val scope: CoroutineScope, private val initialCursor: Int,
                           private val fetch: suspend (String, String, Int) -> Pair<TrackPage, Int>) {
    private val mutable = MutableStateFlow(LoadState(SearchResult(cursor = initialCursor)))
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun submit(sourceId: String, term: String) {
        cancel()
        mutable.value = LoadState(SearchResult(sourceId, term, cursor = initialCursor, hasMore = term.isNotEmpty()))
        loadNext()
    }

    fun cancel() {
        job?.cancel()
        if (mutable.value.loading) mutable.value = mutable.value.copy(loading = false)
    }

    fun loadNext() {
        val current = mutable.value
        val result = current.value
        if (current.loading || result.query.isEmpty() || !result.hasMore) return
        mutable.value = current.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val (page, next) = fetch(result.sourceId, result.query, result.cursor)
                ensureActive()
                mutable.value = LoadState(result.copy(tracks = (result.tracks + page.tracks).distinctBy(Track::id),
                    cursor = next, hasMore = page.hasMore && next > result.cursor, warnings = page.warnings))
            } catch (timedOut: TimeoutCancellationException) {
                ensureActive()
                mutable.value = current.copy(error = "搜索超时，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                mutable.value = current.copy(error = error.message ?: "加载失败，请重试")
            }
        }
    }
}
