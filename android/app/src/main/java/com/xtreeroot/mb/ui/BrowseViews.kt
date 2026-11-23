package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xtreeroot.mb.cache.cachedArtwork
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.SuggestionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.CancellationException

internal val LocalVideoBrowse = staticCompositionLocalOf { false }

internal enum class TrackPlaybackMark {
    Playing, Paused, Buffering, Failed;

    @Composable
    fun label(): String = when (this) {
        Playing -> mb("正在播放", "Now Playing")
        Paused -> mb("已暂停", "Paused")
        Buffering -> mb("正在缓冲", "Buffering")
        Failed -> mb("播放失败", "Playback Failed")
    }
}

internal data class MarkedTrack(val id: String, val playback: TrackPlaybackMark)
internal val LocalMarkedTrack = compositionLocalOf<MarkedTrack?> { null }

@Composable
internal fun DiscoveryCategory.title(): String = when (this) {
    DiscoveryCategory.Recommended -> mb("为你推荐", "Recommended")
    DiscoveryCategory.Chart -> mb("音乐榜", "Music Charts")
    DiscoveryCategory.Trending -> mb("音乐区热门", "Trending Music")
    DiscoveryCategory.Fresh -> mb("新鲜投稿", "New Uploads")
}

@Composable
internal fun Discover(
    model: BrowseModel,
    play: (List<Track>, Track) -> Unit,
    open: (DiscoveryCategory) -> Unit,
) {
    val home by model.home.collectAsStateWithLifecycle()
    val wideCovers = LocalVideoBrowse.current
    RefreshableDiscoveryGrid(wideCovers, refreshing = home.loading && home.value.isNotEmpty(), refresh = { if (!home.loading) model.refresh() }) {
        if (home.loading && home.value.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
        home.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Retry(message, model::refresh) } }
        home.value.forEach { section ->
            item(key = "header-${section.category}", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(top = 14.dp, bottom = 3.dp)) {
                    DiscoverySectionHeading(section.category.title(), section.week?.let { mb("第 $it 周", "Week $it") },
                        horizontalPadding = if (wideCovers) 10.dp else 20.dp, open = { open(section.category) })
                }
            }
            section.error?.let { item(span = { GridItemSpan(maxLineSpan) }) { Retry(it, model::refresh) } }
            when {
                section.tracks.isEmpty() -> if (section.error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyMessage(mb("暂无内容", "No Content"))
                }
                wideCovers -> gridItems(section.tracks, key = { "${section.category}-${it.id}" }) { track ->
                    VideoCoverCard(track) { play(section.tracks, track) }
                }
                else -> item(span = { GridItemSpan(maxLineSpan) }) {
                    DiscoveryShelf(section.tracks.size, itemKey = { section.tracks[it].id }) { index ->
                        val track = section.tracks[index]
                        MusicCoverCard(track) { play(section.tracks, track) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryView(model: BrowseModel, category: DiscoveryCategory,
                           play: (List<Track>, Track) -> Unit) {
    val details by model.details.collectAsStateWithLifecycle()
    val tracks = details.value?.takeIf { it.category == category }?.tracks.orEmpty()
    val refresh = { model.refreshCategory(category) }
    val video = LocalVideoBrowse.current
    RefreshableDiscoveryGrid(video, refreshing = details.loading && tracks.isNotEmpty(), refresh = refresh) {
        when {
            details.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { Retry(checkNotNull(details.error), refresh) }
            details.loading && tracks.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            !details.loading && tracks.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(mb("暂无内容", "No Content")) }
        }
        val first = tracks.firstOrNull { !it.isUnavailable }
        if (first != null) item(span = { GridItemSpan(maxLineSpan) }) {
            TextButton(onClick = { play(tracks, first) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(mb("播放全部", "Play All")) }
        }
        gridItems(tracks, key = Track::id) { track ->
            if (video) VideoCoverCard(track) { play(tracks, track) }
            else TrackRow(track) { play(tracks, track) }
        }
    }
}

@Composable
internal fun Search(model: BrowseModel, activeSource: String, plugin: PluginSource?,
                    openCatalog: (() -> Unit)?, openCategory: ((DiscoveryCategory) -> Unit)?, play: (List<Track>, Track) -> Unit,
                    platforms: Map<String, String> = emptyMap(), target: String = activeSource,
                    chooseTarget: (String) -> Unit = {}, saved: SavedLibraryModel? = null,
                    playPages: ((List<Track>, Track, com.xtreeroot.mb.playback.PlaybackPages) -> Unit)? = null) {
    val pages = model.searchPages(SearchScope.Music)
    val result by pages.state.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(result.value.query) }
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { term: String -> query = term; expanded = false; model.submitSearch(SearchScope.Music, term); focus.clearFocus() }
    val allLabel = mb("全平台搜索", "All Platforms")
    val targetName = when (target) {
        "all" -> mb("全平台搜索", "All Platforms")
        else -> platforms[target] ?: target
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, focused, { focused = it }, { focus.clearFocus() }, submit, platform = {
            TextButton(onClick = { focus.clearFocus(); expanded = !expanded }, modifier = Modifier.widthIn(max = 130.dp)) {
                Text("$targetName ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column {
                SearchSuggestions(plugin, query, focused, submit)
                LazyColumn(Modifier.fillMaxSize().pointerInput(focus) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); waitForUpOrCancellation(PointerEventPass.Final); focus.clearFocus() }
                }) {
                    if (result.value.query.isEmpty()) {
                        if (history.isNotEmpty()) item("recent-searches") { RecentSearches(history, model::clearHistory, model::removeHistory, submit) }
                        if (openCategory != null) item("browse-music") { BrowseMusicCategories(openCategory) }
                    }
                    items(result.value.warnings) { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                    items(result.value.tracks, key = Track::id) { track ->
                        ResourceRow(track, saved) {
                            val queue = result.value.tracks.filter { it.resourceUrl == null }
                            val continuation = model.searchPlaybackPages(result.value)
                            if (continuation != null && playPages != null) playPages(queue, track, continuation) else play(queue, track)
                        }
                    }
                    when {
                        result.loading -> item { Loading() }
                        result.error != null -> item { Retry(checkNotNull(result.error), pages::loadNext) }
                        result.value.hasMore -> item(key = "search-next-${result.value.cursor}") {
                            LaunchedEffect(result.value.query, result.value.cursor) { pages.loadNext() }
                            Loading()
                        }
                        result.value.query.isNotEmpty() && result.value.tracks.isEmpty() -> item { EmptyMessage(mb("未找到结果", "No results")) }
                    }
                }
            }
            if (expanded) {
                Box(Modifier.fillMaxSize().clickable { expanded = false })
                androidx.compose.material3.Surface(Modifier.padding(start = 16.dp).width(250.dp), shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp, shadowElevation = 6.dp) {
                    LazyColumn(Modifier.heightIn(max = 340.dp).padding(vertical = 4.dp)) {
                        val choices = listOf("all" to allLabel) + platforms.toList()
                        items(choices, key = { it.first }) { (id, name) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .background(if (id == target) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { expanded = false; chooseTarget(id) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = if (id == target) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (id == target) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowsingTrackRow(track: Track, enabled: Boolean = true, play: () -> Unit) {
    if (LocalVideoBrowse.current) VideoPreviewRow(track, enabled, play = play) else TrackRow(track, enabled, play = play)
}

@Composable
internal fun VideoPreviewRow(track: Track, enabled: Boolean = true, selectedTrack: Boolean = false, play: () -> Unit) {
    val mark = LocalMarkedTrack.current?.takeIf { it.id == track.id }?.playback
    val highlighted = selectedTrack || mark != null
    ListItem(modifier = Modifier.trackRowInput(track, enabled, mark, highlighted, play = play),
        headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { TrackSubtitle(track) },
        leadingContent = { VideoArtwork(track, mark, Modifier.width(112.dp)) },
        trailingContent = { TrackActionsButton(track) })
}

@Composable
internal fun TrackRow(track: Track, enabled: Boolean = true, rank: Int? = null,
                      actions: @Composable () -> Unit = { TrackActionsButton(track) }, picker: Boolean = false, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val mark = if (picker) null else LocalMarkedTrack.current?.takeIf { it.id == track.id }?.playback
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .trackRowInput(track, enabled, mark, mark != null, picker, play)
        .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        rank?.let { Text(it.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium,
            color = if (it == 1) colors.primary else colors.onSurfaceVariant, fontWeight = FontWeight.Bold) }
        TrackRowArtwork(track, mark, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = if (mark == null) colors.onSurface else colors.primary)
            TrackSubtitle(track)
        }
        if (track.durationMs > 0) Text(timeLabel(track.durationMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        actions()
    }
}

@Composable
internal fun Modifier.trackRowInput(track: Track, enabled: Boolean, mark: TrackPlaybackMark?, highlighted: Boolean,
                                   picker: Boolean = false, play: () -> Unit): Modifier {
    val available = enabled && !track.isUnavailable
    if (picker) return clickable(enabled = available, onClick = play)
    val open = LocalTrackActions.current
    val label = mark?.label()
    val input = combinedClickable(enabled = available, onClick = play,
        onLongClickLabel = mb("打开歌曲操作", "Open Song Actions"), onLongClick = { open(track) })
    return if (highlighted) input.semantics {
        selected = true
        if (label != null) stateDescription = label
    } else input
}

@Composable
internal fun TrackRowArtwork(track: Track, mark: TrackPlaybackMark?, modifier: Modifier) {
    if (mark == null) {
        Artwork(track, modifier)
        return
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Artwork(track, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.56f)))
        when (mark) {
            TrackPlaybackMark.Playing -> Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom) {
                listOf(7, 14, 10).forEach { height -> Box(Modifier.size(2.dp, height.dp).background(Color.White, CircleShape)) }
            }
            TrackPlaybackMark.Paused -> Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = Color.White)
            TrackPlaybackMark.Buffering -> CircularProgressIndicator(progress = { 0.35f }, modifier = Modifier.size(14.dp),
                color = Color.White, strokeWidth = 2.dp)
            TrackPlaybackMark.Failed -> Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = Color.White)
        }
    }
}

@Composable
internal fun Artwork(track: Track, modifier: Modifier = Modifier) {
    Artwork(track.artworkUrl, track.artworkHeaders, modifier)
}

@Composable
internal fun Artwork(address: String?, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (address != null) {
            val request = remember(context, address, headers) {
                ImageRequest.Builder(context).cachedArtwork(address, headers).build()
            }
            AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun EmptyMessage(message: String) {
    Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Retry(message: String, retry: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = retry) { Text(mb("重试", "Retry")) }
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

internal fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
