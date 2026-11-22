package com.xtreeroot.mb.ui

import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val mediaStream = AudioManager.STREAM_MUSIC

internal data class MediaVolume(val minimum: Int, val maximum: Int, val index: Int, val fixed: Boolean) {
    val span get() = maximum - minimum
    fun fraction(value: Float) = if (span > 0) ((value - minimum) / span).coerceIn(0f, 1f) else 0f
}

internal fun AudioManager.mediaVolume(): MediaVolume {
    val minimum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getStreamMinVolume(mediaStream) else 0
    val maximum = getStreamMaxVolume(mediaStream).coerceAtLeast(minimum)
    return MediaVolume(minimum, maximum, getStreamVolume(mediaStream).coerceIn(minimum, maximum), isVolumeFixed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemMediaVolume() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var volume by remember(audio) { mutableStateOf(audio.mediaVolume()) }
    var restricted by remember(audio) { mutableStateOf(false) }
    LaunchedEffect(audio, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                volume = audio.mediaVolume()
                delay(500)
            }
        }
    }
    val current = volume
    val label = mb("系统媒体音量", "System Media Volume")
    val colors = MaterialTheme.colorScheme
    val enabled = current.span > 0 && !current.fixed && !restricted
    val accent = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.38f)
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text("${(100 * current.fraction(current.index.toFloat())).roundToInt()}%", color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
            }
            Slider(value = current.index.toFloat(), onValueChange = { value ->
                val target = value.roundToInt().coerceIn(current.minimum, current.maximum)
                if (target != current.index) {
                    try { audio.setStreamVolume(mediaStream, target, 0) }
                    catch (_: SecurityException) { restricted = true }
                    volume = audio.mediaVolume()
                }
            }, valueRange = current.minimum.toFloat()..maxOf(current.maximum, current.minimum + 1).toFloat(),
                steps = (current.span - 1).coerceAtLeast(0), enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label },
                thumb = { Box(Modifier.size(10.dp).background(accent, CircleShape)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(3.dp)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.26f), CircleShape)) {
                        Box(Modifier.fillMaxWidth(current.fraction(slider.value)).height(3.dp).background(accent, CircleShape))
                    }
                })
        }
    }
}
