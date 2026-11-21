package com.xtreeroot.mb.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.*
import com.xtreeroot.mb.playback.*
import com.xtreeroot.mb.preferences.PlaybackMode
import com.xtreeroot.mb.ui.components.MBBottomSheet
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private enum class PortraitPanel { More, Comments, Captions, Quality, Information, Creator }

/** 画面保留真实比例，信息叠在左下，右侧仅显示无底色图标和平台实际计数。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortraitVideoPage(track: Track, frame: VideoFrameState?, state: PlaybackState, model: PlaybackModel,
                               signedIn: Boolean, browse: () -> Unit, fullScreen: Boolean,
                               setFullScreen: (Boolean) -> Unit, chooseMode: (PlaybackMode) -> Unit) = key(track.id, signedIn) {
    val context = LocalContext.current
    val app = context.applicationContext as MBApplication
    val scope = rememberCoroutineScope()
    val resources by app.resourceBookmarks.tracks.collectAsStateWithLifecycle()
    val preferences by app.settings.values.collectAsStateWithLifecycle()
    val saved = resources.any { it.id == track.id }
    val provider = app.playbackSource(track.sourceId)
    val source = provider as? InteractionSource
    val library = provider as? LibrarySource
    val creators = remember(library) { library?.let { LibraryPages(scope, it::invalidateLibraryCache, it::library) } }
    var panel by remember { mutableStateOf<PortraitPanel?>(null) }
    var capabilities by remember { mutableStateOf(InteractionCapabilities(false, false, false)) }
    var summary by remember { mutableStateOf(TrackInteractionSummary()) }
    var liked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val actions = LocalTrackActions.current
    val next by rememberUpdatedState(model::next)
    val previous by rememberUpdatedState(model::previous)
    LaunchedEffect(source) {
        if (source == null) return@LaunchedEffect
        try { capabilities = source.interactionCapabilities() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@LaunchedEffect }
        try { summary = source.interactionSummary(track.sourceTrackId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 平台没有计数时不显示虚假的零。 */ }
        try { liked = source.isLiked(track.sourceTrackId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 用户执行点赞时再反馈登录状态。 */ }
    }
    if (fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo, true, setFullScreen)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("portrait-video")) {
        val ratio = frame?.aspectRatio ?: if (track.videoWidth > 0 && track.videoHeight > 0) track.videoWidth.toFloat() / track.videoHeight else 9f / 16f
        val pictureHeight = if (panel == PortraitPanel.Comments) maxHeight * 0.35f else maxHeight
        val width = minOf(maxWidth, pictureHeight * ratio)
        Box(Modifier.width(width).height(pictureHeight).align(if (panel == PortraitPanel.Comments) Alignment.TopCenter else Alignment.Center).pointerInput(track.id) {
            var distance = 0f
            detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                onVerticalDrag = { change, amount -> change.consume(); distance += amount },
                onDragEnd = { if (distance < -72.dp.toPx()) next() else if (distance > 72.dp.toPx()) previous() })
        }.semantics {
            customActions = listOf(CustomAccessibilityAction("Next") { next(); true }, CustomAccessibilityAction("Previous") { previous(); true })
        }) {
            if (!fullScreen) VideoStage(frame, state, model::toggle, { model.seekCurrent(state, it) }, model::retryVideo,
                false, setFullScreen, portrait = true)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f),
            0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = browse) { Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回", "Back")) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { chooseMode(PlaybackMode.Music) }) {
                        Icon(painterResource(R.drawable.ic_headphones), mb("听歌模式", "Music Mode"))
                    }
                    IconButton(onClick = { panel = PortraitPanel.More }, modifier = Modifier.testTag("portrait-more")) {
                        Icon(Icons.Default.MoreVert, mb("视频更多操作", "Video Actions"))
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (creators != null && track.artistId != null) TextButton(onClick = {
                            creators.open(LibraryRoute.Creator(track.artistId)); panel = PortraitPanel.Creator
                        }, contentPadding = PaddingValues(0.dp)) { Text(track.artist + " ›", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        else Text(track.artist, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                        TextButton(onClick = { panel = PortraitPanel.Information }, contentPadding = PaddingValues(0.dp)) {
                            Text(track.title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        notice?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    }
                    Column(Modifier.width(60.dp).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (capabilities.likes && source != null) PortraitAction(if (liked) mb("取消点赞", "Unlike") else mb("点赞", "Like"),
                            count = summary.likes, enabled = !pending, action = {
                                val desired = !liked; pending = true; notice = null
                                scope.launch {
                                    try {
                                        source.like(track.sourceTrackId, desired); ensureActive(); liked = desired
                                        summary = summary.copy(likes = summary.likes?.let { (it + if (desired) 1 else -1).coerceAtLeast(0) })
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { notice = failure.message }
                                    finally { pending = false }
                                }
                            }) { if (liked) Icon(Icons.Default.ThumbUp, null, Modifier.size(28.dp), tint = Color.White) else Icon(painterResource(R.drawable.ic_like_outline), null, Modifier.size(28.dp), tint = Color.White) }
                        if (capabilities.comments) PortraitAction(mb("评论", "Comments"), summary.comments, action = { panel = PortraitPanel.Comments }) {
                            Icon(painterResource(R.drawable.ic_comment), null, Modifier.size(28.dp), tint = Color.White)
                        }
                        track.pageUrl?.let { url -> PortraitAction(mb("分享", "Share"), action = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                        }) { Icon(painterResource(R.drawable.ic_share), null, Modifier.size(28.dp), tint = Color.White) } }
                    }
                }
                MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 4.dp)) {
                        PlayerProgress(state, smoke = false, seek = { model.seekCurrent(state, it) })
                    }
                }
            }
        }
    }
    if (panel == PortraitPanel.Creator && creators != null) CreatorUploadsDialog(track.artist, creators,
        close = { panel = null }, back = null, play = { tracks, chosen -> panel = null; model.play(tracks, chosen) })
    else if (panel != null) MBBottomSheet(onDismissRequest = { panel = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val title = when (panel) {
            PortraitPanel.Comments -> mb("评论", "Comments")
            PortraitPanel.Captions -> mb("字幕", "Captions")
            PortraitPanel.Quality -> mb("画质", "Video Quality")
            PortraitPanel.Information -> mb("说明", "Description")
            else -> mb("更多操作", "More Actions")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (panel == PortraitPanel.Comments) summary.comments?.let { Text(compactCount(it), Modifier.padding(start = 10.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { panel = null }) { Icon(Icons.Default.Close, mb("关闭", "Close")) }
        }
        when (panel) {
            PortraitPanel.Comments -> if (source != null) TrackComments(source, track, signedIn, maxHeight = 440.dp, showHeader = false)
            PortraitPanel.Captions -> VideoCaptions(track, state, model, preferences, Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 440.dp))
            PortraitPanel.Quality -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { VideoQualityOptions(frame) }
            PortraitPanel.Information -> Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(track.title, style = MaterialTheme.typography.titleLarge)
                Text(track.artist, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                PortraitMenuRow(mb("说明", "Description"), R.drawable.ic_description) { panel = PortraitPanel.Information }
                PortraitMenuRow(if (saved) mb("已收藏", "Saved") else mb("收藏到资料库", "Save to Library"), R.drawable.ic_bookmark) {
                    scope.launch {
                        try { if (saved) app.resourceBookmarks.remove(track.id) else app.resourceBookmarks.save(track) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { notice = failure.message }
                    }
                }
                PortraitMenuRow(mb("歌单与下载", "Playlists and Downloads"), R.drawable.ic_playlist_add) { panel = null; actions(track) }
                PortraitMenuRow(mb("字幕", "Captions"), R.drawable.ic_caption) { panel = PortraitPanel.Captions }
                if (!frame?.qualities.isNullOrEmpty()) PortraitMenuRow(mb("画质", "Video Quality"), R.drawable.ic_player_style, frame?.quality) { panel = PortraitPanel.Quality }
                PortraitMenuRow(mb("听歌模式", "Music Mode"), R.drawable.ic_headphones) { panel = null; chooseMode(PlaybackMode.Music) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    TextButton(onClick = { panel = null; previous() }, modifier = Modifier.weight(1f)) { Text(mb("上一个", "Previous")) }
                    TextButton(onClick = { panel = null; next() }, modifier = Modifier.weight(1f)) { Text(mb("下一个", "Next")) }
                }
            }
        }
    }
}

@Composable private fun PortraitAction(label: String, count: Long? = null, enabled: Boolean = true, action: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
            contentDescription = label; if (count != null) stateDescription = count.toString()
        }) { icon() }
        Text(count?.let { compactCount(it) } ?: label, color = Color.White,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun PortraitMenuRow(title: String, symbol: Int, value: String? = null, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Icon(painterResource(symbol), null, Modifier.size(26.dp)); Spacer(Modifier.width(18.dp)); Text(title, Modifier.weight(1f))
        value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
