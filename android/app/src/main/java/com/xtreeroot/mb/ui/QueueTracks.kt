package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState

private val rowShape = RoundedCornerShape(12.dp)

@Composable
internal fun QueueTracks(model: PlaybackModel, state: PlaybackState, modifier: Modifier = Modifier, onSelect: () -> Unit = {}) {
    val tracks = state.tracks
    if (tracks.isEmpty()) return EmptyMessage(mb("队列为空", "Your queue is empty"))
    ReorderList(tracks, Track::id, state.connected, { from, to -> model.move(from, to, tracks) },
        modifier.padding(horizontal = 8.dp), footer = if (state.queuePages.more) {
            { QueuePageFooter(model, state) }
        } else null) { index, placement, lifted, dragging ->
        val track = tracks[index]
        val current = index == state.index
        var menu by remember { mutableStateOf(false) }
        QueueTrackRow(track, current, lifted, placement,
            contentModifier = Modifier.selectable(current, enabled = state.connected && !dragging, role = Role.Button,
                onClick = { model.select(index); onSelect() })) {
            if (lifted) Box(Modifier.size(trackReorderHandleSize), contentAlignment = Alignment.Center) { TrackReorderHandle(null) }
            else Box {
                IconButton(onClick = { menu = true }, enabled = state.connected && !dragging) {
                    TrackReorderHandle(mb("编辑 ${track.title}", "Edit ${track.title}"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(mb("上移", "Move Up")) }, enabled = index > 0,
                        onClick = { menu = false; model.move(index, index - 1, tracks) })
                    DropdownMenuItem(text = { Text(mb("下移", "Move Down")) }, enabled = index < tracks.lastIndex,
                        onClick = { menu = false; model.move(index, index + 1, tracks) })
                    DropdownMenuItem(text = { Text(mb("从队列移除", "Remove from Queue")) },
                        onClick = { menu = false; model.remove(index, tracks) })
                }
            }
        }
    }
}

@Composable
internal fun QueuePageFooter(model: PlaybackModel, state: PlaybackState) {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        if (state.queuePages.failed) {
            androidx.compose.material3.TextButton(onClick = model::loadNextQueuePage) { Text(mb("重试", "Retry")) }
        } else {
            LaunchedEffect(state.tracks.size) { model.loadNextQueuePage() }
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun QueueTrackRow(track: Track, current: Boolean, lifted: Boolean, modifier: Modifier,
                          contentModifier: Modifier = Modifier, handle: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        lifted -> colors.surfaceContainerHighest
        current -> colors.primary.copy(alpha = 0.11f)
        else -> Color.Transparent
    }
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp)
        .clip(rowShape).background(background), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(contentModifier).padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(40.dp))
            Column {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (current) colors.primary else colors.onSurface)
                ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)) {
                    TrackSubtitle(track)
                }
            }
        }
        handle()
    }
}
