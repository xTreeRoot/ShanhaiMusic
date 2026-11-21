package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.preferences.BackgroundAccent
import com.xtreeroot.mb.preferences.BackgroundSkinStyle
import kotlin.math.roundToInt

/** 真正发现页上的皮肤草稿编辑器，确认前不修改当前皮肤或任何持久设置。 */
@Composable
internal fun BackgroundPreviewControls(state: AppearanceBackgroundState, opacity: (Float) -> Unit, cancel: () -> Unit,
                                       confirm: () -> Unit, style: ((BackgroundSkinStyle) -> BackgroundSkinStyle) -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "Discovery background preview controls" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.preview?.let { image ->
                    SkinImage(image, state.previewStyle, 1f, Modifier.size(88.dp, 58.dp)
                        .semantics { contentDescription = "Background position preview" }
                        .pointerInput(image, state.previewStyle.zoom) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                style { current -> current.copy(horizontal = (current.horizontal + delta.x / size.width * 2).coerceIn(-1f, 1f),
                                    vertical = (current.vertical + delta.y / size.height * 2).coerceIn(-1f, 1f)) }
                            }
                        })
                }
                Column {
                    Text(mb("自定义皮肤", "Custom Skin"), style = MaterialTheme.typography.titleMedium)
                    Text(mb("拖动左图调整位置", "Drag the image to position"), style = MaterialTheme.typography.bodySmall)
                }
            }
            SkinSlider(mb("图片缩放", "Zoom"), state.previewStyle.zoom, { value -> style { it.copy(zoom = value) } }, 1f..4f, "Background image zoom", String.format(java.util.Locale.ROOT, "%.1f×", state.previewStyle.zoom), !state.busy)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(mb("主题色", "Theme"), Modifier.width(66.dp), style = MaterialTheme.typography.bodyMedium)
                BackgroundAccent.entries.forEach { accent ->
                    Box(Modifier.size(26.dp).border(if (state.previewStyle.accent == accent) 1.5.dp else 0.dp,
                        if (state.previewStyle.accent == accent) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .padding(3.dp).background(accent.color(musicLight.primary), CircleShape)
                        .semantics { contentDescription = "Theme ${accent.name}" }
                        .clickable(enabled = !state.busy) { style { it.copy(accent = accent) } })
                }
            }
            SkinSlider(mb("透明度", "Transparency"), 1f - state.previewOpacity, { opacity(1f - it) }, 0f..1f, "Background transparency", "${((1f - state.previewOpacity) * 100).roundToInt()}%", !state.busy)
            SkinSlider(mb("模糊度", "Blur"), state.previewStyle.blur, { value -> style { it.copy(blur = value) } }, 0f..30f, "Background blur", "${state.previewStyle.blur.roundToInt()}", !state.busy)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(mb("应用于音乐播放器背景", "Apply to Music Player"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(state.previewStyle.appliesToPlayer, { value -> style { it.copy(appliesToPlayer = value) } }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "Background player switch" })
            }
            if (state.error != null) Text(mb("无法更新背景图片", "Cannot update background image"), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = cancel, enabled = !state.busy) { Text(mb("取消", "Cancel")) }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                Button(onClick = confirm, enabled = !state.busy) { Text(mb("使用背景", "Use Background")) }
            }
        }
    }
}

@Composable
private fun SkinSlider(title: String, value: Float, change: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, label: String, display: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(if (LocalMBEnglish.current) 92.dp else 66.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Slider(value, change, valueRange = range, enabled = enabled, modifier = Modifier.weight(1f)
            .height(32.dp).padding(horizontal = 8.dp).semantics { contentDescription = label })
        Text(display, Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
    }
}
