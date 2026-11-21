package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PluginSetting
import com.xtreeroot.mb.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 与 iOS 插件详情一致的名称、来源、设置、权限和保存/移除分组。 */
@Composable
internal fun PluginSettingsView(plugin: InstalledPlugin, model: PluginManagerModel, state: PluginManagerState,
                                modifier: Modifier, rename: () -> Unit, remove: (InstalledPlugin) -> Unit) {
    var values by remember(plugin.id, plugin.revision) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember(plugin.id, plugin.revision) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val urls = LocalUriHandler.current
    LaunchedEffect(plugin.id, plugin.revision) {
        try { values = model.settings(plugin); loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadingError = error.message }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { MBGroupedSection { MBFormRow(mb("本地名称", "Local Name"), detail = plugin.displayName, disclosure = true, onClick = rename) } }
        item {
            MBGroupedSection(mb("来源", "Source")) {
                plugin.repository?.let { repository ->
                    MBFormRow("https://github.com/$repository", disclosure = true, onClick = { urls.openUri("https://github.com/$repository") })
                } ?: MBFormRow(mb("安装来源", "Install Source"), detail = mb("本地文件", "Local File"))
                MBFormDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(mb("安装位置", "Install Location")); Text(plugin.directory.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        plugin.manifest.settings.forEach { setting -> item(key = setting.key) {
            MBGroupedSection(footer = setting.help) {
                val value = values[setting.key].orEmpty()
                val change: (String) -> Unit = { values = values + (setting.key to it); saved = false }
                when (setting.type) {
                    "boolean" -> MBFormRow(setting.label, trailing = { MBSwitch(value == "true", { change(it.toString()) }) })
                    "select" -> PluginSelectSetting(setting, value, change)
                    else -> MBTextField(value, change, Modifier.fillMaxWidth().padding(12.dp), label = { Text(setting.label) },
                        visualTransformation = if (setting.type == "password") PasswordVisualTransformation() else VisualTransformation.None, singleLine = true)
                }
                setting.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { address ->
                    MBFormRow(mb("查看说明", "View Guide"), disclosure = true, onClick = { urls.openUri(address) })
                }
            }
        } }
        item { MBGroupedSection {
            MBFormRow(mb("启用音源", "Enable Source"), trailing = { MBSwitch(plugin.enabled, { model.enable(plugin, it) }, enabled = !state.busy) })
            if (plugin.repository != null) { MBFormDivider(); MBFormRow(mb("更新插件", "Update Plugin"), enabled = !state.busy, onClick = { model.update(plugin) }) }
        } }
        item { MBGroupedSection { PluginNetworkPermissions(plugin.id, plugin.manifest.network) } }
        item { MBGroupedSection {
            MBFormRow(mb("保存", "Save"), enabled = loaded && !saving && !state.busy, onClick = {
                saving = true; saved = false
                scope.launch { try { model.save(plugin, values) { saved = true } } finally { saving = false } }
            })
            if (saved) Text(mb("已保存", "Saved"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saving) CircularProgressIndicator(Modifier.padding(16.dp))
            (loadingError ?: state.error)?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        } }
        item { MBGroupedSection {
            MBFormRow(mb("删除插件", "Delete Plugin"), enabled = !state.busy, color = MaterialTheme.colorScheme.error, onClick = { remove(plugin) })
        } }
    }
}

@Composable
private fun PluginSelectSetting(setting: PluginSetting, value: String, changed: (String) -> Unit) {
    var expanded by remember(setting.key) { mutableStateOf(false) }
    Box {
        MBFormRow(setting.label, detail = setting.options.firstOrNull { it.value == value }?.label ?: mb("未设置", "Not Set"), disclosure = true, onClick = { expanded = true })
        MBDropdownMenu(expanded, { expanded = false }) {
            MBMenuItem({ Text(mb("未设置", "Not Set")) }, { changed(""); expanded = false })
            setting.options.forEach { option -> MBMenuItem({ Text(option.label) }, { changed(option.value); expanded = false }) }
        }
    }
}
