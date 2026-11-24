package com.xtreeroot.mb.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.xtreeroot.mb.ui.components.MBTab as Tab
import com.xtreeroot.mb.ui.components.MBScrollableTabRow as PrimaryScrollableTabRow
import com.xtreeroot.mb.ui.components.MBTabRow as PrimaryTabRow
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.VideoFrameState
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.PlaybackMode
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun VideoDetailsScreen(model: PlaybackModel, state: PlaybackState, preferences: AppPreferences, accountId: String?,
                                fullScreen: Boolean, setFullScreen: (Boolean) -> Unit, browse: () -> Unit,
                                chooseMode: (PlaybackMode) -> Unit, sharedFrame: VideoFrameState? = null, ownsVideoFrame: Boolean = true) {
    val track = state.current ?: return
    val app = LocalContext.current.applicationContext as MBApplication
    val source = app.playbackSource(track.sourceId) as? InteractionSource
    val commentPages = remember(source, track.id, accountId) { source?.let { CommentPages(it, track.sourceTrackId) } }
    var selected by rememberSaveable(track.id) { mutableStateOf(VideoDetailTab.Info) }
    val frame = if (ownsVideoFrame) rememberVideoFrame(track) else sharedFrame
    val ratio = frame?.aspectRatio ?: if (track.videoWidth > 0 && track.videoHeight > 0) track.videoWidth.toFloat() / track.videoHeight else 16f / 9f
    if (ratio < 1f) {
        PortraitVideoPage(track, frame, state, model, accountId != null, browse, fullScreen, setFullScreen, chooseMode)
        return
    }
    LaunchedEffect(commentPages) { commentPages?.refresh() }
    if (fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo,
        fullScreen = true, setFullScreen)
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        WatchPicture(track, state, model, browse, { chooseMode(PlaybackMode.Music) }, frame, fullScreen, setFullScreen, modifier)
    }
    val information: @Composable (Modifier) -> Unit = { modifier ->
        key(track.id) {
            VideoInformation(track, state, model, preferences, accountId != null, source, commentPages, selected, { selected = it },
                showSystemVolume = !fullScreen, modifier = modifier)
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth >= 600.dp && maxWidth > maxHeight) {
                val pictureWidth = minOf(maxWidth * 0.57f, maxHeight * ratio)
                Row(Modifier.fillMaxSize()) {
                    picture(Modifier.width(pictureWidth))
                    information(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                val pictureWidth = minOf(maxWidth, maxHeight * (0.62f * ratio))
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    picture(Modifier.width(pictureWidth))
                    information(Modifier.weight(1f))
                }
            }
        }
    }
    }
}

