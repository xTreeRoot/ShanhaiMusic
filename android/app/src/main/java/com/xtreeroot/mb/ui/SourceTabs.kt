package com.xtreeroot.mb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 标签文字保持清晰对比度，指示条直接使用分页位置插值，拖动中不等到选中才移动。 */
@Composable
internal fun MusicTabs(tabs: Map<String, String>, selected: String, pagePosition: Float? = null, select: (String) -> Unit) {
    val entries = tabs.entries.toList()
    val index = entries.indexOfFirst { it.key == selected }
    val position = (pagePosition ?: index.toFloat()).coerceIn(0f, (entries.size - 1).coerceAtLeast(0).toFloat())
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(selected, entries) { if (index >= 0) state.animateScrollToItem(index) }
    LaunchedEffect(position.roundToInt()) {
        val target = position.roundToInt()
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == target }
        if (target in entries.indices && (visible == null || visible.offset < layout.viewportStartOffset || visible.offset + visible.size > layout.viewportEndOffset)) state.animateScrollToItem(target)
    }
    val accent = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f)
    Box(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        LazyRow(Modifier.fillMaxWidth().selectableGroup(), state = state, contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(entries, key = { _, it -> it.key }) { itemIndex, entry ->
                val current = selected == entry.key
                val weight = (1f - abs(itemIndex - position)).coerceIn(0f, 1f)
                Box(Modifier.height(48.dp).widthIn(min = 48.dp, max = 144.dp)
                    .selectable(current, role = Role.Tab, onClick = { select(entry.key) }).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(entry.value, Modifier.onGloballyPositioned { bounds[entry.key] = it.boundsInWindow() }
                        .graphicsLayer { scaleX = 1f + weight * .02f; scaleY = scaleX },
                        style = MaterialTheme.typography.titleSmall, color = lerp(inactive, accent, weight),
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val a = entries.getOrNull(floor(position).toInt())?.let { bounds[it.key] }
            val b = entries.getOrNull(ceil(position).toInt())?.let { bounds[it.key] }
            if (a != null && b != null) {
                val fraction = position - floor(position)
                val left = a.left + (b.left - a.left) * fraction - origin.x
                val width = a.width + (b.width - a.width) * fraction
                drawRoundRect(accent, Offset(left, size.height - 5.dp.toPx()), Size(width, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
    }
}
