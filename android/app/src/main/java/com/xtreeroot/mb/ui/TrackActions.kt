package com.xtreeroot.mb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBBottomSheet as ModalBottomSheet
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track

internal val LocalTrackActions = staticCompositionLocalOf<(Track) -> Unit> { {} }

@Composable
internal fun TrackActionsButton(track: Track, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val open = LocalTrackActions.current
    val label = mb("操作 ${track.title}", "Actions for ${track.title}")
    IconButton(onClick = { open(track) }, modifier = modifier) {
        if (onArtwork) Box(Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.66f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MoreVert, label, Modifier.size(17.dp), tint = Color.White)
        } else Icon(Icons.Default.MoreVert, label, Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActions(track: Track, saved: SavedLibraryModel, connected: Boolean, close: () -> Unit,
                          enqueue: (Boolean) -> Unit, shareAudio: () -> Unit, export: () -> Unit, creator: ((String) -> Unit)?) {
    val context = LocalContext.current
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val playlists by saved.playlists.collectAsStateWithLifecycle()
    var choosePlaylist by remember(track.id) { mutableStateOf(false) }
    var createPlaylist by remember(track.id) { mutableStateOf(false) }
    val downloaded = offline.downloads.any { it.track.id == track.id }
    val offlineAvailable = downloaded || track.sourceId == localSourceId
    if (createPlaylist) {
        PlaylistNameDialog(mb("新建歌单", "New Playlist"), close = close) { name -> saved.create(name, listOf(track.id)); close() }
    } else ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 520.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val colors = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (choosePlaylist) IconButton(onClick = { choosePlaylist = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回歌曲操作", "Back to Song Actions"))
            } else Artwork(track, Modifier.size(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (choosePlaylist) mb("添加到歌单", "Add to Playlist") else track.title,
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = if (choosePlaylist) track.title else track.artist
                if (subtitle.isNotBlank()) Text(subtitle, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = close) { Icon(Icons.Default.Close, mb("关闭", "Close")) }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (choosePlaylist) {
                playlists.forEach { playlist ->
                    TrackAction(playlist.name) { saved.add(playlist.id, listOf(track.id)); close() }
                }
                TrackAction(mb("新建歌单", "New Playlist")) { createPlaylist = true }
            } else {
                TrackAction(mb("加入播放队列", "Add to Queue"), connected) { enqueue(false); close() }
                TrackAction(mb("下一首播放", "Play Next"), connected) { enqueue(true); close() }
                if (offlineAvailable) {
                    val activity by saved.activity.collectAsStateWithLifecycle()
                    val liked = (activity[track.id]?.likedAt ?: 0L) > 0
                    TrackAction(if (liked) mb("取消本地喜欢", "Unlike Locally") else mb("本地喜欢", "Like Locally")) {
                        saved.toggleFavorite(track)
                        close()
                    }
                    TrackAction(mb("添加到歌单", "Add to Playlist")) { choosePlaylist = true }
                    TrackAction(mb("分享音频", "Share Audio")) { shareAudio(); close() }
                    TrackAction(mb("导出音频", "Export Audio")) { export(); close() }
                } else if (track.id in transfers) TrackAction(mb("取消下载", "Cancel Download")) { saved.cancel(track.id); close() }
                else TrackAction(mb("下载音频", "Download Audio")) { saved.download(track); close() }
                if (creator != null) track.artistId?.let { id -> TrackAction(mb("查看 UP 主投稿", "View Creator Uploads")) { creator(id); close() } }
                track.pageUrl?.let { url -> TrackAction(mb("分享链接", "Share Link")) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url), null))
                    close()
                } }
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val titleLabel = mb("标题", "Title")
                val creditLabel = mb("标题和作者", "Title and Artist")
                TrackAction(mb("复制标题", "Copy Title")) { clipboard.setPrimaryClip(ClipData.newPlainText(titleLabel, track.title)); close() }
                TrackAction(mb("复制标题和作者", "Copy Title and Artist")) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(creditLabel, "${track.title} - ${track.artist}")); close()
                }
            }
        }
    }
}

@Composable
private fun TrackAction(text: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PlaylistNameDialog(title: String, initial: String = "", close: () -> Unit, save: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(mb("歌单名称", "Playlist Name")) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name) }) { Text(mb("保存", "Save")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}