@Composable
private fun WatchPicture(track: Track, state: PlaybackState, model: PlaybackModel, browse: () -> Unit, openMusic: () -> Unit,
                         frame: VideoFrameState?, fullScreen: Boolean, setFullScreen: (Boolean) -> Unit,
                         modifier: Modifier) {
    Box(modifier.aspectRatio(frame?.aspectRatio ?: 16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo,
            fullScreen = false, setFullScreen) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = browse) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回浏览", "Back to Browse"), tint = Color.White)
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = openMusic) {
                    Icon(androidx.compose.ui.res.painterResource(com.xtreeroot.mb.R.drawable.ic_music_note), null, tint = Color.White)
                    Text(mb("听歌模式", "Music Mode"), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoInformation(track: Track, state: PlaybackState, model: PlaybackModel, preferences: AppPreferences,
                             signedIn: Boolean, source: InteractionSource?, commentPages: CommentPages?,
                             selected: VideoDetailTab, select: (VideoDetailTab) -> Unit, showSystemVolume: Boolean, modifier: Modifier) {
    val total = commentPages?.state?.collectAsStateWithLifecycle()?.value
        ?.takeIf { it.capabilities?.comments == true }?.page?.total ?: 0
    val numberLocale = if (LocalMBEnglish.current) Locale.US else Locale.SIMPLIFIED_CHINESE
    val count = remember(total, numberLocale) { NumberFormat.getIntegerInstance(numberLocale).format(total) }
    val pager = rememberPagerState(initialPage = selected.ordinal, pageCount = { VideoDetailTab.entries.size })
    val latestSelect by rememberUpdatedState(select)
    val pagerScope = rememberCoroutineScope()
    // 点击与手势都先更新同一个 Pager；停稳后再保存选中项，避免双向 Effect 互相取消动画。
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { latestSelect(VideoDetailTab.entries[it]) } }
    Column(modifier.fillMaxWidth()) {
        val tabs: @Composable () -> Unit = {
            VideoDetailTab.entries.forEach { tab ->
                Tab(pager.currentPage == tab.ordinal, { pagerScope.launch { pager.animateScrollToPage(tab.ordinal) } }, text = {
                    val label = tab.label()
                    Text(if (tab == VideoDetailTab.Comments && total > 0) "$label · $count" else label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumWidth = if (commentPages != null) 520.dp else 360.dp
            if (maxWidth < minimumWidth || LocalDensity.current.fontScale >= 1.4f) {
                PrimaryScrollableTabRow(pager.currentPage, edgePadding = 8.dp, tabs = tabs)
            } else PrimaryTabRow(pager.currentPage, tabs = tabs)
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
        when (VideoDetailTab.entries[page]) {
            VideoDetailTab.Info -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                item {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(track.artist, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        state.error?.let { Retry(it, model::retry) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = model::previous, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(mb("上一首", "Previous"), maxLines = 1) }
                            TextButton(onClick = model::next, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(mb("下一首", "Next"), maxLines = 1) }
                            if (source != null) PlayerInteractionBar(track, signedIn, showCommentsButton = false, modifier = Modifier)
                            TextButton(onClick = model::shuffle, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (state.shuffle) mb("随机", "Shuffle") else mb("顺序", "In Order"), maxLines = 1) }
                            TextButton(onClick = model::repeat, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(when (state.repeatMode) {
                                    Player.REPEAT_MODE_ONE -> mb("单曲", "Repeat One")
                                    Player.REPEAT_MODE_ALL -> mb("循环", "Repeat All")
                                    else -> mb("不循环", "No Repeat")
                                })
                            }
                        }
                    }
                }
                videoQueue(state, model::select)
            }
            VideoDetailTab.Comments -> if (source == null) EmptyMessage(mb("此音源不支持评论", "Comments are not available for this source"))
                else TrackComments(source, track, signedIn, Modifier.fillMaxSize(), sharedPages = commentPages)
            VideoDetailTab.Captions -> VideoCaptions(track, state, model, preferences)
            VideoDetailTab.Queue -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                videoQueue(state, model::select)
                if (showSystemVolume) item { SystemMediaVolume() }
            }
        }
        }
    }
}

private fun LazyListScope.videoQueue(state: PlaybackState, select: (Int) -> Unit) {
    item {
        Text(mb("播放队列 · ${state.tracks.size}", "Queue · ${state.tracks.size}"),
            Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    itemsIndexed(state.tracks) { index, track ->
        val current = index == state.index
        val colors = MaterialTheme.colorScheme
        val mark = LocalMarkedTrack.current?.playback?.takeIf { current }
        val queueLabel = mb("队列选择 ${track.title}", "Queue selection: ${track.title}")
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (current) colors.primary.copy(alpha = 0.11f) else Color.Transparent)
            .trackRowInput(track, state.connected, mark, current, play = { select(index) })
            .semantics { contentDescription = queueLabel }
            .padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            VideoArtwork(track, mark, Modifier.width(88.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (current) colors.primary else colors.onSurface)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                TrackPlaybackStatus(track)
            }
            TrackActionsButton(track)
        }
    }
}

@Composable
internal fun VideoCaptions(track: Track, state: PlaybackState, model: PlaybackModel, preferences: AppPreferences, modifier: Modifier = Modifier.fillMaxSize()) {
    val captions by model.captions.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { model.loadCaptions(track) }
    Box(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        when {
            captions.loading -> Loading()
            captions.error != null -> Retry(checkNotNull(captions.error)) { model.loadCaptions(track) }
            captions.value.isEmpty() -> EmptyMessage(mb("暂无字幕", "No Captions"))
            else -> CaptionStage(captions.value, state.positionMs, preferences.lyricStyle, preferences.stageSparks,
                ValueAnimator.areAnimatorsEnabled(), seek = { model.seekCurrent(state, it) })
        }
    }
}

private enum class VideoDetailTab { Info, Comments, Captions, Queue }

@Composable
private fun VideoDetailTab.label(): String = when (this) {
    VideoDetailTab.Info -> mb("简介", "Info")
    VideoDetailTab.Comments -> mb("评论", "Comments")
    VideoDetailTab.Captions -> mb("字幕", "Captions")
    VideoDetailTab.Queue -> mb("队列", "Queue")
}
