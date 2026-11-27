package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginEntity
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 播放器与歌曲列表共用，写入所选插件账号的真实歌单。 */
@Composable
internal fun PlatformPlaylistAction(track: Track, source: PluginSource? =
    (LocalContext.current.applicationContext as MBApplication).playbackSource(track.sourceId) as? PluginSource) {
    if (track.sourceId == com.xtreeroot.mb.local.localSourceId) return
    var showing by remember(track.id, source) { mutableStateOf(false) }
    TextButton(onClick = { showing = true }, modifier = Modifier.fillMaxWidth().testTag("add-platform-playlist")) {
        Text(mb("添加到平台歌单", "Add to Platform Playlist"))
    }
    if (showing) PlatformPlaylistDialog(track, source) { showing = false }
}

@Composable
private fun PlatformPlaylistDialog(track: Track, source: PluginSource?, close: () -> Unit) {
    var targets by remember { mutableStateOf<List<PluginEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val unavailable = mb("此插件尚未提供平台歌单写入接口", "This plugin does not provide platform playlist editing")
    LaunchedEffect(source, reload) {
        loading = true; error = null
        try { targets = source?.writablePlaylists() ?: throw IllegalStateException(unavailable) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: unavailable }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text(mb("添加到平台歌单", "Add to Platform Playlist")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.title)
                targets.forEach { playlist ->
                    TextButton(enabled = !loading && !saving, modifier = Modifier.fillMaxWidth().testTag("platform-playlist-${playlist.id}"),
                        onClick = {
                            saving = true; error = null
                            scope.launch {
                                try { checkNotNull(source).addToPlatformPlaylist(track, playlist); close() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: unavailable }
                                finally { saving = false }
                            }
                        }) { Text(playlist.title) }
                }
                if (loading || saving) CircularProgressIndicator(Modifier.size(24.dp))
                if (!loading && error == null && targets.isEmpty()) Text(mb("没有可编辑的平台歌单", "No editable platform playlists"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { if (error != null) TextButton(enabled = !saving, onClick = { reload++ }) { Text(mb("重试", "Retry")) } },
        dismissButton = { TextButton(enabled = !saving, onClick = close) { Text(mb("关闭", "Close")) } })
}
