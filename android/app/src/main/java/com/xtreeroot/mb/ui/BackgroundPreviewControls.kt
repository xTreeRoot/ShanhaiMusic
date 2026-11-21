package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 在真实发现页底部调节候选背景，确认前不修改已保存图片和透明度。 */
@Composable
internal fun BackgroundPreviewControls(state: AppearanceBackgroundState, opacity: (Float) -> Unit, cancel: () -> Unit, confirm: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "Discovery background preview controls" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mb("透明度", "Transparency"))
                Slider(1f - state.previewOpacity, { opacity(1f - it) }, enabled = !state.busy, modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp).semantics { contentDescription = "Background transparency" })
                Text("${((1f - state.previewOpacity) * 100).roundToInt()}%")
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
