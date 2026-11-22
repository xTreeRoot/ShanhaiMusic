package com.xtreeroot.mb.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.playback.VideoAdjustmentDrag
import com.xtreeroot.mb.playback.VideoAdjustmentKind
import com.xtreeroot.mb.ui.components.MBAlertDialog
import com.xtreeroot.mb.ui.components.MBTextButton
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 横屏视频直接调系统媒体音量与系统亮度；没有修改系统设置权限时只提供授权入口。 */
@Composable internal fun VideoAdjustmentControls(enabled: Boolean, modifier: Modifier,
                                               content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val system = remember(context) { SystemVideoAdjustments(context) }
    var kind by remember { mutableStateOf(VideoAdjustmentKind.Volume) }
    var value by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var restricted by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(false) }
    val label = if (kind == VideoAdjustmentKind.Volume) mb("系统音量", "System Volume") else mb("系统亮度", "System Brightness")
    val message = if (kind == VideoAdjustmentKind.Brightness) mb("需要允许修改系统设置", "Allow modifying system settings")
        else mb("当前设备无法调整系统音量", "System volume cannot be adjusted on this device")
    LaunchedEffect(dragging, revision) { if (!dragging) { delay(1500); visible = false } }
    val input = if (!enabled) Modifier else Modifier.pointerInput(system) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val selected = if (down.position.x < size.width / 2f) VideoAdjustmentKind.Volume else VideoAdjustmentKind.Brightness
            var translation = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> translation = over; change.consume() }
            if (start != null) {
                kind = selected; restricted = !system.available(selected); dragging = true; visible = true
                val baseline = system.read(selected)
                val drag = VideoAdjustmentDrag(selected, baseline, size.height.toFloat())
                value = baseline
                fun adjust() { if (!restricted) {
                    val actual = system.write(selected, drag.value(translation))
                    if (actual == null) restricted = true else value = actual
                } }
                try {
                    adjust()
                    verticalDrag(start.id) { change ->
                        translation += change.positionChange().y
                        change.consume(); adjust()
                    }
                } finally {
                    dragging = false; revision++
                }
                if (restricted && selected == VideoAdjustmentKind.Brightness) permission = true
            }
        }
    }
    Box(modifier.then(input), contentAlignment = Alignment.Center) {
        content()
        AnimatedVisibility(visible && enabled, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.widthIn(min = 160.dp, max = 240.dp)
                .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(18.dp)).padding(horizontal = 20.dp, vertical = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (restricted) message else "${(value * 100).roundToInt()}%" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(if (kind == VideoAdjustmentKind.Brightness) com.xtreeroot.mb.R.drawable.ic_system_brightness
                    else com.xtreeroot.mb.R.drawable.ic_audio_output),
                    null, Modifier.size(28.dp), tint = Color.White)
                Text(if (restricted) message else "$label ${(value * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (!restricted) Box(Modifier.width(136.dp).height(4.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
    if (permission) MBAlertDialog(onDismissRequest = { permission = false },
        title = { Text(mb("调节系统亮度", "Adjust System Brightness")) },
        text = { Text(mb("允许山海修改系统设置后，右侧上下滑动即可调节系统亮度。", "Allow Shanhai to modify system settings, then swipe vertically on the right to change system brightness.")) },
        confirmButton = { MBTextButton(onClick = {
            permission = false
            try { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (_: ActivityNotFoundException) { visible = true; revision++ }
        }) { Text(mb("打开系统授权", "Open System Permission")) } },
        dismissButton = { MBTextButton(onClick = { permission = false }) { Text(mb("暂不", "Not Now")) } })
}

private class SystemVideoAdjustments(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    fun available(kind: VideoAdjustmentKind): Boolean = when (kind) {
        VideoAdjustmentKind.Volume -> audio?.mediaVolume()?.let { !it.fixed && it.span > 0 } == true
        VideoAdjustmentKind.Brightness -> Settings.System.canWrite(context)
    }
    fun read(kind: VideoAdjustmentKind): Float = when (kind) {
        VideoAdjustmentKind.Volume -> audio?.mediaVolume()?.let { it.fraction(it.index.toFloat()) } ?: 0f
        VideoAdjustmentKind.Brightness -> Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }
    fun write(kind: VideoAdjustmentKind, value: Float): Float? = try {
        if (!available(kind)) null else when (kind) {
            VideoAdjustmentKind.Volume -> {
                val current = checkNotNull(audio).mediaVolume()
                val target = (current.minimum + value * current.span).roundToInt().coerceIn(current.minimum, current.maximum)
                if (target != current.index) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                read(kind)
            }
            VideoAdjustmentKind.Brightness -> {
                val resolver = context.contentResolver
                val manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                val changed = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, manual) == manual ||
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, manual)
                if (changed && Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (value * 255).roundToInt().coerceIn(3, 255))) read(kind) else null
            }
        }
    } catch (_: SecurityException) { null }
}
