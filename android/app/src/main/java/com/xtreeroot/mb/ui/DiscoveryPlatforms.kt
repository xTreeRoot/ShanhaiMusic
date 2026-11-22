package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.playback.PlaybackStation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** 使用原生分页容器，横向卡片优先消费其自身的滚动手势。 */
@Composable internal fun DiscoveryPlatforms(ids: List<String>, selected: String, select: (String) -> Unit,
    source: (String) -> PluginSource?, navigator: (PluginSource) -> PluginCatalogNavigator,
    play: (List<Track>, Track) -> Unit, playStation: (List<Track>, Track, PlaybackStation) -> Unit,
    progress: (Float) -> Unit, builtin: @Composable () -> Unit) {
    val pager = rememberPagerState(initialPage = ids.indexOf(selected).coerceAtLeast(0), pageCount = { ids.size })
    val latestIds by rememberUpdatedState(ids)
    val latestSelect by rememberUpdatedState(select)
    val latestProgress by rememberUpdatedState(progress)
    LaunchedEffect(selected, ids) { val index = ids.indexOf(selected); if (index >= 0 && pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { page -> latestIds.getOrNull(page)?.let(latestSelect) }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { latestProgress(it) }
    }
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { ids[it] }) { page ->
        val plugin = source(ids[page])
        if (plugin == null) builtin()
        else { val nav = navigator(plugin); PluginCatalogBrowser(plugin, nav.home, nav, play, playStation) }
    }
}
