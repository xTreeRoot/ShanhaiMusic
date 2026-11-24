package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.xtreeroot.mb.R
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.VideoFrameState
import kotlin.math.roundToInt

/** 直接显示当前解码器，应用内小窗和系统画中画不产生第二条请求或音轨。 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable internal fun FloatingVideoWindow(frame: VideoFrameState?, state: PlaybackState, system: Boolean,
    toggle: () -> Unit, expand: () -> Unit, close: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var offset by remember { mutableStateOf(Offset.Zero) }
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val width = minOf(224.dp, maxWidth * 0.6f)
        val ratio = (frame?.aspectRatio ?: 16f / 9f).coerceIn(0.56f, 2.39f)
        val height = minOf(width / ratio, maxHeight * 0.6f)
        val modifier = if (system) Modifier.fillMaxSize() else Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 100.dp)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }.size(width, height)
            .pointerInput(maxWidth, maxHeight) { detectDragGestures { change, delta ->
                change.consume()
                offset = Offset((offset.x + delta.x).coerceIn(-(maxWidth - width - 24.dp).value.coerceAtLeast(0f) * density, 0f),
                    (offset.y + delta.y).coerceIn(-(maxHeight - height - 112.dp).value.coerceAtLeast(0f) * density, 0f))
            } }.clip(RoundedCornerShape(12.dp))
        Box(modifier.background(Color.Black).semantics { contentDescription = "Floating video window" }) {
            AndroidView(factory = { PlayerView(it).apply { useController = false } },
                update = { it.player = frame?.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
            if (!system) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, mb("关闭视频小窗", "Close Video Window"), tint = Color.White) }
                    IconButton(onClick = expand) { Icon(painterResource(R.drawable.ic_fullscreen), mb("展开视频", "Expand Video"), tint = Color.White) }
                }
                IconButton(onClick = toggle, modifier = Modifier.align(Alignment.BottomStart)) {
                    Icon(if (state.playWhenReady) painterResource(R.drawable.ic_media_pause) else painterResource(android.R.drawable.ic_media_play),
                        mb("播放或暂停", "Play or Pause"), tint = Color.White)
                }
            }
        }
    }
}
