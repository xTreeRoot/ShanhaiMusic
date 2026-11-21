package com.xtreeroot.mb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import com.xtreeroot.mb.ui.components.MBTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.Track

@Composable
internal fun LibraryRoute.title(): String = when (this) {
    LibraryRoute.Favorites -> mb("收藏夹", "Collections")
    LibraryRoute.Liked -> mb("点赞的视频", "Liked Videos")
    LibraryRoute.History -> mb("观看历史", "Watch History")
    LibraryRoute.Following -> mb("我的关注", "Following")
    is LibraryRoute.Folder -> mb("收藏夹内容", "Collection")
    is LibraryRoute.Creator -> mb("UP 主投稿", "Creator Uploads")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatorUploadsDialog(title: String, model: LibraryPages, close: () -> Unit, back: (() -> Unit)?,
                                  play: (List<Track>, Track) -> Unit) {
    Dialog(onDismissRequest = back ?: close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            window?.let { dialog ->
                WindowCompat.getInsetsController(dialog, dialog.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                if (back != null) IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回", "Back"))
                }
            }, actions = { TextButton(onClick = close) { Text(mb("完成", "Done")) } })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { AccountLibraryView(model, {}, play) }
        }
    }
}

@Composable
internal fun AccountLibraryView(model: LibraryPages, open: (LibraryRoute) -> Unit,
                                play: (List<Track>, Track) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val page = state.value
    PrefetchArtwork(page.tracks.mapNotNull { it.artworkReference() } + page.links.mapNotNull { link ->
        link.imageUrl?.let { ArtworkReference(it) }
    })
    val refreshing = state.loading && !state.appending && (page.tracks.isNotEmpty() || page.links.isNotEmpty())
    RefreshableList(refreshing = refreshing, refresh = model::refresh) {
        state.error?.takeUnless { state.retryingAppend }?.let { item { Retry(it, model::retry) } }
        items(page.links, key = { "link:${it.id}" }) { link ->
            ListItem(headlineContent = { Text(link.title) }, supportingContent = { Text(link.subtitle) },
                leadingContent = {
                    Artwork(link.imageUrl, emptyMap(), Modifier.size(44.dp))
                }, modifier = Modifier.clickable { open(link.route) })
        }
        val first = page.tracks.firstOrNull { !it.isUnavailable }
        if (first != null) item {
            TextButton(onClick = { play(page.tracks, first) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(mb("播放全部", "Play All")) }
        }
        items(page.tracks, key = Track::id) { track -> TrackRow(track) { play(page.tracks, track) } }
        when {
            state.loading -> if (!refreshing) item { Loading() }
            state.error != null -> if (state.retryingAppend) item { Retry(checkNotNull(state.error), model::retry) }
            page.nextCursor != null -> item { TextButton(onClick = model::loadMore) { Text(mb("加载更多", "Load More")) } }
            page.tracks.isEmpty() && page.links.isEmpty() -> item { EmptyMessage(mb("暂无内容", "No Content")) }
        }
    }
}
