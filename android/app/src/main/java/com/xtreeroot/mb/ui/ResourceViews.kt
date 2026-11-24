package com.xtreeroot.mb.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton

internal val LocalSourceNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

@Composable internal fun resourceTypeName(type: String?): String = when (type) {
    "video" -> mb("视频", "Video")
    "book" -> mb("图书", "Book")
    "software" -> mb("软件", "Software")
    "other" -> mb("其他", "Other")
    else -> mb("音乐", "Music")
}

@Composable internal fun ResourceRow(track: Track, saved: SavedLibraryModel?, inLibrary: Boolean = false, play: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf(false) }
    val actions = LocalTrackActions.current
    val context = LocalContext.current
    val names = LocalSourceNames.current
    val type = resourceTypeName(track.resourceType)
    val open = { if (track.resourceUrl != null) detail = true else play() }
    CompositionLocalProvider(LocalTrackActions provides { item -> if (item.resourceUrl != null) detail = true else actions(item) }) {
        Column {
            TrackRow(track, actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}")) }
                    DropdownMenu(menu, { menu = false }) {
                        if (saved != null) DropdownMenuItem(text = { Text(if (inLibrary) mb("移除", "Remove") else mb("保存到资料库", "Save to Library")) }, onClick = {
                            menu = false
                            if (inLibrary) saved.removeResource(track.id) else saved.saveResource(track)
                        })
                        if (track.resourceUrl == null) DropdownMenuItem(text = { Text(mb("更多操作", "More Actions")) }, onClick = { menu = false; actions(track) })
                        else DropdownMenuItem(text = { Text(mb("分享资源链接", "Share Resource Link")) }, onClick = {
                            menu = false
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, track.resourceUrl), null))
                        })
                    }
                }
            }, play = open)
            Text("${names[track.sourceId] ?: track.sourceId} · $type",
                Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (detail) ResourceDetail(track, saved) { detail = false }
}

@Composable private fun ResourceDetail(track: Track, saved: SavedLibraryModel?, close: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { error = "未找到可打开此链接的应用，请分享资源链接" }
    }
    AlertDialog(onDismissRequest = close, title = { Text(track.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${LocalSourceNames.current[track.sourceId] ?: track.sourceId} · ${resourceTypeName(track.resourceType)}")
            Text(track.artist, style = MaterialTheme.typography.bodySmall)
            track.resourceUrl?.let { url -> TextButton(onClick = { open(url) }) { Text(mb("打开资源链接", "Open Resource Link")) } }
            track.pageUrl?.let { url -> TextButton(onClick = { open(url) }) { Text(mb("查看来源", "View Source")) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { if (saved != null) TextButton(onClick = { saved.saveResource(track); close() }) { Text(mb("保存到资料库", "Save to Library")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("完成", "Done")) } })
}
