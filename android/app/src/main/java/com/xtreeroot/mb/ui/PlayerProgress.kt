package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.playback.PlaybackState
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerProgress(state: PlaybackState, smoke: Boolean, seek: (Long) -> Unit,
                            interactions: MutableInteractionSource = remember { MutableInteractionSource() }) {
    key(state.selectionRevision, state.current?.id, state.index) {
        var draft by remember { mutableStateOf<Float?>(null) }
        val duration = state.durationMs.coerceAtLeast(0)
        val position = state.positionMs.coerceIn(0, duration)
        val value = draft ?: if (duration > 0) (position.toDouble() / duration).toFloat() else 0f
        val shownPosition = draft?.let { (it.toDouble() * duration).roundToLong() } ?: position
        val active = duration > 0 && state.connected && state.seekable
        val dragging by interactions.collectIsDraggedAsState()
        val focused by interactions.collectIsFocusedAsState()
        val colors = MaterialTheme.colorScheme
        val label = mb("播放进度", "Playback Progress")
        val currentTime = timeLabel(shownPosition)
        val totalTime = timeLabel(duration)
        val accessibleTime = mb("$currentTime，共 $totalTime", "$currentTime of $totalTime")
        val accent = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.38f)
        Box(Modifier.fillMaxWidth()) {
            Slider(value = value, onValueChange = { draft = it }, onValueChangeFinished = {
                val target = draft
                draft = null
                if (active && target != null) seek((target.toDouble() * duration).roundToLong())
            }, enabled = active, interactionSource = interactions,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                    contentDescription = label
                    stateDescription = accessibleTime
                },
                thumb = {
                    Box(Modifier.width(12.dp).height(48.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (dragging || focused) 12.dp else 8.dp).background(accent, CircleShape))
                    }
                }, track = { slider ->
                    Box(Modifier.fillMaxWidth().height(if (dragging) 4.dp else 3.dp)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.26f), CircleShape)) {
                        Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).height(if (dragging) 4.dp else 3.dp)
                            .background(accent, CircleShape))
                    }
                })
            if (smoke && state.playing && active) ProgressSmoke(value, shownPosition, Modifier.matchParentSize())
        }
        val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(currentTime, color = colors.onSurfaceVariant, style = timeStyle)
            Text(totalTime, color = colors.onSurfaceVariant, style = timeStyle)
        }
    }
}
