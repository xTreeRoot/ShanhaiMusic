package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 入口读取原路由真实内容；首页已展示的区域只补“更多”，不重复展开。 */
@Composable internal fun PluginNavigationShelf(source: PluginSource, link: PluginCatalogLink,
    open: (PluginCatalogRequest) -> Unit, play: (List<Track>, Track) -> Unit, refreshed: PluginCatalogPreview? = null) {
    var content by remember(source, link.destination) { mutableStateOf<PluginCatalogSection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(preview: PluginCatalogPreview?) {
        try {
            if (preview?.error != null) { error = preview.error; return }
            val page = preview?.page ?: source.catalog.load(link.destination)
            currentCoroutineContext().ensureActive()
            content = PluginCatalogSection(link.title.ifBlank { page.title }, page.sections.flatMap { it.tracks }.take(6),
                page.sections.flatMap { it.entities }.take(6), page.sections.flatMap { it.links }.take(6), link,
                page.sections.firstOrNull()?.empty.orEmpty(), page.sections.any { it.videos })
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "加载失败" }
    }
    LaunchedEffect(source, link.destination, refreshed) { load(refreshed) }
    Column(Modifier.padding(vertical = 8.dp)) {
        content?.let { PluginDiscoverySection(it, open, play) } ?: run {
            PluginDiscoverySection(PluginCatalogSection(link.title, emptyList(), emptyList(), emptyList(), link, "", false), open, play)
            if (error == null) Loading()
        }
        if (error != null) Retry(checkNotNull(error)) { scope.launch { load(null) } }
    }
}
