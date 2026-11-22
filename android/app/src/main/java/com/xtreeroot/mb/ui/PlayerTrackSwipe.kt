package com.xtreeroot.mb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** 手指拖动直接移动舞台；不足阈值回弹，提交后让新资源从另一侧进入。 */
@Composable
internal fun Modifier.trackSwipeMotion(key: Any, vertical: Boolean = false, progress: TrackSwipeProgress? = null,
                                      previous: () -> Unit, next: () -> Unit): Modifier {
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestKey by rememberUpdatedState(key)
    var distance by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val animation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) { if (!settling) distance = 0f }
    val shown = if (settling) animation.value else distance
    SideEffect { progress?.offset = shown }
    // 回弹经过零点时仍在过渡中，辅助技术和连续操作要等舞台真正恢复。
    val feedback = if (settling) mb("切换中", "Switching") else if (shown == 0f) "" else if (vertical) {
        if (shown < 0) mb("切换下一个视频", "Next video") else mb("切换上一个视频", "Previous video")
    } else if (shown < 0) mb("切换下一首", "Next track") else mb("切换上一首", "Previous track")
    return pointerInput(key, vertical) {
        var eligible = false
        val extent = (if (vertical) size.height else size.width).toFloat()
        val threshold = maxOf((if (vertical) 72 else 64).dp.toPx(), extent * .18f)
        val reset = {
            scope.launch {
                animation.snapTo(distance); settling = true
                animation.animateTo(0f, spring(stiffness = 420f))
                distance = 0f; settling = false
            }
            Unit
        }
        val finish = {
            if (!settling) {
                if (!eligible || abs(distance) < threshold) reset()
                else scope.launch {
                    val forward = distance < 0
                    val original = latestKey
                    animation.snapTo(distance); settling = true
                    animation.animateTo(if (forward) -extent else extent, tween(150))
                    if (forward) latestNext() else latestPrevious()
                    // MediaController 跨进程提交选曲，等界面收到新选择再交换舞台。
                    if (progress != null) withTimeoutOrNull(1_000) { snapshotFlow { latestKey }.first { it != original } }
                    // 相邻资源已经随拖动进入中心，提交后直接交换，不再从空白移入。
                    animation.snapTo(0f)
                    distance = 0f; settling = false
                }
            }
            Unit
        }
        if (vertical) detectVerticalDragGestures(
            onDragStart = { eligible = !settling; if (eligible) distance = 0f },
            onDragCancel = { if (!settling) reset() }, onDragEnd = finish,
            onVerticalDrag = { change, delta -> if (eligible && !settling) { change.consume(); distance = (distance + delta).coerceIn(-extent, extent) } })
        else detectHorizontalDragGestures(
            onDragStart = { eligible = !settling && it.x >= 24.dp.toPx(); if (eligible) distance = 0f },
            onDragCancel = { if (!settling) reset() }, onDragEnd = finish,
            onHorizontalDrag = { change, delta -> if (eligible && !settling) { change.consume(); distance = (distance + delta).coerceIn(-extent, extent) } })
    }.graphicsLayer { if (vertical) translationY = shown else translationX = shown }
        .semantics { stateDescription = feedback }
}
