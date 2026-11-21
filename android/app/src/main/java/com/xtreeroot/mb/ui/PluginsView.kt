package com.xtreeroot.mb.ui

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
    Scaffold(topBar = {
        TopAppBar(largeTitle = true, title = { Text(mb("插件", "Plugins")) }, actions = {
            TextButton(onClick = close) { Text(mb("完成", "Done")) }
        })
    }) { padding ->
        if (selected != null) {
            val plugin = state.installed.firstOrNull { it.id == selected }
            if (plugin != null) PluginSettingsView(plugin, model, state, Modifier.padding(padding), { selected = null }, { remove = it })
            else selected = null
        }
        if (selected == null) LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.installed.isNotEmpty()) item {
                val canUpdate = !state.busy && state.installed.any { it.repository != null }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = model::updateAll, enabled = canUpdate) { Text(mb("一键全部更新", "Update All")) }
                    TextButton(onClick = { reordering = !reordering }, enabled = !state.busy) {
                        Text(if (reordering) mb("完成排序", "Done Sorting") else mb("排序", "Sort"))
                    }
                }
            }
            items(state.installed, key = InstalledPlugin::id) { plugin ->
                ListItem(headlineContent = { Text(plugin.displayName) },
                    supportingContent = {
                        val status = if (plugin.enabled) mb("已启用", "Enabled") else mb("已停用", "Disabled")
                        Column {
                            Text(listOfNotNull(plugin.manifest.version, status).joinToString(" · "))

                        }
                    },
                    trailingContent = {
                        Row {
                            if (reordering) {
                                val index = state.installed.indexOfFirst { it.id == plugin.id }
                                TextButton(onClick = { model.move(plugin, -1) }, enabled = !state.busy && index > 0) { Text(mb("上移", "Up")) }
                                TextButton(onClick = { model.move(plugin, 1) }, enabled = !state.busy && index < state.installed.lastIndex) { Text(mb("下移", "Down")) }
                            } else TextButton(onClick = { localName = plugin.localName.orEmpty(); rename = plugin }, enabled = !state.busy) { Text(mb("重命名", "Rename")) }
                        }
                    },
                    modifier = Modifier.clickable { selected = plugin.id })
            }
            if (state.installed.isEmpty() && !state.busy) item { Text(mb("没有已安装的插件", "No installed plugins"), Modifier.padding(24.dp)) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showZip = true }, enabled = !state.busy) { Text(mb("从 ZIP 安装", "Install ZIP")) }
                    TextButton(onClick = { showFolder = true }, enabled = !state.busy) { Text(mb("从文件夹安装", "Install Folder")) }
                    TextButton(onClick = { repository = ""; batchImport = false; showGitHub = true }, enabled = !state.busy) { Text(mb("GitHub", "GitHub")) }
                }
            }
            item {
                TextButton(onClick = { repository = ""; batchImport = true; showGitHub = true }, enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text(mb("一键导入/覆盖", "Import / Overwrite")) }
            }
            if (state.busy && state.check == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            state.error?.let { item { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) } }
        }
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
                items(report.available) { update -> Text("${update.plugin.displayName} → ${update.release.revision.take(12)}") }
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
                    Text(mb("网络权限", "Network permissions"), style = MaterialTheme.typography.labelMedium)
                    Text(manifest.network.joinToString(", ").ifEmpty { mb("无", "None") }, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun PluginReviewDialog(candidate: PendingPlugin, busy: Boolean, batch: Boolean, model: PluginManagerModel, error: String?) {
    AlertDialog(
        onDismissRequest = { if (!busy) model.discard() }, title = { Text(mb("检查插件", "Review plugin")) },
        text = {
            LazyColumn {
                item { ReviewField(mb("名称", "Name"), candidate.manifest.name) }
                item { ReviewField(mb("版本", "Version"), candidate.manifest.version.orEmpty()) }
                item { ReviewField(mb("宿主兼容版本", "Required host version"), candidate.manifest.minAppVersion.orEmpty()) }
                item { Text(mb("网络权限", "Network permissions"), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
                if (candidate.manifest.network.isEmpty()) item { Text(mb("无", "None"), Modifier.padding(vertical = 4.dp)) }
                items(candidate.manifest.network) { host -> Text(host, Modifier.padding(vertical = 2.dp), fontFamily = FontFamily.Monospace) }
                if (candidate.manifest.settings.isNotEmpty()) item { Text(mb("设置项：${candidate.manifest.settings.size}", "Settings: ${candidate.manifest.settings.size}"), Modifier.padding(top = 12.dp)) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = model::install, enabled = !busy) {
            Text(if (batch) mb("允许并更新", "Allow and Update") else mb("安装", "Install"))
        } },
        dismissButton = { TextButton(onClick = model::discard, enabled = !busy) {
            Text(if (batch) mb("跳过", "Skip") else mb("取消", "Cancel"))
        } },
    )
}

@Composable
private fun ReviewField(name: String, value: String) {
    if (value.isNotEmpty()) ListItem(headlineContent = { Text(name) }, supportingContent = { Text(value) })
}

@Composable
private fun PluginSettingsView(plugin: InstalledPlugin, model: PluginManagerModel, state: PluginManagerState, modifier: Modifier,
                               back: () -> Unit, remove: (InstalledPlugin) -> Unit) {
    val values = remember(plugin.id, plugin.revision) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val changed = remember(plugin.id, plugin.revision) { mutableStateOf(false) }
    var loaded by remember(plugin.id, plugin.revision) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val loadError = mb("无法读取插件设置", "Could not read plugin settings")
    LaunchedEffect(plugin.id, plugin.revision) {
        try { values.value = model.settings(plugin); loaded = true }
        catch (_: Exception) { loadingError = loadError }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(plugin.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item { Text(mb("安装位置", "Install location")); Text(plugin.directory.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(mb("启用音源", "Enable source")); Switch(plugin.enabled, { model.enable(plugin, it) }, enabled = !state.busy)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plugin.repository != null) TextButton(onClick = { model.update(plugin) }, enabled = !state.busy) { Text(mb("更新插件", "Update Plugin")) }
                TextButton(onClick = { remove(plugin) }, enabled = !state.busy) { Text(mb("删除", "Delete")) }
            }
        }
        item { PluginNetworkPermissions(plugin.id, plugin.manifest.network) }
        items(plugin.manifest.settings, key = PluginSetting::key) { setting ->
            val value = values.value[setting.key].orEmpty()
            when (setting.type) {
                "boolean" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(setting.label, Modifier.weight(1f)); Switch(value == "true", {
                        values.value = values.value + (setting.key to it.toString()); changed.value = true; saved = false
                    })
                }
                "select" -> PluginSelectSetting(setting, value) {
                    values.value = values.value + (setting.key to it); changed.value = true; saved = false
                }
                else -> OutlinedTextField(value, {
                    values.value = values.value + (setting.key to it); changed.value = true; saved = false
                }, Modifier.fillMaxWidth(), label = { Text(setting.label) },
                    visualTransformation = if (setting.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                    supportingText = setting.help?.let { text -> { Text(text) } }, singleLine = true)
            }
        }
        item {
            TextButton(onClick = {
                saving = true
                scope.launch {
                    model.save(plugin, values.value) { saved = true; changed.value = false }
                    saving = false
                }
            }, enabled = loaded && changed.value && !saving && !state.busy) {
                Text(if (saving) mb("保存中…", "Saving…") else mb("保存设置", "Save settings"))
            }
            if (saved) Text(mb("已保存", "Saved"), style = MaterialTheme.typography.bodySmall)
            loadingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item { TextButton(onClick = back) { Text(mb("返回", "Back")) } }
    }
}

@Composable
private fun PluginSelectSetting(setting: PluginSetting, value: String, changed: (String) -> Unit) {
    var expanded by remember(setting.key) { mutableStateOf(false) }
    Column {
        ListItem(headlineContent = { Text(setting.label) }, supportingContent = {
            Text(setting.options.firstOrNull { it.value == value }?.label ?: mb("未设置", "Not set"))
        }, modifier = Modifier.clickable { expanded = true })
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
            setting.options.forEach { option -> androidx.compose.material3.DropdownMenuItem(
                text = { Text(option.label) }, onClick = { changed(option.value); expanded = false }) }
        }
    }
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
