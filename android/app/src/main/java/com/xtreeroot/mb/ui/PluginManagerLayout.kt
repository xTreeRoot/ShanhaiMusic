package com.xtreeroot.mb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.res.painterResource
import com.xtreeroot.mb.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.ui.components.*
import kotlin.math.roundToInt

/** 插件管理沿用分组列表：点按详情、长按名称、左滑删除、编辑时拖动排序。 */
@Composable
internal fun PluginsOverview(state: PluginManagerState, reordering: Boolean, open: (InstalledPlugin) -> Unit,
                              rename: (InstalledPlugin) -> Unit, remove: (InstalledPlugin) -> Unit,
                              move: (InstalledPlugin, Int) -> Unit, import: () -> Unit, update: () -> Unit,
                              zip: () -> Unit, folder: () -> Unit, github: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            MBGroupedSection {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    MBTextButton(import, enabled = !state.busy && state.pending == null) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(mb("一键导入/覆盖", "Import / Overwrite"))
                    }
                    MBTextButton(update, enabled = !state.busy && state.installed.any { it.repository != null }) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(mb("全部更新", "Update All"))
                    }
                }
            }
        }
        if (state.installed.isNotEmpty()) item {
            MBGroupedSection {
                state.installed.forEachIndexed { index, plugin ->
                    if (index > 0) MBFormDivider()
                    key(plugin.id) { PluginManagerRow(plugin, reordering, state.busy, index, state.installed.lastIndex,
                        { open(plugin) }, { rename(plugin) }, { remove(plugin) }, { move(plugin, it) }) }
                }
            }
        }
        item {
            MBGroupedSection {
                MBFormRow(mb("从 ZIP 安装", "Install from ZIP"), leading = { Icon(painterResource(R.drawable.ic_description), null) }, enabled = !state.busy, onClick = zip)
                MBFormDivider()
                MBFormRow(mb("从文件夹安装", "Install from Folder"), leading = { Icon(painterResource(R.drawable.ic_folder), null) }, enabled = !state.busy, onClick = folder)
                MBFormDivider()
                MBFormRow(mb("从 GitHub 安装", "Install from GitHub"), leading = { Icon(painterResource(R.drawable.ic_download), null) }, enabled = !state.busy, onClick = github)
            }
        }
        if (state.busy && state.check == null) item { Loading() }
        state.error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PluginManagerRow(plugin: InstalledPlugin, editing: Boolean, busy: Boolean, index: Int, last: Int,
                              open: () -> Unit, rename: () -> Unit, remove: () -> Unit, move: (Int) -> Unit) {
    var height by remember(plugin.id) { mutableIntStateOf(1) }
    var drag by remember(plugin.id) { mutableFloatStateOf(0f) }
    val latestMove by rememberUpdatedState(move)
    val latestIndex by rememberUpdatedState(index)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart && !busy) remove()
        false
    })
    val moveUp = mb("上移", "Move Up")
    val moveDown = mb("下移", "Move Down")
    SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !editing && !busy,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Delete, mb("删除", "Delete"), Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.onError)
            }
        }) {
        Row(Modifier.fillMaxWidth().background(skinSurface()).onSizeChanged { height = it.height }
            .combinedClickable(enabled = !busy && !editing, onClick = open, onLongClick = rename)
            .semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction("Rename ${plugin.displayName}") { rename(); true })
                    add(CustomAccessibilityAction("Delete ${plugin.displayName}") { remove(); true })
                    if (index > 0) add(CustomAccessibilityAction("$moveUp ${plugin.displayName}") { move(-1); true })
                    if (index < last) add(CustomAccessibilityAction("$moveDown ${plugin.displayName}") { move(1); true })
                }
            }.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (editing) IconButton(remove, enabled = !busy) { Icon(Icons.Default.Delete, mb("删除", "Delete"), tint = MaterialTheme.colorScheme.error) }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_plugin), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(plugin.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                plugin.manifest.version?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(if (plugin.enabled) mb("已启用", "Enabled") else mb("已停用", "Disabled"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editing) Icon(painterResource(R.drawable.ic_queue_reorder), null, Modifier.size(44.dp)
                .semantics { contentDescription = "Reorder ${plugin.displayName}" }
                .pointerInput(plugin.id, busy, last) {
                    if (!busy) detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                        onDragEnd = {
                            val target = (latestIndex + (drag / height).roundToInt()).coerceIn(0, last)
                            val step = target - latestIndex
                            if (step != 0) latestMove(step)
                            drag = 0f
                        }) { change, amount -> change.consume(); drag += amount }
                }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
        }
    }
}
