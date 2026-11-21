package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.plugin.PendingPlugin
import com.xtreeroot.mb.ui.components.*

/** 安装确认使用分组面板，展示当前/新版信息，网络权限默认折叠。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginReviewDialog(candidate: PendingPlugin, busy: Boolean, batch: Boolean, model: PluginManagerModel, error: String?) {
    MBBottomSheet(onDismissRequest = { if (!busy) model.discard() },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MBTextButton(model::discard, enabled = !busy) { Text(if (batch) mb("跳过", "Skip") else mb("取消", "Cancel")) }
            MBTextButton(model::install, enabled = !busy) { Text(if (candidate.replaces != null) mb("更新", "Update") else mb("安装", "Install")) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MBGroupedSection {
                MBFormRow(mb("名称", "Name"), detail = candidate.manifest.name)
                candidate.replaces?.let { MBFormDivider(); MBFormRow(mb("当前版本", "Current Version"), detail = it.manifest.version.orEmpty()) }
                MBFormDivider(); MBFormRow(mb("版本", "Version"), detail = candidate.manifest.version.orEmpty())
                MBFormDivider(); MBFormRow(mb("宿主兼容版本", "Required Host Version"), detail = candidate.manifest.minAppVersion ?: "0.1.0")
            } }
            item { MBGroupedSection(mb("新版说明", "What's New")) {
                Text(candidate.releaseNotes ?: mb("此版本未提供更新说明", "No update notes were provided"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            } }
            item { MBGroupedSection { PluginNetworkPermissions(candidate.token.toString(), candidate.manifest.network) } }
            error?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
