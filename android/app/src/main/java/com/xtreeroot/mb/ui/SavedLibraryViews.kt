package com.xtreeroot.mb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBCheckbox as Checkbox
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.local.LocalPlaylist
import com.xtreeroot.mb.local.LocalMusicIndex
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.local.PortableLibrary
import com.xtreeroot.mb.local.Transfer
import com.xtreeroot.mb.local.matchesLocalMusic
import com.xtreeroot.mb.music.Track
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DataLocationPage(library: PortableLibrary, back: () -> Unit) {
    val status by library.status.collectAsStateWithLifecycle()
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), library::bind)
    LazyColumn(Modifier.fillMaxSize()) {
        item { LibraryPageHeader(mb("数据位置", "Data Location"), back) }
        item {
            val colors = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp)).background(colors.surfaceContainerLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        Icon(if (status.linked) Icons.Default.Check else Icons.AutoMirrored.Filled.List, null, tint = colors.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (status.linked) mb("已连接独立资料库目录", "Backup Folder Connected")
                            else mb("未设置独立资料库目录", "No Backup Folder Selected"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (status.linked) status.folderName?.let {
                            Text("$it / MB Data", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status.savedAt > 0) {
                        val locale = if (LocalMBEnglish.current) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
                        val date = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale).format(Date(status.savedAt))
                        Text(mb("最近备份：$date", "Last backup: $date"), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(if (status.linked) mb("卸载后备份仍在。重装后选回同一个文件夹。", "The backup survives uninstall. Select this same folder after reinstalling.")
                        else mb("在‘文档’下选择文件夹。山海会把备份放进其中的 MB Data。", "Choose a folder in Documents. Shanhai keeps the backup in its MB Data subfolder."),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.linked) {
                        TextButton(enabled = !status.busy, onClick = library::saveNow) { Text(mb("立即备份", "Back Up Now")) }
                        TextButton(enabled = !status.busy, onClick = { choose.launch(null) }) {
                            Text(mb("更换或恢复目录", "Change or Restore Folder"))
                        }
                    } else TextButton(enabled = !status.busy, onClick = { choose.launch(null) }) {
                        Text(mb("选择目录或恢复", "Choose Folder or Restore"))
                    }
                }
                if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status.error?.let { error ->
                    val message = mb(error.problem.chinese, error.problem.english)
                    Text(listOfNotNull(message, error.asset).joinToString("\n"), color = colors.error)
                }
            }
            Text(mb("登录凭据、插件私有数据和播放缓存不备份。恢复的插件需重新启用。",
                "Sign-in credentials, private plugin data, and playback cache are not backed up. Restored plugins must be enabled again."),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DownloadsPage(saved: SavedLibraryModel, play: (List<Track>, Track) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val offline by saved.offline.collectAsStateWithLifecycle()
    val refreshing by saved.refreshing.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val error by saved.transferError.collectAsStateWithLifecycle()
    var cacheMenu by remember { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = LocalTrackActions.current
    val tracks = offline.downloads.map { it.track }
    val cacheSize = android.text.format.Formatter.formatShortFileSize(context, offline.cacheBytes)
    RefreshableList(refreshing = refreshing, refresh = saved::refresh) {
        item { LibraryPageHeader(mb("下载与缓存", "Downloads & Cache"), back) }
        item {
            ListItem(headlineContent = { Text(mb("播放缓存", "Playback Cache")) }, supportingContent = { Text(mb("已用 $cacheSize", "$cacheSize used")) }, trailingContent = {
                Box {
                    TextButton(onClick = { cacheMenu = true }) { Text(if (offline.limitMb == 0) mb("已关闭", "Off") else "${offline.limitMb} MB") }
                    DropdownMenu(cacheMenu, { cacheMenu = false }) {
                        OfflineMediaStore.limitsMb.forEach { limit ->
                            DropdownMenuItem(text = { Text(if (limit == 0) mb("关闭自动缓存", "No Cache") else "$limit MB") }, onClick = {
                                cacheMenu = false; saved.setLimit(limit)
                            })
                        }
                    }
                }
            })
            TextButton(enabled = offline.cacheBytes > 0, onClick = { clear = true }) { Text(mb("清空播放缓存", "Clear Playback Cache")) }
        }
        error?.let { message -> item { EmptyMessage(message) } }
        activeDownloadSection(transfers, saved::cancel)
        item { Text(mb("已下载", "Downloaded"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
        if (tracks.isEmpty()) item { EmptyMessage(mb("暂无下载", "No Downloads")) }
        items(tracks, key = Track::id) { track ->
            TrackRow(track, actions = {
                SavedTrackMenu(track, more = { actions(track) }) {
                    DropdownMenuItem(text = { Text(mb("删除下载", "Delete Download")) }, onClick = { it(); remove = track.id })
                }
            }) { play(tracks, track) }
        }
    }
    if (clear) LibraryConfirmation(mb("清空播放缓存？", "Clear playback cache?"),
        mb("已下载和导入的音频会保留。", "Downloaded and imported audio will be kept."), mb("清空", "Clear"), { clear = false }) {
        clear = false; saved.clearCache()
    }
    remove?.let { id -> LibraryConfirmation(mb("删除下载？", "Delete download?"),
        mb("仅删除此音频的离线副本。", "Only the offline copy of this track will be deleted."), mb("删除", "Delete"), { remove = null }) {
        remove = null; saved.removeDownload(id)
    } }
}

internal fun LazyListScope.activeDownloadSection(transfers: Map<String, Transfer>, cancel: (String) -> Unit) {
    if (transfers.isEmpty()) return
    item(key = "active-downloads") {
        Text(mb("正在下载", "Downloading"), Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    items(transfers.values.toList(), key = { "transfer:${it.track.id}" }) { transfer ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(transfer.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                val indicator = Modifier.fillMaxWidth().height(3.dp)
                if (transfer.expected > 0) LinearProgressIndicator(progress = {
                    (transfer.received.toFloat() / transfer.expected).coerceIn(0f, 1f)
                }, modifier = indicator) else LinearProgressIndicator(indicator)
            }
            IconButton(onClick = { cancel(transfer.track.id) }) {
                Icon(Icons.Default.Close, mb("取消下载：${transfer.track.title}", "Cancel Download: ${transfer.track.title}"))
            }
        }
    }
}

@Composable
internal fun PlaylistsPage(saved: SavedLibraryModel, library: LocalMusicIndex,
                           play: (List<Track>, Track) -> Unit, playGroup: (List<Track>, Boolean) -> Unit,
                           initialPlaylistId: String? = null, back: () -> Unit) {
    val playlists by saved.playlists.collectAsStateWithLifecycle()
    val available = remember(library) { library.songs.associateBy(Track::id) }
    var selected by rememberSaveable(initialPlaylistId) { mutableStateOf(initialPlaylistId) }
    var create by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var chooseTracks by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var trackQuery by rememberSaveable(selected) { mutableStateOf("") }
    var sorting by rememberSaveable(selected) { mutableStateOf(false) }
    val current = playlists.firstOrNull { it.id == selected }
    val actions = LocalTrackActions.current
    fun closePlaylist() { if (initialPlaylistId == null) selected = null else back() }
    BackHandler(current != null, onBack = ::closePlaylist)
    if (current == null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                LibraryPageHeader(mb("本地歌单", "Local Playlists"), back) {
                    TextButton(onClick = { create = true }) { Text(mb("新建歌单", "New Playlist")) }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(mb("搜索歌单", "Search Playlists")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            val matching = playlists.filter { matchesLocalMusic(it.name, query) }
            if (matching.isEmpty()) item { EmptyMessage(mb("没有匹配的歌单", "No Matching Playlists")) }
            items(matching, key = LocalPlaylist::id) { playlist ->
                LocalGroupRow(playlist.name, "", playlist.trackIds.mapNotNull(available::get)) { selected = playlist.id }
            }
        }
    } else {
        val tracks = current.trackIds.mapNotNull(available::get)
        val matching = library.filter(tracks, trackQuery)
        val canSort = trackQuery.isBlank() && tracks.size > 1
        val editing = canSort && sorting
        TrackReorderList(matching, editing, { from, to -> saved.reorder(current.id, from, to, matching.map(Track::id)) },
            Modifier.fillMaxSize(), header = {
                Column {
                    LibraryPageHeader(null, back = ::closePlaylist) {
                        if (canSort) TextButton(onClick = { sorting = !sorting }) {
                            Text(if (editing) mb("完成排序", "Done Sorting") else mb("排序", "Sort"))
                        }
                        PlaylistMenu(current, { rename = true }, { delete = true })
                    }
                    LocalCollectionHero(current.name, "", tracks)
                    LocalMusicSearch(trackQuery, { trackQuery = it; sorting = false }, mb("在此页面搜索", "Search This Page"))
                    if (tracks.isNotEmpty()) LocalPlaybackButtons(matching, playGroup)
                    TextButton(onClick = { chooseTracks = true }) { Text(mb("添加歌曲", "Add Songs")) }
                    if (tracks.isEmpty()) EmptyMessage(mb("歌单暂无歌曲", "This Playlist Is Empty"))
                    else if (matching.isEmpty()) EmptyMessage(mb("没有匹配的歌曲", "No Matching Songs"))
                }
            }) { visibleIndex, placement, lifted, dragging ->
            val track = matching[visibleIndex]
            val index = if (trackQuery.isBlank()) visibleIndex else -1
            val surface = if (lifted) Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                else Modifier
            Box(placement.then(surface)) {
                TrackRow(track, enabled = !editing && !dragging, actions = {
                    if (lifted) Box(Modifier.size(trackReorderHandleSize), contentAlignment = Alignment.Center) { TrackReorderHandle(null) }
                    else SavedTrackMenu(track, more = { actions(track) }, reorder = editing, enabled = !dragging) { close ->
                        DropdownMenuItem(text = { Text(mb("上移", "Move Up")) }, enabled = trackQuery.isBlank() && index > 0, onClick = {
                            close(); saved.move(current.id, track.id, -1, available.keys)
                        })
                        DropdownMenuItem(text = { Text(mb("下移", "Move Down")) }, enabled = trackQuery.isBlank() && index < tracks.lastIndex, onClick = {
                            close(); saved.move(current.id, track.id, 1, available.keys)
                        })
                        DropdownMenuItem(text = { Text(mb("移出歌单", "Remove from Playlist")) }, onClick = { close(); saved.remove(current.id, track.id) })
                    }
                }) { play(matching, track) }
            }
        }
    }
    if (create) PlaylistNameDialog(mb("新建歌单", "New Playlist"), close = { create = false }) { name -> create = false; saved.create(name) }
    if (current != null) {
        if (rename) RenameLocalPlaylist(current, saved) { rename = false }
        if (delete) DeleteLocalPlaylist(current, saved, { delete = false }, ::closePlaylist)
        if (chooseTracks) TrackPicker(available.values.filterNot { it.id in current.trackIds }, close = { chooseTracks = false }) {
            chooseTracks = false; saved.add(current.id, it)
        }
    }
}

@Composable
internal fun LibraryPageHeader(title: String?, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回资料库", "Back to Library")) }
        if (title != null) Text(title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleLarge)
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
internal fun PlaylistMenu(playlist: LocalPlaylist, rename: () -> Unit, delete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, mb("操作歌单 ${playlist.name}", "Playlist Actions ${playlist.name}")) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(mb("重命名歌单", "Rename Playlist")) }, onClick = { open = false; rename() })
            DropdownMenuItem(text = { Text(mb("删除歌单", "Delete Playlist")) }, onClick = { open = false; delete() })
        }
    }
}

@Composable
internal fun RenameLocalPlaylist(playlist: LocalPlaylist, saved: SavedLibraryModel, close: () -> Unit) {
    PlaylistNameDialog(mb("重命名歌单", "Rename Playlist"), playlist.name, close) { name ->
        close(); saved.rename(playlist.id, name)
    }
}

@Composable
internal fun DeleteLocalPlaylist(playlist: LocalPlaylist, saved: SavedLibraryModel,
                                 close: () -> Unit, afterConfirm: () -> Unit = {}) {
    LibraryConfirmation(mb("删除歌单“${playlist.name}”？", "Delete playlist “${playlist.name}”?"),
        mb("音频文件会保留在资料库里。", "Audio files will stay in your library."), mb("删除", "Delete"), close) {
        close(); saved.delete(playlist); afterConfirm()
    }
}

@Composable
private fun SavedTrackMenu(track: Track, more: () -> Unit, reorder: Boolean = false, enabled: Boolean = true,
                           entries: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            if (reorder) TrackReorderHandle(mb("调整 ${track.title} 顺序", "Reorder ${track.title}"))
            else Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}"))
        }
        DropdownMenu(open, { open = false }) {
            entries { open = false }
            DropdownMenuItem(text = { Text(mb("播放、歌单与导出", "Playback, Playlists & Export")) }, onClick = { open = false; more() })
        }
    }
}

@Composable
private fun TrackPicker(tracks: List<Track>, close: () -> Unit, add: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val matching = tracks.filter { it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = close, title = { Text(mb("添加歌曲", "Add Songs")) }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(mb("搜索已保存的歌曲", "Search Saved Songs")) })
            }
            if (matching.isEmpty()) item { Text(mb("没有可添加的歌曲", "No Songs to Add"), Modifier.padding(vertical = 12.dp)) }
            items(matching, key = Track::id) { track ->
                val checked = track.id in selection
                ListItem(headlineContent = { Text(track.title, maxLines = 1) }, supportingContent = { Text(track.artist, maxLines = 1) },
                    trailingContent = { Checkbox(checked, onCheckedChange = null) }, modifier = Modifier.clickable {
                        selection = ArrayList(if (checked) selection - track.id else selection + track.id)
                    })
            }
        }
    }, confirmButton = { TextButton(enabled = selection.isNotEmpty(), onClick = { add(selection) }) { Text(mb("添加", "Add")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}

@Composable
internal fun LibraryConfirmation(title: String, detail: String, button: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = confirm) { Text(button) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}
