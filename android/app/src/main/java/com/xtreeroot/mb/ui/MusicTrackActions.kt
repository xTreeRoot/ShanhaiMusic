package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import com.xtreeroot.mb.ui.components.MBBottomSheet as ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.*
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** 曲目身份重建时取消旧请求；只显示插件实际提供的总数。 */
@Composable
internal fun MusicTrackMetadata(track: Track, source: InteractionSource?, creator: (() -> Unit)?, compact: Boolean = false, more: () -> Unit) = key(track.id, source) {
    var capabilities by remember { mutableStateOf(InteractionCapabilities(false, false, false)) }
    var summary by remember { mutableStateOf(TrackInteractionSummary()) }
    var liked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        if (source == null) return@LaunchedEffect
        try { capabilities = source.interactionCapabilities() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@LaunchedEffect }
        try { summary = source.interactionSummary(track.sourceTrackId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 计数不可用不阻塞播放。 */ }
        try { liked = source.isLiked(track.sourceTrackId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 登录错误在用户操作后反馈。 */ }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val artistModifier = if (creator == null) Modifier else Modifier.clickable(
                    onClickLabel = mb("查看作者 ${track.artist}", "View artist ${track.artist}"), onClick = creator)
                Text(track.artist + if (creator != null) " ›" else "", artistModifier.heightIn(min = 32.dp)
                    .padding(top = 4.dp).testTag("player-creator"), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CountedInteraction(summary.likes, enabled = source != null && capabilities.likes && !pending,
                onClick = {
                    val provider = source ?: return@CountedInteraction
                    val desired = !liked
                    pending = true; error = null
                    scope.launch {
                        try {
                            provider.like(track.sourceTrackId, desired)
                            ensureActive()
                            liked = desired
                            summary = summary.copy(likes = summary.likes?.let { (it + if (desired) 1 else -1).coerceAtLeast(0) })
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "点赞失败" }
                        finally { pending = false }
                    }
                }, tag = "player-platform-like") {
                Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (liked) mb("取消点赞", "Unlike") else mb("点赞", "Like"), tint = if (liked) Color.Red else LocalContentColor.current)
            }
            CountedInteraction(summary.comments, enabled = capabilities.comments, onClick = { comments = true }, tag = "player-track-comments") {
                Icon(painterResource(R.drawable.ic_comment), mb("评论", "Comments"))
            }
            IconButton(onClick = more, modifier = Modifier.size(36.dp, 48.dp).testTag("player-track-more")) {
                Icon(Icons.Default.MoreVert, mb("歌曲更多操作", "Song Actions"))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
    if (comments && source != null) CommentsSheet(source, track, signedIn = true) { comments = false }
}

@Composable
private fun CountedInteraction(count: Long?, enabled: Boolean, onClick: () -> Unit, tag: String, icon: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).testTag(tag), contentAlignment = Alignment.Center) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { if (count != null) stateDescription = count.toString() }) { icon() }
        if (count != null) Text(compactCount(count), Modifier.align(Alignment.TopEnd).clearAndSetSemantics {},
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}
internal fun compactCount(count: Long): String = when {
    count >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.ROOT, "%.1f万", count / 10_000.0)
    else -> NumberFormat.getIntegerInstance().format(count)
}

/** 收藏保存元数据，下载保存音频；音质直接写入设置页使用的持久配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicTrackActions(track: Track, saved: SavedLibraryModel, preferences: AppPreferences,
                               creator: (() -> Unit)?, close: () -> Unit) {
    val app = LocalContext.current.applicationContext as MBApplication
    val offline by saved.offline.collectAsStateWithLifecycle()
    val resources by saved.resources.collectAsStateWithLifecycle()
    val activity by saved.activity.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    var choosingQuality by remember { mutableStateOf(false) }
    val downloaded = offline.downloads.any { it.track.id == track.id }
    val local = downloaded || track.sourceId == localSourceId
    val collected = if (local) (activity[track.id]?.likedAt ?: 0) > 0 else resources.any { it.id == track.id }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 560.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)
            .testTag("player-track-actions"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(track, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = close) { Icon(Icons.Default.Close, mb("关闭", "Close")) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                MusicActionTile(if (collected) mb("已收藏", "Collected") else mb("收藏", "Collect"), "player-collect", true, {
                    if (local) saved.toggleFavorite(track) else if (collected) saved.removeResource(track.id) else saved.saveResource(track)
                }) { Icon(painterResource(R.drawable.ic_library_album), null) }
                MusicActionTile(if (downloaded) mb("已下载", "Downloaded") else if (track.id in transfers) mb("下载中", "Downloading") else mb("下载", "Download"),
                    "player-download", !local && track.id !in transfers && !track.isUnavailable, { saved.download(track) }) {
                    Icon(painterResource(R.drawable.ic_download), null)
                }
            }
            HorizontalDivider()
            if (creator != null) TextButton(onClick = { close(); creator() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Person, null); Spacer(Modifier.width(14.dp)); Text(mb("作者：", "Artist: ") + track.artist, Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Default.KeyboardArrowRight, null)
            }
            TextButton(onClick = { choosingQuality = !choosingQuality }, modifier = Modifier.fillMaxWidth().testTag("player-quality")) {
                Icon(painterResource(R.drawable.ic_audio_output), null); Spacer(Modifier.width(14.dp)); Text(mb("音质", "Audio Quality"), Modifier.weight(1f))
                Text(qualityLabel(preferences.quality)); Icon(Icons.Default.KeyboardArrowRight, null)
            }
            if (choosingQuality) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioQuality.entries.forEach { quality ->
                    TextButton(onClick = { app.settings.update { it.copy(quality = quality) } },
                        modifier = Modifier.weight(1f).background(if (quality == preferences.quality) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = .04f), RoundedCornerShape(10.dp))
                            .testTag("player-quality-${quality.name}").semantics { selected = quality == preferences.quality }) { Text(qualityLabel(quality)) }
                }
            }
        }
    }
}
@Composable private fun qualityLabel(quality: AudioQuality) = when (quality) {
    AudioQuality.Standard -> mb("标准", "Standard")
    AudioQuality.High -> mb("高品质", "High")
    AudioQuality.Lossless -> mb("无损", "Lossless")
}
@Composable private fun MusicActionTile(title: String, tag: String, enabled: Boolean, action: () -> Unit, icon: @Composable () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.widthIn(min = 90.dp).testTag(tag)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(60.dp, 56.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) { icon() }
            Text(title)
        }
    }
}
