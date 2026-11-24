package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.DiscoveryCategory

@Composable
internal fun SearchField(query: String, edit: (String) -> Unit, focused: Boolean, focusChanged: (Boolean) -> Unit,
                         dismiss: () -> Unit, submit: (String) -> Unit, platform: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val hint = mb("搜索歌曲或视频", "Search songs or videos")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (platform != null) platform() else Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        BasicTextField(query, onValueChange = edit, singleLine = true,
            modifier = Modifier.weight(1f).onFocusChanged { focusChanged(it.isFocused) }
                .semantics { contentDescription = hint },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submit(query) }),
            decorationBox = { field ->
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            })
        if (query.isNotEmpty()) IconButton(onClick = { submit("") }) {
            Icon(Icons.Default.Close, mb("清空搜索", "Clear Search"), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        TextButton(onClick = { submit(query) }, enabled = query.isNotBlank()) { Text(mb("搜索", "Search")) }
        if (focused) IconButton(onClick = dismiss) { Icon(Icons.Default.Check, mb("完成", "Done")) }
    }
}

@Composable
internal fun SearchScopePicker(mode: SearchScope, change: (SearchScope) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).selectableGroup()
        .background(colors.surfaceContainer, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        SearchScope.entries.forEach { option ->
            val selected = mode == option
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                .background(if (selected) colors.primary.copy(alpha = 0.12f).compositeOver(colors.surfaceContainer) else colors.surfaceContainer)
                .selectable(selected, role = Role.Tab, onClick = { change(option) }).padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center) {
                Text(if (option == SearchScope.Music) mb("音乐", "Music") else mb("视频", "Videos"),
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) colors.primary else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun RecentSearches(history: List<String>, clear: () -> Unit, remove: (String) -> Unit, select: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mb("最近搜索", "Recent Searches"), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = clear) { Text(mb("全部清除", "Clear All")) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.forEach { term ->
                Row(Modifier.widthIn(min = 48.dp, max = 300.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                    Text(term, Modifier.weight(1f, fill = false).clickable(role = Role.Button) { select(term) }.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { remove(term) }) { Icon(Icons.Default.Close, mb("删除搜索记录 $term", "Remove search $term"), Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun BrowseMusicCategories(open: (DiscoveryCategory) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tile = colors.primary.copy(alpha = 0.08f).compositeOver(colors.surfaceContainerLow)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(mb("浏览音乐", "Browse Music"), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        DiscoveryCategory.entries.chunked(2).forEach { categories ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                categories.forEach { category ->
                    Column(Modifier.weight(1f).heightIn(min = 112.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(tile)
                        .clickable(role = Role.Button) { open(category) }.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        when (category) {
                            DiscoveryCategory.Recommended -> Icon(painterResource(R.drawable.ic_music_note), null, tint = colors.primary)
                            DiscoveryCategory.Chart -> Icon(Icons.AutoMirrored.Filled.List, null, tint = colors.primary)
                            DiscoveryCategory.Trending -> Icon(Icons.Default.ThumbUp, null, tint = colors.primary)
                            DiscoveryCategory.Fresh -> Icon(Icons.Default.Star, null, tint = colors.primary)
                        }
                        Text(category.title(), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    }
                }
            }
        }
    }
}
