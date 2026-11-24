package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xtreeroot.mb.playback.PlaybackStation
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginEntity
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.ui.components.MBTextButton
import com.xtreeroot.mb.ui.components.MBTopAppBar

/** 播放器作者入口沿用插件目录协议，与歌曲当前来源绑定，保留目录分页和曲目操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginCreatorDialog(source: PluginSource, creator: PluginEntity, close: () -> Unit,
                                 play: (List<Track>, Track) -> Unit,
                                 station: (List<Track>, Track, PlaybackStation) -> Unit) {
    key(source, creator.id) {
        val navigator = remember { PluginCatalogNavigator().apply { open(PluginCatalogRequest.Entity(creator)) } }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background, topBar = {
                MBTopAppBar(title = { Text(creator.title) }, actions = {
                    if (navigator.current != null) MBTextButton(onClick = { navigator.back(); if (navigator.current == null) close() }) { Text(mb("返回", "Back")) }
                    MBTextButton(onClick = close) { Text(mb("完成", "Done")) }
                })
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    navigator.current?.let { PluginCatalogBrowser(source, it, navigator, play, station) }
                }
            }
        }
    }
}
