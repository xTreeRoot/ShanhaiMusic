package com.xtreeroot.mb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.LibraryPage
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class LibraryModel(application: Application, source: LibrarySource) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, object : LibrarySource { override val id = ""; override suspend fun search(query: String, page: Int) = com.xtreeroot.mb.music.TrackPage(emptyList(), false); override suspend fun library(route: LibraryRoute, cursor: String?) = LibraryPage() })

    val pages = LibraryPages(viewModelScope, source::invalidateLibraryCache, source::library)
    val creatorPages = LibraryPages(viewModelScope, source::invalidateLibraryCache, source::library)
}

internal data class LibraryPageState(val value: LibraryPage = LibraryPage(), val loading: Boolean = false,
                                     val appending: Boolean = false, val error: String? = null) {
    val retryingAppend get() = error != null && appending
}

internal class LibraryPages(private val scope: CoroutineScope, private val invalidateCache: suspend () -> Unit = {}, private val fetch: suspend (LibraryRoute, String?) -> LibraryPage) {
    private val mutable = MutableStateFlow(LibraryPageState())
    private var currentRoute: LibraryRoute? = null
    private var failedCursor: String? = null
    private var job: Job? = null
    val state = mutable.asStateFlow()

    fun open(route: LibraryRoute?) {
        job?.cancel()
        currentRoute = route
        failedCursor = null
        mutable.value = LibraryPageState()
        if (route != null) load(null)
    }

    fun refresh() {
        if (mutable.value.loading && !mutable.value.appending) return
        job?.cancel()
        load(null, refresh = true)
    }

    fun retry() {
        if (!mutable.value.loading && mutable.value.error != null) load(failedCursor)
    }

    fun loadMore() {
        if (!mutable.value.loading) mutable.value.value.nextCursor?.let { load(it) }
    }

    private fun load(cursor: String?, refresh: Boolean = false) {
        val route = currentRoute ?: return
        val previous = mutable.value.value
        mutable.value = LibraryPageState(previous, loading = true, appending = cursor != null)
        job = scope.launch {
            try {
                if (refresh) invalidateCache()
                val result = fetch(route, cursor)
                ensureActive()
                val existing = previous.takeIf { cursor != null }
                mutable.value = LibraryPageState(result.copy(
                    title = result.title ?: existing?.title,
                    tracks = (existing?.tracks.orEmpty() + result.tracks).distinctBy(Track::id),
                    links = (existing?.links.orEmpty() + result.links).distinctBy { it.id },
                    nextCursor = result.nextCursor?.takeUnless { it == cursor },
                ))
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                ensureActive()
                failedCursor = cursor
                mutable.value = LibraryPageState(previous, appending = cursor != null, error = failure.message ?: "加载失败，请重试")
            }
        }
    }
}
