package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.playback.VideoFrameState
import com.xtreeroot.mb.ui.components.MBTextButton

/** 只展示插件真实提供的画质，选择保存为全局偏好，单条视频回退不会覆盖偏好。 */
@Composable internal fun VideoQualityMenu(frame: VideoFrameState?) {
    if (frame?.qualities.isNullOrEmpty()) return
    val app = LocalContext.current.applicationContext as MBApplication
    val preferences by app.settings.values.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val choose: (String) -> Unit = { id -> expanded = false; app.settings.update { it.copy(videoQuality = id) } }
    val label = mb("画质", "Video Quality")
    Box {
        MBTextButton(onClick = { expanded = true }, modifier = Modifier.testTag("video-quality").semantics {
            contentDescription = label
        }) { Text(frame?.quality ?: label, color = Color.White) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(mb("自动（最高可用）", "Automatic (Best Available)")) }, onClick = { choose("auto") },
                modifier = Modifier.testTag("video-quality-auto").semantics { selected = preferences.videoQuality == "auto" })
            frame?.qualities.orEmpty().forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { choose(option.id) },
                    modifier = Modifier.testTag("video-quality-${option.id}").semantics { selected = preferences.videoQuality == option.id })
            }
            if (preferences.videoQuality != "auto" && frame?.qualities.orEmpty().none { it.id == preferences.videoQuality }) {
                DropdownMenuItem(text = { Text(mb("首选 ${preferences.videoQuality} · 当前视频不可用", "Preferred ${preferences.videoQuality} unavailable")) },
                    onClick = {}, enabled = false)
            }
            frame?.quality?.let { actual -> DropdownMenuItem(text = { Text(mb("当前画质 $actual", "Current quality $actual")) }, onClick = {}, enabled = false) }
        }
    }
}

/** 竖屏面板使用与横屏菜单相同的持久画质配置，无填充文字选项。 */
@Composable internal fun VideoQualityOptions(frame: VideoFrameState?) {
    val app = LocalContext.current.applicationContext as MBApplication
    val preferences by app.settings.values.collectAsStateWithLifecycle()
    val options = listOf("auto" to mb("自动（最高可用）", "Automatic (Best Available)")) + frame?.qualities.orEmpty().map { it.id to it.label }
    for ((id, label) in options) {
        MBTextButton(onClick = { app.settings.update { it.copy(videoQuality = id) } }, modifier = Modifier.fillMaxWidth().testTag("video-quality-$id")
            .semantics { selected = preferences.videoQuality == id }) {
            Text(label, Modifier.weight(1f))
            if (preferences.videoQuality == id) Text("✓")
        }
    }
    if (preferences.videoQuality != "auto" && frame?.qualities.orEmpty().none { it.id == preferences.videoQuality }) {
        Text(mb("首选 ${preferences.videoQuality} · 当前视频不可用", "Preferred ${preferences.videoQuality} unavailable"))
    }
}
