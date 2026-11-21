package com.xtreeroot.mb.ui

import android.animation.ValueAnimator
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import com.xtreeroot.mb.ui.components.MBTopAppBar as TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.R
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.TrackComment
import com.xtreeroot.mb.music.quietCommentAt
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
private fun PlaybackState.failureMessage(): String? =
    if (audioFocusBlocked) mb("系统暂未允许播放音频", "System audio focus is unavailable") else error

@Composable
internal fun ColumnScope.MiniPlayer(state: PlaybackState, open: () -> Unit, toggle: () -> Unit, queue: () -> Unit,
                                    coverBounds: (Rect) -> Unit) {
    val track = state.current ?: return
    val failure = state.failureMessage()
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 6.dp)
        .widthIn(max = 680.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer,
        shadowElevation = 2.dp, border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.65f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = open).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Artwork(track, Modifier.size(40.dp).onGloballyPositioned { coverBounds(it.boundsInWindow()) })
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        (failure ?: track.artist).takeIf(String::isNotBlank)?.let { subtitle ->
                            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (failure == null) colors.onSurfaceVariant else colors.error)
                        }
                    }
                }
                PlayButton(state, toggle)
                IconButton(onClick = queue) { Icon(Icons.AutoMirrored.Filled.List, mb("播放队列", "Queue"), Modifier.size(20.dp)) }
            }
            if (state.durationMs > 0) {
                val progress = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(Modifier.fillMaxWidth(progress).height(2.dp).background(colors.primary.copy(alpha = 0.72f), CircleShape))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlaying(model: PlaybackModel, state: PlaybackState, preferences: AppPreferences, saved: SavedLibraryModel, signedIn: Boolean,
                        close: () -> Unit, showQueue: () -> Unit, showArtist: (String) -> Unit, openingCover: Rect?,
                        rotate: ((PlaybackDirection) -> Unit)?) {
    val track = state.current ?: return
    val failure = state.failureMessage()
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val activity by saved.activity.collectAsStateWithLifecycle()
    val inLibrary = track.sourceId == localSourceId || offline.downloads.any { it.track.id == track.id }
    val favorite = (activity[track.id]?.likedAt ?: 0) > 0
    val app = LocalContext.current.applicationContext as MBApplication
    val source = app.playbackSource(track.sourceId)
    var trackActions by remember(track.id) { mutableStateOf(false) }
    val openCreator: (() -> Unit)? = track.artistId?.takeIf { it.isNotBlank() && source is LibrarySource }?.let { id -> { showArtist(id) } }
    if (trackActions) MusicTrackActions(track, saved, preferences, openCreator) { trackActions = false }
    val supportsVideo = state.videoAvailable
    var showingCaptions by rememberSaveable { mutableStateOf(preferences.lyricsStage) }
    var widePage by rememberSaveable { mutableStateOf(WidePlayerPage.Lyrics) }
    var modeNotice by remember { mutableStateOf<PlayerModeNotice?>(null) }
    LaunchedEffect(modeNotice) {
        if (modeNotice != null) {
            delay(2_000)
            modeNotice = null
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(LocalDensity.current) { window.width.toDp() }
    val wide = window.width > window.height && windowWidth >= 600.dp
    val enlargedText = LocalDensity.current.fontScale >= 1.5f
    val showToolbarTitle = windowWidth >= if (enlargedText) 400.dp else 360.dp
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animate = ValueAnimator.areAnimatorsEnabled()
    val origin = if (animate && preferences.growFromCover) openingCover else null
    var frame by remember { mutableStateOf<Rect?>(null) }
    val entrance = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(frame != null) { if (frame != null) entrance.animateTo(1f, tween(320)) }
    val captions by model.captions.collectAsStateWithLifecycle()
    val interaction = source as? InteractionSource
    val lyricsVisible = if (wide) widePage == WidePlayerPage.Lyrics || widePage == WidePlayerPage.Rhythm else showingCaptions
    val showHotComments = preferences.hotComments && interaction != null && !wide && showingCaptions
    var hotComments by remember(track.id, interaction, signedIn, showHotComments) { mutableStateOf(emptyList<TrackComment>()) }
    var openComments by remember(track.id, interaction, signedIn) { mutableStateOf(false) }
    LaunchedEffect(track.id, source, lyricsVisible) {
        if (lyricsVisible) model.loadCaptions(track)
    }
    LaunchedEffect(track.id, interaction, signedIn, showHotComments) {
        if (showHotComments) {
            try {
                if (interaction.interactionCapabilities().comments) hotComments = interaction.comments(track.sourceTrackId).comments
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    val comment = if (showHotComments && !captions.loading && captions.error == null) {
        quietCommentAt(state.positionMs, captions.value, state.durationMs, hotComments)
    } else null
    val colors = MaterialTheme.colorScheme
    Box((if (origin == null) Modifier else Modifier.onGloballyPositioned { frame = it.boundsInWindow() }.graphicsLayer {
            val destination = frame
            val progress = entrance.value
            if (destination == null) alpha = 0f else {
                val initial = (origin.width / destination.width.coerceAtLeast(1f)).coerceIn(0.03f, 1f)
                transformOrigin = TransformOrigin.Center
                scaleX = initial + (1f - initial) * progress
                scaleY = scaleX
                translationX = (origin.center.x - destination.center.x) * (1f - progress)
                translationY = (origin.center.y - destination.center.y) * (1f - progress)
                alpha = progress.coerceAtLeast(0.02f)
                clip = true
            }
        }).fillMaxSize().background(Brush.verticalGradient(listOf(colors.surfaceContainerLow, colors.background)))) {
        if (preferences.fog) {
            val bass by app.bassLevel.collectAsStateWithLifecycle()
            PlaybackFog(track, if (state.playing) bass else 0f, animate, Modifier.matchParentSize())
        }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(title = {
                if (showToolbarTitle) Text(mb("正在播放", "Now Playing"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            },
                expandedHeight = 48.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent), navigationIcon = {
                IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, mb("关闭播放器", "Close Player")) }
            }, actions = {
                if (supportsVideo) IconButton(onClick = model::watchCurrentVideo) {
                    Icon(painterResource(R.drawable.ic_watch_video), mb("观看当前视频", "Watch Current Video"))
                }
                PlayerStyleMenu(preferences, if (wide) lyricsVisible else showingCaptions, chooseArtwork = { style ->
                    app.settings.update { it.copy(playerStyle = style) }
                    showingCaptions = false
                }, chooseLyrics = { style ->
                    app.settings.update { it.copy(lyricStyle = style) }
                    showingCaptions = true
                    if (wide) widePage = WidePlayerPage.Lyrics
                })
                if (rotate != null) IconButton(onClick = { rotate(if (landscape) PlaybackDirection.Portrait else PlaybackDirection.Landscape) }) {
                    Icon(painterResource(R.drawable.ic_screen_rotate), if (landscape) mb("竖屏播放", "Portrait Player") else mb("横屏播放", "Landscape Player"))
                }
                AudioOutputButton(state.connected)
                IconButton(onClick = showQueue) { Icon(Icons.AutoMirrored.Filled.List, mb("播放队列", "Queue")) }
            })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val seekCurrent: (Long) -> Unit = { model.seekCurrent(state, it) }
            val showLyrics: @Composable (Boolean) -> Unit = { rhythm ->
                when {
                    captions.loading -> Loading()
                    captions.error != null -> Retry(checkNotNull(captions.error)) { model.loadCaptions(track) }
                    captions.value.isEmpty() -> EmptyMessage(mb("暂无歌词", "No Lyrics"))
                    rhythm -> RhythmCaptionStage(captions.value, state.positionMs, state.playing, animate, seekCurrent)
                    else -> androidx.compose.runtime.key(track.id) {
                        CaptionStage(captions.value, state.positionMs, preferences.lyricStyle,
                            preferences.stageSparks, animate, seekCurrent)
                    }
                }
            }
            val stage: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                    .musicTrackSwipes(track.id, previous = model::previous, next = model::next), contentAlignment = Alignment.Center) {
                    when {
                        comment != null -> Column(Modifier.fillMaxWidth().clickable { openComments = true }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(mb("热评", "Top Comment"), style = MaterialTheme.typography.labelLarge)
                            Text(comment.text, style = MaterialTheme.typography.titleLarge)
                            Text("${comment.author} · ${comment.likes} ${mb("赞", "likes")}", style = MaterialTheme.typography.labelMedium)
                        }
                        showingCaptions -> showLyrics(false)
                        else -> {
                            val watch = mb("切换到实时视频", "Switch to Video")
                            PlayerArtwork(track, state, preferences.playerStyle, animate,
                                if (supportsVideo) Modifier.clickable(onClickLabel = watch, role = Role.Button, onClick = model::watchCurrentVideo)
                                    .semantics { contentDescription = watch } else Modifier)
                        }
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                    MusicTrackMetadata(track, source as? InteractionSource, openCreator) { trackActions = true }
                    state.station?.let { station ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(station.title.ifBlank { mb("电台", "Radio") }, Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = model::endStation, enabled = state.connected) {
                                Text(mb("退出电台", "Leave Station"))
                            }
                        }
                    }
                    if (failure != null) Retry(failure, model::retry)
                    else if (state.loading) Text(mb("正在加载…", "Loading…"), style = MaterialTheme.typography.labelMedium)
                    PlayerProgress(state, preferences.smoke && animate, seekCurrent)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerModeControl(if (state.shuffle) mb("随机", "Shuffle") else mb("顺序", "In Order"),
                            R.drawable.ic_shuffle, state.shuffle, state.connected, onClick = {
                                model.shuffle()?.let { modeNotice = PlayerModeNotice.Shuffle(it, state.repeatMode) }
                            }, Modifier.weight(1f))
                        IconButton(onClick = model::previous) {
                            Icon(painterResource(R.drawable.ic_media_previous), mb("上一首", "Previous"))
                        }
                        PlayButton(state, model::toggle, prominent = !enlargedText)
                        IconButton(onClick = model::next) {
                            Icon(painterResource(R.drawable.ic_media_next), mb("下一首", "Next"))
                        }
                        val repeat = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> mb("单曲", "Repeat One")
                            Player.REPEAT_MODE_ALL -> mb("循环", "Repeat All")
                            else -> mb("不循环", "Repeat Off")
                        }
                        PlayerModeControl(repeat, if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                            state.repeatMode != Player.REPEAT_MODE_OFF, state.connected, onClick = {
                                model.repeat()?.let { modeNotice = PlayerModeNotice.Repeat(it) }
                            }, Modifier.weight(1f))
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (wide) {
                    Row(Modifier.widthIn(max = 1040.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        WidePlayerStage(track, state, model, widePage, { widePage = it }, interaction, signedIn, showLyrics,
                            Modifier.weight(1.24f).fillMaxHeight())
                        controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    }
                } else if (maxWidth >= 720.dp) {
                    val stageHeight = minOf(540.dp, maxHeight * 0.62f)
                    Column(Modifier.widthIn(max = 580.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        stage(Modifier.fillMaxWidth().height(stageHeight))
                        controls(Modifier.fillMaxWidth())
                    }
                } else if (enlargedText || maxHeight < 520.dp) {
                    val stageHeight = minOf(maxWidth, 360.dp)
                    Column(Modifier.widthIn(max = 480.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                        stage(Modifier.fillMaxWidth().height(stageHeight))
                        controls(Modifier.fillMaxWidth())
                    }
                } else {
                    Column(Modifier.widthIn(max = 480.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        stage(Modifier.weight(1f).fillMaxWidth())
                        controls(Modifier.fillMaxWidth())
                    }
                }
                modeNotice?.let { notice ->
                    PlaybackModePill(notice, Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
        }
    }
    }
    if (openComments && interaction != null) CommentsSheet(interaction, track, signedIn) { openComments = false }
}

private sealed interface PlayerModeNotice {
    class Shuffle(val enabled: Boolean, val repeatMode: Int) : PlayerModeNotice
    class Repeat(val mode: Int) : PlayerModeNotice
}

@Composable
private fun PlaybackModePill(notice: PlayerModeNotice, modifier: Modifier) {
    val text = when (notice) {
        is PlayerModeNotice.Shuffle -> if (notice.enabled) mb("已切换为随机播放", "Switched to shuffle")
            else repeatModeNotice(notice.repeatMode, mb("已切换为顺序播放", "Switched to sequential playback"))
        is PlayerModeNotice.Repeat -> repeatModeNotice(notice.mode, mb("已关闭循环", "Repeat turned off"))
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Text(text, modifier.background(colors.surfaceContainerHighest, shape).border(0.5.dp, colors.outlineVariant, shape)
        .padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.onSurface, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

@Composable
private fun repeatModeNotice(mode: Int, off: String): String = when (mode) {
    Player.REPEAT_MODE_ALL -> mb("已切换为列表循环", "Switched to repeat all")
    Player.REPEAT_MODE_ONE -> mb("已切换为单曲循环", "Switched to repeat one")
    else -> off
}

@Composable
private fun PlayerModeControl(label: String, icon: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit,
                              modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val status = if (active) mb("已开启", "On") else mb("已关闭", "Off")
    Column(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { stateDescription = status }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(painterResource(icon), null, Modifier.size(19.dp), tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PlayButton(state: PlaybackState, toggle: () -> Unit, prominent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val size = if (prominent) 32.dp else 24.dp
    val modifier = if (prominent) Modifier.size(60.dp).background(colors.primary, CircleShape) else Modifier
    val available = state.error == null && !state.audioFocusBlocked
    val pausing = state.playWhenReady && available
    val buffering = state.loading && pausing
    val label = when {
        buffering -> mb("正在缓冲，暂停", "Buffering, Pause")
        pausing -> mb("暂停", "Pause")
        else -> mb("播放", "Play")
    }
    val tint = if (prominent) colors.onPrimary else colors.onSurface
    IconButton(onClick = toggle, modifier = modifier.semantics {
        contentDescription = label
    }, colors = IconButtonDefaults.iconButtonColors(contentColor = tint)) {
        if (buffering) {
            val indicator = Modifier.size(size).clearAndSetSemantics { }
            if (ValueAnimator.areAnimatorsEnabled()) CircularProgressIndicator(indicator, color = tint, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { 0.25f }, modifier = indicator, color = tint, strokeWidth = 2.dp)
        } else if (pausing) Icon(painterResource(R.drawable.ic_media_pause), null, Modifier.size(size))
        else Icon(Icons.Default.PlayArrow, null, Modifier.size(size))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(model: PlaybackModel, state: PlaybackState, close: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mb("播放队列 · ${state.tracks.size}", "Queue · ${state.tracks.size}"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { confirmClear = true }, enabled = state.tracks.isNotEmpty()) { Text(mb("清空", "Clear")) }
            IconButton(onClick = close) { Icon(Icons.Default.Close, mb("关闭队列", "Close Queue")) }
        }
        QueueTracks(model, state, onSelect = close)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(mb("清空播放队列？", "Clear the play queue?")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; model.clear(); close() }) { Text(mb("清空", "Clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(mb("取消", "Cancel")) } })
}
