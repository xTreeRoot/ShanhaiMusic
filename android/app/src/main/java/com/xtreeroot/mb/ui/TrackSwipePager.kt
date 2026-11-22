package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.Track

internal class TrackSwipeProgress {
    var offset by mutableFloatStateOf(0f)
}

/** 当前舞台与真实队列相邻封面同时拖动，松手提交之前不切歌、不启动相邻视频。 */
@Composable
internal fun TrackSwipePager(selection: Any, previousTrack: Track?, nextTrack: Track?, previous: () -> Unit,
                            next: () -> Unit, modifier: Modifier = Modifier, vertical: Boolean = false,
                            content: @Composable BoxScope.() -> Unit) {
    val motion = remember { TrackSwipeProgress() }
    var previewPrevious by remember { mutableStateOf(previousTrack) }
    var previewNext by remember { mutableStateOf(nextTrack) }
    SideEffect { if (motion.offset == 0f) { previewPrevious = previousTrack; previewNext = nextTrack } }
    BoxWithConstraints(modifier.clipToBounds()) {
        val extent = (if (vertical) constraints.maxHeight else constraints.maxWidth).toFloat()
        val coverSize = minOf(maxWidth - 40.dp, maxHeight - 84.dp, 440.dp).coerceAtLeast(60.dp)
        val track = if (motion.offset < 0) previewNext else previewPrevious
        if (motion.offset != 0f && track != null) {
            val label = (if (motion.offset < 0) mb("下一条预览", "Next Preview") else mb("上一条预览", "Previous Preview")) + " ${track.title} · ${track.artist}"
            Box(Modifier.fillMaxSize().graphicsLayer {
                val distance = (if (motion.offset < 0) extent else -extent) + motion.offset
                if (vertical) translationY = distance else translationX = distance
            }.semantics(mergeDescendants = true) { contentDescription = label }) {
                if (vertical) {
                    Artwork(track, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        if (motion.offset < 0) listOf(Color.Black.copy(alpha = .85f), Color.Transparent)
                        else listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
                } else {
                    Artwork(track, Modifier.align(Alignment.Center).padding(bottom = 64.dp).size(coverSize))
                }
                Column(Modifier.align(if (vertical && motion.offset < 0) Alignment.TopStart else Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = if (vertical) Color.White else MaterialTheme.colorScheme.onSurface
                    Text(track.title, color = color, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxSize().trackSwipeMotion(selection, vertical, motion, previous, next), contentAlignment = Alignment.Center, content = content)
    }
}
