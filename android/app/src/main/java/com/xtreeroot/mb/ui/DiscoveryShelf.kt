package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 按可见宽度先填满一行，最多两行；后续资源横滑，点击仍使用原始索引。 */
@Composable
internal fun DiscoveryShelf(count: Int, itemKey: (Int) -> Any = { it }, content: @Composable (Int) -> Unit) {
    if (count == 0) return
    val rowHeight = 246.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth - 40.dp + 16.dp) / 172.dp).toInt().coerceAtLeast(1)
        val rows = ((count + columns - 1) / columns).coerceIn(1, 2)
        val capacity = columns * 2
        LazyRow(Modifier.height(rowHeight * rows + 16.dp * (rows - 1)),
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items((0 until count step capacity).toList(), key = { itemKey(it) }) { start ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(rows) { row ->
                        val first = start + row * columns
                        if (first < count) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (index in first until minOf(first + columns, count)) key(itemKey(index)) {
                                Column(Modifier.width(156.dp).height(rowHeight)) { content(index) }
                            }
                        }
                    }
                }
            }
        }
    }
}
