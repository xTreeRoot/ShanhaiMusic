package com.xtreeroot.mb.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.R
import kotlin.math.abs

internal val trackReorderHandleSize = 48.dp

private data class TrackDrag(val index: Int, val size: Int, val finger: Float, val grabOffset: Float) {
    val center get() = finger + grabOffset
}

private val LazyListItemInfo.center get() = offset + size / 2f

@Composable
internal fun <T> ReorderList(items: List<T>, itemKey: (T) -> String, enabled: Boolean, onMove: (Int, Int) -> Unit,
                              modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null,
                              footer: (@Composable () -> Unit)? = null,
                              row: @Composable (Int, Modifier, Boolean, Boolean) -> Unit) {
    val list = rememberLazyListState()
    val firstTrack = if (header == null) 0 else 1
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val edge = with(LocalDensity.current) { trackReorderHandleSize.toPx() }
    val move by rememberUpdatedState(onMove)
    var drag by remember { mutableStateOf<TrackDrag?>(null) }
    val dragging = drag != null
    fun destination(drag: TrackDrag): Int = list.layoutInfo.visibleItemsInfo.asSequence()
        .filter { it.index - firstTrack in items.indices }.minByOrNull { abs(it.center - drag.center) }
        ?.let { it.index - firstTrack } ?: drag.index

    LaunchedEffect(dragging, list, edge) {
        if (!dragging) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val finger = drag?.finger ?: break
            val height = list.layoutInfo.viewportSize.height
            val zone = minOf(edge, height / 3f)
            if (zone > 0) {
                val direction = when {
                    finger < zone -> (finger - zone) / zone
                    finger > height - zone -> (finger - height + zone) / zone
                    else -> 0f
                }.coerceIn(-1f, 1f)
                if (direction != 0f) {
                    val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                    list.scrollBy(direction * edge * 12 * elapsed)
                }
            }
            previous = now
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        LazyColumn(Modifier.fillMaxWidth().pointerInput(items, enabled, rtl, firstTrack) {
            if (!enabled || items.size < 2) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val distanceFromEnd = if (rtl) down.position.x else size.width - down.position.x
                val handle = trackReorderHandleSize.toPx()
                if (distanceFromEnd !in 0f..handle) return@awaitEachGesture
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.index - firstTrack in items.indices && abs(it.center - down.position.y) <= handle / 2
                } ?: return@awaitEachGesture
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                        if (!pointer.pressed) {
                            drag?.let { active ->
                                pointer.consume()
                                val target = destination(active.copy(finger = pointer.position.y))
                                if (target != active.index) move(active.index, target)
                            }
                            break
                        }
                        val delta = pointer.position - down.position
                        if (drag == null && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y)) break
                        if (drag != null || abs(delta.y) > viewConfiguration.touchSlop) {
                            pointer.consume()
                            drag = TrackDrag(item.index - firstTrack, item.size, pointer.position.y, item.center - down.position.y)
                        }
                    }
                } finally { drag = null }
            }
        }, state = list, contentPadding = PaddingValues(bottom = 14.dp)) {
            if (header != null) item(content = { header() })
            itemsIndexed(items, key = { index, item -> "$index:${itemKey(item)}" }) { index, _ ->
                val active = drag
                val hidden = active?.index == index
                val place = Modifier.offset {
                    val vertical = if (active == null) 0 else {
                        val target = destination(active)
                        when {
                            index > active.index && index <= target -> -active.size
                            index < active.index && index >= target -> active.size
                            else -> 0
                        }
                    }
                    IntOffset(0, vertical)
                }
                val visibility = if (hidden) place.graphicsLayer { alpha = 0f } else place
                row(index, if (dragging) visibility.clearAndSetSemantics {} else visibility, false, dragging)
            }
            if (footer != null) item { footer() }
        }
        drag?.takeIf { it.index in items.indices }?.let { active ->
            row(active.index, Modifier.graphicsLayer { translationY = active.center - active.size / 2f }
                .clearAndSetSemantics {}, true, true)
        }
    }
}

@Composable
internal fun TrackReorderHandle(description: String?) {
    Icon(painterResource(R.drawable.ic_queue_reorder), description, Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
