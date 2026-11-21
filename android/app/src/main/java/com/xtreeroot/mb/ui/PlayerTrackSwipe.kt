package com.xtreeroot.mb.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 舞台上的横向切歌手势；保留左边缘返回、纵向歌词滚动及舞台外的拖动控件。 */
@Composable
internal fun Modifier.musicTrackSwipes(key: Any, previous: () -> Unit, next: () -> Unit): Modifier {
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    return pointerInput(key) {
        var distance = 0f
        var eligible = false
        val edge = 24.dp.toPx()
        val threshold = maxOf(64.dp.toPx(), size.width * 0.18f)
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f; eligible = it.x >= edge },
            onDragCancel = { distance = 0f },
            onHorizontalDrag = { _, delta -> distance += delta },
            onDragEnd = {
                if (eligible && distance <= -threshold) latestNext()
                else if (eligible && distance >= threshold) latestPrevious()
                distance = 0f
            })
    }
}
