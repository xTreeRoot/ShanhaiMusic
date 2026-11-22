package com.xtreeroot.mb.ui

import androidx.compose.ui.graphics.Color

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import com.xtreeroot.mb.ui.components.MBSwitch as Switch
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import com.xtreeroot.mb.ui.components.MBTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PendingPlugin
import com.xtreeroot.mb.plugin.PluginSetting
import com.xtreeroot.mb.plugin.PluginUpdateReport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginsView(model: PluginManagerModel, state: PluginManagerState, removed: (String) -> Unit, close: () -> Unit) {
    var displaySettings by remember { mutableStateOf<InstalledPlugin?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showZip by rememberSaveable { mutableStateOf(false) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    var repository by rememberSaveable { mutableStateOf("") }
    var showGitHub by rememberSaveable { mutableStateOf(false) }
    var batchImport by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<InstalledPlugin?>(null) }
    var localName by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<InstalledPlugin?>(null) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.prepareZip(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.prepareTree(uri)
    }
    LaunchedEffect(showZip) { if (showZip) { showZip = false; zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) } }
    LaunchedEffect(showFolder) { if (showFolder) { showFolder = false; folderPicker.launch(null) } }
    LaunchedEffect(state.requestedRepository, state.busy, state.check, state.pending, state.updateReport, state.importReport) {
        if (!state.busy && state.check == null && state.pending == null && state.updateReport == null && state.importReport == null) state.requestedRepository?.let { repo ->
            model.clearRepository(repo)
            model.prepareGitHub(repo)
        }
    }
    Scaffold(containerColor = if (LocalSkinEnabled.current) Color.Transparent else MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(state.installed.firstOrNull { it.id == selected }?.displayName.orEmpty()) }, navigationIcon = {
            TextButton(onClick = { if (selected != null) selected = null else close() }) { Text(mb("返回", "Back")) }
        }, actions = {
            if (selected == null) TextButton(onClick = { reordering = !reordering }, enabled = !state.busy) {
                Text(if (reordering) mb("完成", "Done") else mb("编辑", "Edit"))
            }
        })
    }) { padding ->
        if (selected != null) {
            val plugin = state.installed.firstOrNull { it.id == selected }
            if (plugin != null) PluginSettingsView(plugin, model, state, Modifier.padding(padding),
                { localName = plugin.localName.orEmpty(); rename = plugin }, { remove = it })
            else selected = null
        }
        if (selected == null) PluginsOverview(state, reordering, { selected = it.id },
            { localName = it.localName.orEmpty(); rename = it }, { remove = it }, model::move,
            { repository = ""; batchImport = true; showGitHub = true }, model::updateAll,
            { showZip = true }, { showFolder = true }, { repository = ""; batchImport = false; showGitHub = true }, Modifier.padding(padding), display = { displaySettings = it })
    }
    androidx.activity.compose.BackHandler(selected != null) { selected = null }

    displaySettings?.let { plugin ->
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.xtreeroot.mb.MBApplication
        DiscoverySectionsEditor(plugin.manifest, app.plugins) { displaySettings = null }
    }
    rename?.let { plugin -> AlertDialog(onDismissRequest = { rename = null }, title = { Text(mb("本地名称", "Local Name")) },
        text = { OutlinedTextField(localName, { localName = it }, singleLine = true, placeholder = { Text(plugin.manifest.name) }) },
        confirmButton = { TextButton(onClick = { model.rename(plugin, localName); rename = null }) { Text(mb("保存", "Save")) } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text(mb("取消", "Cancel")) } }) }
    val check = state.check
    if (check != null) PluginCheckDialog(check, model)
    else state.pending?.let { candidate -> PluginReviewDialog(candidate, state.busy && !state.batchReview, state.batchReview, model, state.error) }
    state.updateReport?.let { PluginUpdateResultDialog(it, imported = false, model::dismissUpdateReport) }
    state.importReview?.let { PluginImportReview(it, model) }
    state.importReport?.let { PluginUpdateResultDialog(it, imported = true, model::dismissImportReport) }
    if (showGitHub) AlertDialog(
        onDismissRequest = { showGitHub = false }, title = {
            Text(if (batchImport) mb("一键导入/覆盖", "Import / Overwrite") else mb("从 GitHub 安装", "Install from GitHub"))
        },
        text = { OutlinedTextField(repository, { repository = it }, label = {
            Text(if (batchImport) mb("GitHub 仓库或 Stars 列表地址", "GitHub repository or Stars list URL") else "owner/repository")
        }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            showGitHub = false
            if (batchImport) model.importGitHub(repository) else model.prepareGitHub(repository)
        }, enabled = repository.isNotBlank() && !state.busy) { Text(mb("检查", "Check")) } },
        dismissButton = { TextButton(onClick = { showGitHub = false }) { Text(mb("取消", "Cancel")) } },
    )
    remove?.let { plugin -> AlertDialog(
        onDismissRequest = { remove = null }, title = { Text(mb("删除插件？", "Delete plugin?")) },
        text = { Text(mb("删除 ${plugin.displayName} 的安装文件，保留本机插件设置及私有数据。",
            "Remove ${plugin.displayName} while keeping its local settings and private data.")) },
        confirmButton = { TextButton(onClick = { remove = null; selected = null; model.remove(plugin, removed) }, enabled = !state.busy) {
            Text(mb("删除", "Delete"))
        } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(mb("取消", "Cancel")) } },
    ) }
}

