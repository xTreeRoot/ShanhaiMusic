package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.R
import com.xtreeroot.mb.plugin.*
import com.xtreeroot.mb.ui.components.*
import kotlinx.coroutines.launch

/** 本地草稿取消不写入，保存仅提交实际改动，保留编辑时到达的节点数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DiscoverySectionsEditor(source: PluginSource, installer: PluginInstaller, close: () -> Unit) {
    val document by installer.discoveryPreferences.collectAsState()
    val upLabel = mb("上移", "Move Up"); val downLabel = mb("下移", "Move Down")
    val sections = source.manifest.discoverySections
    val before = remember(source) { document.resolve(source.id, sections.map { it.preferenceSection }) }
    var draft by remember(source) { mutableStateOf(before) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun move(id: String, target: Int) {
        val order = draft.order.toMutableList(); val from = order.indexOf(id)
        if (from >= 0 && target in order.indices && target != from) { order.removeAt(from); order.add(target, id); draft = draft.copy(order = order) }
    }
    MBBottomSheet(onDismissRequest = { if (!busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MBTextButton(onClick = close, enabled = !busy) { Text(mb("取消", "Cancel")) }
            Text(mb("发现栏目", "Discovery Sections"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            MBTextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { installer.saveDiscovery(source.id, before, draft); close() }
                    catch (failure: Exception) { error = failure.message; busy = false }
                }
            }) { Text(mb("保存", "Save")) }
        }
        ReorderList(draft.order, { it }, !busy, { from, to -> move(draft.order[from], to) },
            Modifier.fillMaxWidth().heightIn(max = 560.dp)) { index, placement, _, dragging ->
            val id = draft.order[index]
            val section = sections.first { it.id == id }
            Row(placement.fillMaxWidth().alpha(if (dragging) .7f else 1f).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                MBSwitch(draft.enabled[id] == true, { draft = draft.copy(enabled = draft.enabled + (id to it)) },
                    Modifier.semantics { contentDescription = "栏目开关 ${section.title}" }, enabled = !busy)
                Icon(painterResource(R.drawable.ic_queue_reorder), "排序 ${section.title}", Modifier.size(44.dp)
                    .semantics { customActions = listOf(
                        CustomAccessibilityAction(upLabel) { move(id, index - 1); true },
                        CustomAccessibilityAction(downLabel) { move(id, index + 1); true }) })
            }
        }
        MBTextButton(enabled = !busy, onClick = { draft = DiscoveryPreferences().resolve(source.id, sections.map { it.preferenceSection }) }) { Text(mb("恢复默认", "Restore Defaults")) }
        error?.let { message -> Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
    }
}
