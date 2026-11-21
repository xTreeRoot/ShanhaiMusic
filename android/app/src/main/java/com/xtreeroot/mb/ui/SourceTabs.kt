package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicTabs(tabs: Map<String, String>, selected: String, select: (String) -> Unit) {
    val entries = tabs.entries.toList()
    val index = entries.indexOfFirst { it.key == selected }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
    LaunchedEffect(selected, entries) {
        if (index < 0) return@LaunchedEffect
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.key == selected }
        if (visible == null || visible.offset < layout.viewportStartOffset || visible.offset + visible.size > layout.viewportEndOffset) {
            state.scrollToItem(index)
        }
    }
    LazyRow(Modifier.fillMaxWidth().selectableGroup(), state = state, contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        items(entries, key = { it.key }) { entry ->
            val current = selected == entry.key
            var textWidth by remember(entry.value) { mutableIntStateOf(0) }
            val indicatorWidth = with(LocalDensity.current) { textWidth.toDp() }
            Box(Modifier.height(48.dp).widthIn(min = 48.dp, max = 144.dp)
                .selectable(current, role = Role.Tab, onClick = { select(entry.key) }).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(entry.value, style = MaterialTheme.typography.titleSmall,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { textWidth = it.size.width })
                if (current) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).width(indicatorWidth).height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            }
        }
    }
}