@Composable
private fun PluginUpdateResultDialog(report: PluginUpdateReport, imported: Boolean, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = {
        Text(if (imported) mb("插件导入结果", "Plugin Import Results") else mb("插件更新结果", "Plugin Update Results"))
    },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.available.isEmpty() && report.installed.isEmpty() && report.skipped.isEmpty() && report.failures.isEmpty() && report.unchanged.isEmpty()) {
                    item { Text(mb("没有可用更新", "No updates available")) }
                }
                items(report.available) { update -> Column { Text("${update.plugin.displayName} → ${update.release.revision.take(12)}"); update.release.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } }
                report.notes.forEach { (name, notes) -> item { Column { Text(name, style = MaterialTheme.typography.titleSmall); Text(notes, style = MaterialTheme.typography.bodyMedium) } } }
                items(report.installed) { name ->
                    Text(if (imported) mb("已导入：$name", "Imported: $name") else mb("已更新：$name", "Updated: $name"))
                }
                items(report.unchanged) { name -> Text(mb("已是最新：$name", "Up to date: $name")) }
                items(report.skipped) { name -> Text(mb("已跳过：$name", "Skipped: $name")) }
                items(report.failures) { failure ->
                    val detail = failure.detail ?: mb("未知错误", "Unknown error")
                    Text("${failure.pluginName}: $detail", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(mb("关闭", "Close")) } })
}

@Composable
private fun PluginImportReview(candidates: List<PendingPlugin>, model: PluginManagerModel) {
    AlertDialog(onDismissRequest = model::discard, title = { Text(mb("确认导入/覆盖", "Confirm Import / Overwrite")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(mb("覆盖插件代码时保留本机账号、设置和私有数据。", "Overwriting code keeps local accounts, settings, and private data.")) }
                items(candidates, key = { it.manifest.id }) { candidate ->
                    val manifest = candidate.manifest
                    val action = if (candidate.replaces != null) mb("覆盖", "Overwrite") else mb("安装", "Install")
                    Text(listOfNotNull(action, manifest.name, manifest.version).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    candidate.repository?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    candidate.replaces?.repository?.takeUnless { it.equals(candidate.repository, ignoreCase = true) }?.let { previous ->
                        Text(mb("原仓库：$previous", "Previous repository: $previous"), style = MaterialTheme.typography.bodySmall)
                    }
                    candidate.releaseNotes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    PluginNetworkPermissions(candidate.token.toString(), manifest.network)
                }
            }
        },
        confirmButton = { TextButton(onClick = model::install) { Text(mb("确认全部导入", "Import All")) } },
        dismissButton = { TextButton(onClick = model::discard) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun PluginCheckDialog(state: PluginCheck, model: PluginManagerModel) {
    val failure = state as? PluginCheck.Failed
    AlertDialog(onDismissRequest = model::dismissCheck,
        title = { Text(if (failure == null) mb("正在检查插件", "Checking Plugin") else mb("插件检查失败", "Plugin Check Failed")) },
        text = {
            if (failure == null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(26.dp))
                Text(mb("正在检查安装包与权限…", "Checking the package and permissions…"))
            } else Text(failure.message, color = MaterialTheme.colorScheme.error)
        },
        confirmButton = { if (failure != null) TextButton(onClick = model::retryCheck) { Text(mb("重试", "Retry")) } },
        dismissButton = { if (failure != null) TextButton(onClick = model::dismissCheck) { Text(mb("关闭", "Close")) } })
}

/** 网络权限按需展开，不挤占每个插件设置的首屏。 */
@Composable
internal fun PluginNetworkPermissions(identity: String, hosts: List<String>) {
    var expanded by remember(identity) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(mb("网络权限", "Network permissions"))
                Text(if (expanded) mb("收起", "Collapse") else mb("展开", "Expand"))
            }
        }
        if (expanded) {
            if (hosts.isEmpty()) Text(mb("无", "None"))
            hosts.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
