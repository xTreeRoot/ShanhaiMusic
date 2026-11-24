package com.xtreeroot.mb.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.edge.*
import com.xtreeroot.mb.ui.components.MBFormRow
import com.xtreeroot.mb.ui.components.MBGroupedSection
import kotlinx.coroutines.CancellationException
import com.xtreeroot.mb.plugin.PluginSetting
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import com.xtreeroot.mb.ui.components.MBAlertDialog

@Composable
internal fun DeviceSettingsSection(app: MBApplication, node: NodeBinding, names: Map<String,String>, accounts: Map<String,String>, openAccount: (String) -> Unit) {
    var devices by remember { mutableStateOf(emptyList<SettingsDevice>()) }
    var selected by remember { mutableStateOf<SettingsDevice?>(null) }
    var preview by remember { mutableStateOf<DeviceSettings?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("") }
    var pluginChoices by remember { mutableStateOf(emptyList<BackupPluginChoice>()) }
    var pluginsLoaded by remember { mutableStateOf(false) }
    var confirmPlugin by remember { mutableStateOf<BackupPluginChoice?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installed by app.installedPlugins.collectAsStateWithLifecycle()
    val failure = mb("设备设置操作失败，请刷新后重试", "Device settings failed. Refresh and retry.")
    val refreshFailed = mb("已备份本机设置 · 刷新失败", "Device settings saved · Refresh failed")
    var nodeError by remember { mutableStateOf<NodeFailure?>(null) }
    val saved = mb("本机已完整备份（含插件和插件信息）", "Device backed up, including plugins and their data")
    val fullyRestored = mb("已完整恢复到本机", "Device restored")
    val restored = mb("已恢复 %1\$d 项 · 跳过 %2\$d 项", "%1\$d restored · %2\$d skipped")
    fun run(job: suspend () -> Unit) {
        if (busy) return
        busy = true; result = null; nodeError = null
        scope.launch {
            try { job() } catch (e: CancellationException) { throw e } catch (e: NodeException) { nodeError = e.failure } catch (_: Exception) { result = failure }
            finally { busy = false }
        }
    }
    suspend fun backup() {
        NodeDeviceBackup(app, node).backup(accounts)
        selected = null; preview = null; result = saved
        try { devices = app.nodeClient.settingsDevices(node) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { result = refreshFailed }
    }
    fun refresh() = run { devices = app.nodeClient.settingsDevices(node) }
    LaunchedEffect(node) { run {
        devices = app.nodeClient.settingsDevices(node)
        deviceName = devices.firstOrNull { it.current }?.name.orEmpty()
    } }
    MBGroupedSection(mb("设备设置","Device settings")) {
        OutlinedTextField(deviceName, { deviceName = it }, enabled = !busy, singleLine = true, label = { Text(mb("本机名称", "Device name")) })
        MBFormRow(mb("保存名称", "Save name"), enabled = !busy && deviceName.isNotBlank(), onClick = { run {
            app.nodeClient.renameDevice(node, deviceName)
            devices = app.nodeClient.settingsDevices(node)
            deviceName = devices.firstOrNull { it.current }?.name ?: deviceName
            result = "设备名称已保存"
        } })
        MBFormRow(mb("备份本机","Back up this device"), enabled = !busy, onClick = { run {
            backup()
        } })
        MBFormRow(mb("刷新","Refresh"),enabled = !busy,onClick = ::refresh)
        MBFormRow(mb("从其他机器恢复", "Restore from another device"), disclosure = true, enabled = !busy, onClick = { restoring = !restoring })
        if (restoring && devices.none { !it.current && it.updated > 0 }) MBFormRow(mb("暂无其他机器的备份，请先在原机器备份本机", "No other device backup. Back up the source device first."))
        for (device in devices.filter { restoring && !it.current && it.updated > 0 }) {
            MBFormRow(device.name + " · " + device.key.takeLast(6) + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(device.updated * 1000)), disclosure = true, enabled = !busy, onClick = {
                if (selected == device) { selected = null; preview = null }
                else run { val data = app.nodeClient.readSettingsSnapshot(node,device); selected = device; preview = data; pluginChoices = emptyList(); pluginsLoaded = false; confirmPlugin = null }
            })
            val data = preview
            if (selected == device && data != null) {
                data.backup?.let { MBFormRow(mb("完整备份 · ${it.plugins} 个插件", "Complete backup · ${it.plugins} plugins")) }
                if (data.backup == null) MBFormRow(mb("旧备份：仅含设置", "Older backup: settings only"))
                if (data.backup != null) {
                    MBFormRow(mb("选择插件信息恢复", "Choose plugin information to restore"), enabled = !busy, onClick = { run {
                        pluginChoices = NodeDeviceBackup(app, node).pluginChoices(device, data)
                        pluginsLoaded = true
                    } })
                    if (pluginsLoaded && pluginChoices.isEmpty()) MBFormRow(mb("此备份中没有插件", "No plugins in this backup"))
                    for (plugin in pluginChoices) MBFormRow((names[plugin.id] ?: plugin.name) + mb(" · 恢复插件信息", " · Restore plugin information"), enabled = !busy, onClick = { confirmPlugin = plugin })
                }
                key(device.key) { SnapshotGroup(mb("App 设置","App settings"), data.app, null) }
                for ((id, items) in data.plugins.toSortedMap()) key(device.key, id) {
                    val fields = installed.find { it.id == id }?.manifest?.settings.orEmpty()
                    SnapshotGroup((names[id] ?: id) + mb(" · 插件设置", " · Plugin settings"), items, fields)
                }
                for ((id,name) in data.accounts.toSortedMap()) MBFormRow(names[id] ?: id, detail = name, disclosure = app.musicSource(id) != null,
                    onClick = if (app.musicSource(id) == null) null else { { openAccount(id) } })
                MBFormRow(if (data.backup != null) mb("恢复到本机", "Restore to this device") else mb("恢复这些设置","Restore these settings"), enabled = !busy, onClick = { run {
                    if (data.backup != null) {
                        NodeDeviceBackup(app, node).restore(device, data)
                        result = fullyRestored
                        return@run
                    }
                    var n = 0
                    for (plugin in app.plugins.installed()) {
                        val incoming = data.plugins[plugin.id]?.filter { (k,v) -> plugin.manifest.settings.any { it.key == k && it.portable(v) } }.orEmpty()
                        if (incoming.isNotEmpty()) {
                            if (app.settings.nodeBinding.value !== node) throw CancellationException()
                            app.plugins.saveSettings(plugin,app.plugins.settings(plugin)+incoming); n += incoming.size
                        }
                    }
                    app.refreshPlugins()
                    if (app.settings.nodeBinding.value !== node) throw CancellationException()
                    app.settings.update { val (prefs,added) = it.restoreSettings(data.app); n += added; prefs }
                    result = restored.format(n, data.app.size + data.plugins.values.sumOf { it.size } - n)
                } })
            }
        }
        if (busy) MBFormRow(mb("处理中…","Working…"))
        nodeError?.let { MBFormRow(profileFailure(it)) }
        result?.let { MBFormRow(it) }
    }
    confirmPlugin?.let { plugin ->
        val device = selected
        val data = preview
        MBAlertDialog(onDismissRequest = { confirmPlugin = null }, title = { Text(mb("覆盖所选插件的信息", "Replace selected plugin information")) },
            text = { Text(mb("将用“${device?.name.orEmpty()}”的“${names[plugin.id] ?: plugin.name}”设置、登录信息和私有数据覆盖本机该插件。", "Replace this plugin's settings, sign-in information and private data from ${device?.name.orEmpty()}.")) },
            confirmButton = { TextButton(onClick = {
                confirmPlugin = null
                if (device != null && data != null) run {
                    NodeDeviceBackup(app, node).restorePlugin(device, data, plugin.id)
                    result = "已覆盖所选插件的信息"
                }
            }) { Text(mb("覆盖此插件的信息", "Replace this plugin's information")) } },
            dismissButton = { TextButton(onClick = { confirmPlugin = null }) { Text(mb("取消", "Cancel")) } })
    }
}


private val fieldNames = mapOf(
    "appearance" to ("外观" to "Appearance"),
    "language" to ("语言" to "Language"),
    "quality" to ("音质" to "Quality"),
    "smoke" to ("进度条烟雾" to "Smoke on the Progress Bar"),
    "fog" to ("烟雾背景" to "Fog Backdrop"),
    "growFromCover" to ("从封面展开" to "Grow From the Cover"),
    "lyricsStage" to ("舞台歌词" to "Stage Lyrics"),
    "hotComments" to ("间奏热评" to "Top Comments in Quiet Parts"),
    "stageSparks" to ("歌词火花" to "Sparks"),
    "nowPlayingLyrics" to ("系统播放器歌词" to "Now Playing lyrics"),
    "pauseOtherSourcesOnSwitch" to ("切换音源时暂停" to "Pause when switching sources"),
    "inAppVideoWindow" to ("应用内视频窗口" to "In-app video window"),
    "systemVideoWindow" to ("系统视频窗口" to "System video window"),
    "automaticUpdateChecks" to ("自动检查更新" to "Automatic update checks"),
)
private val valueNames = mapOf(
    "true" to ("开启" to "Turn On"),
    "false" to ("关闭" to "Off"),
    "system" to ("跟随系统" to "System"),
    "dark" to ("深色" to "Dark"),
    "light" to ("浅色" to "Light"),
    "high" to ("高" to "High"),
    "lossless" to ("无损" to "Lossless"),
    "standard" to ("标准" to "Standard"),
    "zh-CN" to ("简体中文" to "简体中文"),
    "en" to ("English" to "English"),
)

@Composable
private fun SnapshotGroup(title: String, values: Map<String, String>, fields: List<PluginSetting>?) {
    var expanded by remember { mutableStateOf(false) }
    MBFormRow(title, detail = mb("${values.size} 项", "${values.size} settings"), disclosure = true, onClick = { expanded = !expanded })
    if (expanded) for ((key, value) in values.toSortedMap()) {
        val field = fields?.find { it.key == key }
        val name = if (fields == null) fieldNames[key] else null
        val translated = if (fields == null || field?.type == "boolean") valueNames[value] else null
        val label = field?.label ?: name?.let { mb(it.first, it.second) } ?: key
        val displayed = field?.options?.find { it.value == value }?.label ?: translated?.let { mb(it.first, it.second) } ?: value
        MBFormRow(label, detail = displayed)
    }
}
