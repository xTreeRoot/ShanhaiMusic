package com.xtreeroot.mb.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.edge.syncProfile
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.edge.CloudRecord
import com.xtreeroot.mb.edge.NodeException
import com.xtreeroot.mb.edge.NodeFailure
import com.xtreeroot.mb.edge.nodeOrigin
import com.xtreeroot.mb.ui.components.MBTextButton
import com.xtreeroot.mb.ui.components.MBFormDivider
import com.xtreeroot.mb.ui.components.MBFormRow
import com.xtreeroot.mb.ui.components.MBGroupedSection
import com.xtreeroot.mb.ui.components.MBTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 复用应用分组组件，实现标签、输入左右排列的节点接入表单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeSettingsView(app: MBApplication, sourceNames: Map<String, String>, surface: Color, accounts: Map<String, String> = emptyMap(), openAccount: (String) -> Unit = {}, close: () -> Unit) {
    val binding by app.settings.nodeBinding.collectAsStateWithLifecycle()
    val valid = binding?.let { it.expiresAt > System.currentTimeMillis() / 1_000 } == true
    var address by remember { mutableStateOf(binding?.origin?.toString().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<NodeFailure?>(null) }
    var invalidAddress by remember { mutableStateOf(false) }
    var expanded by remember(binding) { mutableStateOf<String?>(null) }
    var publishing by remember(binding) { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var records by remember(binding) { mutableStateOf<List<CloudRecord>?>(null) }
    var overviewError by remember(binding) { mutableStateOf<NodeFailure?>(null) }
    var refreshing by remember(binding) { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val busy = loading || publishing
    LaunchedEffect(binding, refreshVersion) {
        val target = binding ?: return@LaunchedEffect
        if (!valid) return@LaunchedEffect
        refreshing = true; overviewError = null
        try { records = app.nodeClient.overview(target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: NodeException) { records = null; overviewError = failure.failure }
        finally { refreshing = false }
    }
    BackHandler(onBack = close)
    val connect: () -> Unit = connect@{
        if (busy || password.isEmpty()) return@connect
        val origin = runCatching { nodeOrigin(address) }.getOrElse { invalidAddress = true; return@connect }
        loading = true
        error = null
        val entered = password
        password = ""
        scope.launch {
            try {
                val joined = app.nodeClient.join(origin, entered, Build.MODEL.take(64))
                ensureActive()
                val previous = app.settings.nodeBinding.value
                if (previous?.origin == origin && previous.catalogPublicKey != joined.catalogPublicKey) {
                    throw NodeException(NodeFailure.KeyChanged)
                }
                app.settings.setNodeBinding(joined)
                address = origin.toString()
                feedback = "connected"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: NodeException) { error = failure.failure }
            finally { loading = false }
        }
    }
    Scaffold(containerColor = surface, topBar = {
        MBTopAppBar(title = {}, actions = {
            TextButton(onClick = close) { Text(mb("完成", "Done")) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            if (binding != null) MBGroupedSection() {
                MBFormRow(if (valid) mb("已接入", "Connected") else mb("授权已过期", "Authorization expired"), detail = binding?.origin?.host)
                MBFormDivider()
                MBFormRow(mb("解除此设备绑定", "Disconnect This Device"), enabled = !busy, color = MaterialTheme.colorScheme.error,
                    onClick = { app.settings.setNodeBinding(null); password = ""; error = null; feedback = "unlinked" })
            }
            if (valid) {
                binding?.let { node -> key(node) { DeviceSettingsSection(app, node, sourceNames, accounts, openAccount) } }
                fun stateOf(id: String, key: String): Boolean? = records?.any { it.platform == id && it.key == key && it.kind == "data" }
                MBGroupedSection(mb("应用偏好", "App preferences")) {
                    MBFormRow(mb("插件偏好", "Plugin preferences"), detail = cloudStored(stateOf("app", "plugins")))
                    MBFormDivider()
                    MBFormRow(mb("发现栏目偏好", "Discovery preferences"), detail = cloudStored(stateOf("app", "discovery")))
                    MBFormDivider()
                    records.orEmpty().filter { it.platform == "app" && it.kind == "data" && it.key !in setOf("plugins", "discovery") }.forEach {
                        MBFormRow(it.key.orEmpty(), detail = cloudStored(true))
                        MBFormDivider()
                    }
                    MBFormRow(if (refreshing) mb("正在查询…", "Checking…") else mb("刷新", "Refresh"),
                        enabled = !refreshing && !busy, onClick = { refreshVersion++ })
                    overviewError?.let { MBFormRow(profileFailure(it)) }
                }
                val ids = (sourceNames.keys + records.orEmpty().map { it.platform }.filter { it != "app" })
                    .sortedWith(compareBy({ sourceNames[it] ?: it }, { it }))
                MBGroupedSection {
                    ids.forEach { id -> key(id) {
                        val count = records?.filter { it.platform == id }?.sumOf { it.count }
                        MBFormRow(sourceNames[id] ?: id, detail = count?.let { mb("已存 $it 项", "$it stored") },
                            disclosure = true, enabled = !busy,
                            onClick = { expanded = if (expanded == id) null else id })
                        if (expanded == id) {
                            val source = app.musicSource(id) as? PluginSource
                            val targetBinding = binding
                            var supported by remember(source) { mutableStateOf(false) }
                            var result by remember(source) { mutableStateOf<String?>(null) }
                            var failure by remember(source) { mutableStateOf<NodeFailure?>(null) }
                            LaunchedEffect(source) {
                                try { supported = source?.supportsAccount() == true }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failure = NodeFailure.Rejected }
                            }
                            val success = mb("资料已同步", "Profile synced")
                            val signedOut = mb("请先在本机登录", "Sign in on this device first")
                            MBFormRow(mb("账号资料", "Account profile"), detail = cloudStored(stateOf(id, "profile")), trailing = {
                                if (supported && source != null && targetBinding != null) TextButton(enabled = !busy, onClick = {
                                    if (publishing) return@TextButton
                                    publishing = true; failure = null; result = null
                                    scope.launch {
                                        try {
                                            val target = NodeProfileTarget.verified(targetBinding, id, source.account())
                                            val revision = source.mediaRevision
                                            fun current() = app.settings.nodeBinding.value == targetBinding && app.musicSource(id) === source && source.mediaRevision == revision
                                            if (target == null) { result = signedOut }
                                            else if (current()) {
                                                val profile = app.nodeClient.syncProfile(targetBinding, id, target.profile, true, ::current)
                                                ensureActive()
                                                if (current()) { app.settings.cacheNodeProfile(targetBinding, id, profile); result = success; refreshVersion++ }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (e: NodeException) { failure = e.failure }
                                        catch (_: Exception) { failure = NodeFailure.Rejected }
                                        finally { publishing = false }
                                    }
                                }) { Text(if (publishing) mb("同步中", "Syncing") else mb("同步资料", "Sync profile")) }
                            })
                            val count = records?.firstOrNull { it.kind == "sessions" && it.platform == id }?.count ?: 0
                            MBFormRow(mb("登录会话", "Sign-in sessions"), detail = if (count > 0) mb("$count 份", "$count stored") else cloudStored(records?.let { false }))
                            records.orEmpty().filter { it.platform == id && it.kind == "data" && it.key != "profile" }.forEach {
                                MBFormRow(it.key.orEmpty(), detail = cloudStored(true))
                            }
                            result?.let { MBFormRow(it) }
                            failure?.let { MBFormRow(profileFailure(it)) }
                        }
                        MBFormDivider()
                    } }
                }
            }
            if (!valid) {
                MBGroupedSection {
                    NodeInput(mb("节点地址", "Address"), mb("域名或 HTTPS 地址", "Domain or HTTPS address"), address,
                        { address = it; invalidAddress = false; error = null }, busy, keyboard = KeyboardType.Uri) {
                        if (address.isNotEmpty()) IconButton(onClick = { address = ""; invalidAddress = false }, enabled = !busy) {
                            Icon(Icons.Default.Close, mb("清空节点地址", "Clear address"), Modifier.size(20.dp))
                        }
                    }
                    if (invalidAddress) Text(mb("请输入不带路径的 HTTPS 节点地址。", "Enter an HTTPS node address without a path."),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
                    MBFormDivider()
                    NodeInput(mb("节点密码", "Password"), mb("请输入密码", "Enter password"), password,
                        { password = it; error = null }, busy, if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), KeyboardType.Password) {
                        TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                            Text(if (passwordVisible) mb("隐藏", "Hide") else mb("显示", "Show"))
                        }
                    }
                }
                MBTextButton(onClick = connect, enabled = !busy && address.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (loading) mb("正在接入…", "Connecting…") else if (binding != null) mb("重新接入", "Reconnect") else mb("接入节点", "Connect to Node"))
                }
            }
            error?.let { Text(nodeJoinFailure(it), Modifier.padding(horizontal = 32.dp), color = MaterialTheme.colorScheme.error) }
            feedback?.let { Text(if (it == "connected") mb("已接入节点", "Connected")
                else mb("已解除绑定", "Disconnected"),
                Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
private fun cloudStored(stored: Boolean?): String = when (stored) {
    true -> mb("已存云端", "Stored in cloud")
    false -> mb("未存云端", "Not stored")
    null -> mb("未查询", "Not checked")
}

@Composable
private fun NodeInput(label: String, placeholder: String, value: String, change: (String) -> Unit, busy: Boolean,
                       visual: VisualTransformation = VisualTransformation.None, keyboard: KeyboardType,
                       trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyLarge)
        BasicTextField(value, change, Modifier.weight(1f).padding(vertical = 14.dp).semantics { contentDescription = label },
            enabled = !busy, singleLine = true, visualTransformation = visual, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { input ->
                Box { if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge); input() }
            })
        trailing()
    }
}

@Composable
private fun nodeJoinFailure(failure: NodeFailure): String = when (failure) {
    NodeFailure.BadPassword -> mb("节点密码错误", "Wrong node password")
    NodeFailure.KeyChanged -> mb("节点密钥已变化，请核实地址后解除旧绑定", "Node key changed; verify the address before disconnecting")
    NodeFailure.Unsupported -> mb("节点尚未提供接入接口，请更新服务器", "This node needs a server update to support connecting")
    else -> profileFailure(failure)
}

@Composable
internal fun profileFailure(failure: NodeFailure): String = when (failure) {
    NodeFailure.OtherAccount -> mb("云端资料属于另一个账号，未覆盖", "The profile belongs to another account; nothing was overwritten")
    NodeFailure.Deleted -> mb("云端资料已删除，未重新创建", "The profile was deleted; it was not recreated")
    NodeFailure.Conflict -> mb("云端资料已变更，未覆盖", "The profile changed; nothing was overwritten")
    NodeFailure.Unauthorized -> mb("授权已失效，请重新接入", "Authorization expired; reconnect")
    NodeFailure.Retryable -> mb("节点暂不可达，请稍后重试", "Node temporarily unavailable; try again later")
    NodeFailure.Unsupported -> mb("节点尚未提供资料同步接口，请更新服务器", "This node needs a server update to support profile sync")
    NodeFailure.Rejected -> mb("节点拒绝同步请求，未覆盖本地资料", "The node rejected sync; local data was not overwritten")
    else -> mb("资料校验失败，未覆盖本地资料", "Profile validation failed; local data was not overwritten")
}
