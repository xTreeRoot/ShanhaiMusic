package com.xtreeroot.mb.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import kotlin.math.abs

/** 分页歌单在后台查找，远距离先接近目标，再平滑滚动最后几行。 */
@Composable
internal fun PlaylistCatalogFocus(sourceID: String, request: PluginCatalogRequest, pager: PluginCatalogPager,
                                  grid: LazyGridState, resumeHeader: Boolean, loadMore: suspend () -> Unit) {
    val marked = LocalMarkedTrack.current
    val focus = remember(pager, request) {
        val playlist = (request as? PluginCatalogRequest.Entity)?.entity?.kind == com.xtreeroot.mb.plugin.PluginEntityKind.Playlist
        PlaylistTrackFocus(marked?.id?.takeIf { playlist && it.startsWith("$sourceID:") })
    }
    LaunchedEffect(grid, focus) {
        grid.interactionSource.interactions.collect { if (it is DragInteraction.Start) focus.stop() }
    }
    LaunchedEffect(pager, request, focus) {
        val visited = mutableSetOf<Pair<PluginCatalogRequest.Browse?, Int?>>()
        pager.state.collect { status ->
            if (focus.finished || status.busy) return@collect
            if (status.error != null) { focus.stop(); return@collect }
            val page = status.page ?: return@collect
            val more = page.nextOffset != null || page.nextPage != null
            val tracks = page.sections.flatMap { it.tracks }
            if (focus.locate(tracks, more) != null) {
                val row = playlistGridTrackIndex(page, checkNotNull(focus.target), resumeHeader) ?: return@collect
                if (abs(grid.firstVisibleItemIndex - row) > 12) grid.scrollToItem((row - 3).coerceAtLeast(0))
                grid.animateScrollToItem(row, -grid.layoutInfo.viewportSize.height / 3)
            } else if (!focus.finished && visited.add(page.nextPage to page.nextOffset)) loadMore()
            else focus.stop()
        }
    }
}
