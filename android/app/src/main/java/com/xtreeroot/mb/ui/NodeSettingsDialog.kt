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
internal fun NodeSettingsView(app: MBApplication, profileSync: NodeProfileSyncState, canPublish: Boolean,
                              publish: () -> Unit, surface: Color, sourceId: String? = null, sourceName: String? = null,
                              localName: String? = null, canSyncSignIn: Boolean = false, nodeSignIn: NodeSignInState? = null,
                              signInChecked: Boolean = false,
                              syncSignIn: (Boolean) -> Unit = {}, close: () -> Unit) {
    val binding by app.settings.nodeBinding.collectAsStateWithLifecycle()
    val valid = binding?.let { it.expiresAt > System.currentTimeMillis() / 1_000 } == true
    var address by remember { mutableStateOf(binding?.origin?.toString().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<NodeFailure?>(null) }
    var invalidAddress by remember { mutableStateOf(false) }
    var confirmingUnlink by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var records by remember(binding) { mutableStateOf<List<CloudRecord>?>(null) }
    var overviewError by remember(binding) { mutableStateOf<NodeFailure?>(null) }
    var refreshing by remember(binding) { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val busy = loading || profileSync == NodeProfileSyncState.Publishing || nodeSignIn?.busy == true
    LaunchedEffect(binding, refreshVersion, profileSync, nodeSignIn?.result) {
        val target = binding ?: return@LaunchedEffect
        if (!valid || busy) return@LaunchedEffect
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
        MBTopAppBar(title = { Text(mb("山海服务", "Shanhai Service")) }, actions = {
            TextButton(onClick = close) { Text(mb("完成", "Done")) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            if (binding != null) MBGroupedSection(mb("当前节点", "Current Node")) {
                MBFormRow(if (valid) mb("已接入", "Connected") else mb("授权已过期", "Authorization expired"), detail = binding?.origin?.host)
                MBFormDivider()
                MBFormRow(mb("解除此设备绑定", "Disconnect This Device"), enabled = !busy, color = MaterialTheme.colorScheme.error,
                    onClick = { confirmingUnlink = true })
            }
            if (valid) MBGroupedSection(mb("云端存储", "Cloud Storage")) {
                records?.let { inventory ->
                    fun stored(platform: String, key: String) = inventory.any { it.kind == "data" && it.platform == platform && it.key == key }
                    MBFormRow(mb("插件偏好", "Plugin Preferences"), detail = cloudStored(stored("app", "plugins")))
                    MBFormDivider()
                    MBFormRow(mb("发现栏目偏好", "Discovery Preferences"), detail = cloudStored(stored("app", "discovery")))
                    inventory.filter { it.kind == "sessions" && it.platform != sourceId }.forEach { record ->
                        MBFormDivider()
                        MBFormRow(record.platform, detail = mb("登录会话 ${record.count} 份", "${record.count} sign-in sessions"))
                    }
                    inventory.filter { it.key == "profile" && it.platform != sourceId }.forEach { record ->
                        MBFormDivider()
                        MBFormRow(record.platform, detail = mb("已存账号资料", "Account profile stored"))
                    }
                    MBFormDivider()
                }
                overviewError?.let { MBFormRow(if (it == NodeFailure.Unsupported) mb("节点需更新后才能显示存储清单", "Update the node to see stored records") else profileFailure(it)) }
                MBFormRow(if (refreshing) mb("正在查询云端记录…", "Checking cloud records…") else mb("刷新云端状态", "Refresh Cloud Status"),
                    enabled = !refreshing && !busy, onClick = { refreshVersion++ })
            }
            if (valid && sourceId != null && sourceName != null) MBGroupedSection(mb("当前音源：$sourceName", "Current Source: $sourceName")) {
                MBFormRow(mb("本机登录", "Local Sign-in"), detail = localName ?:
                    if (signInChecked) mb("未登录", "Signed out") else mb("尚未确认", "Not checked"))
                records?.let { inventory ->
                    MBFormDivider()
                    MBFormRow(mb("账号资料（仅 ID 和昵称）", "Account Profile (ID and Name)"),
                        detail = cloudStored(inventory.any { it.platform == sourceId && it.key == "profile" }))
                    MBFormDivider()
                    val count = inventory.firstOrNull { it.platform == sourceId && it.kind == "sessions" }?.count ?: 0
                    MBFormRow(mb("云端登录", "Cloud Sign-in"), detail = if (count == 0) cloudStored(false) else mb("$count 份 · 有效性未验证", "$count · validity not verified"))
                }
                MBFormDivider()
                if (canSyncSignIn) MBFormRow(if (localName != null) mb("同步登录到云端", "Sync Sign-in to Cloud") else mb("从云端恢复登录", "Restore Sign-in from Cloud"),
                    enabled = !busy && signInChecked, onClick = { syncSignIn(localName != null) })
                else MBFormRow(mb("当前插件不能同步登录", "Sign-in sync unavailable"))
                MBFormDivider()
                MBFormRow(mb("只同步账号资料", "Sync Account Profile Only"), enabled = !busy && canPublish, onClick = publish)
                nodeSignIn?.let { MBFormRow(nodeSignInFeedback(it)) }
            }
            MBGroupedSection(mb("接入节点", "Connect to Node"),
                mb("只接入可信节点；节点管理员能够解密同步数据。", "Use a trusted node; its administrator can decrypt synced data.")) {
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
            error?.let { Text(nodeJoinFailure(it), Modifier.padding(horizontal = 32.dp), color = MaterialTheme.colorScheme.error) }
            feedback?.let { Text(if (it == "connected") mb("已接入节点，本机可以同步。", "Node connected. This device can sync.")
                else mb("已解除绑定，现有登录和云端记录未变更。", "Device disconnected. Existing sign-ins and cloud records were not changed."),
                Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
            val result = when (profileSync) {
                NodeProfileSyncState.Idle -> null
                NodeProfileSyncState.Publishing -> mb("正在同步…", "Syncing…")
                NodeProfileSyncState.Published -> mb("账号资料已同步，未上传登录会话。", "Account profile synced. No sign-in session was sent.")
                is NodeProfileSyncState.Failed -> profileFailure(profileSync.failure)
            }
            if (binding != null && result != null) Text(result, Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                color = if (profileSync is NodeProfileSyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmingUnlink) AlertDialog(onDismissRequest = { confirmingUnlink = false },
        title = { Text(mb("解除此设备绑定？", "Disconnect this device?")) },
        text = { Text(mb("不会删除本机登录或云端记录。", "Local sign-ins and cloud records will not be deleted.")) },
        confirmButton = { TextButton(onClick = {
            app.settings.setNodeBinding(null); confirmingUnlink = false; password = ""; error = null; feedback = "unlinked"
        }) { Text(mb("确认解绑", "Disconnect")) } },
        dismissButton = { TextButton(onClick = { confirmingUnlink = false }) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun cloudStored(stored: Boolean): String = if (stored) mb("已存云端", "Stored in Cloud") else mb("未存云端", "Not Stored")

@Composable
private fun nodeSignInFeedback(state: NodeSignInState): String = when (state.result) {
    NodeSignInResult.Uploading -> mb("正在同步登录会话…", "Syncing sign-in session…")
    NodeSignInResult.Restoring -> mb("正在查找并验证云端登录…", "Finding and verifying cloud sign-in…")
    NodeSignInResult.Uploaded -> mb("登录会话已存到节点。", "Sign-in session stored on the node.")
    NodeSignInResult.Restored -> mb("已从云端恢复登录。", "Signed in from the cloud.")
    NodeSignInResult.Missing -> mb("云端没有可恢复的登录会话。", "No cloud sign-in session is available.")
    NodeSignInResult.Multiple -> mb("云端有多个账号，没自动选择。请在本机登录要用的账号。", "Multiple cloud accounts. Sign in on this device to choose one.")
    NodeSignInResult.Blocked -> mb("本机已主动退出，请先在本机登录。", "You signed out here. Sign in on this device first.")
    NodeSignInResult.Unverified -> mb("平台未确认此会话，请在本机重新登录。", "The service did not verify the session. Sign in again here.")
    NodeSignInResult.Failed -> when (state.failure) {
        NodeFailure.Deleted -> mb("云端会话已删除，没有重新创建。", "The deleted cloud session was not recreated.")
        NodeFailure.Conflict -> mb("云端会话已变化，请刷新再试。", "The cloud session changed. Refresh and try again.")
        NodeFailure.Unsupported -> mb("节点尚不支持登录同步，请升级节点。", "Update the node to use sign-in sync.")
        NodeFailure.UntrustedPlugin -> mb("请先安装与该节点签名目录一致的插件版本，再同步登录。", "Install the plugin version verified by this node before syncing sign-ins.")
        NodeFailure.Unauthorized -> profileFailure(NodeFailure.Unauthorized)
        NodeFailure.Retryable -> profileFailure(NodeFailure.Retryable)
        else -> mb("登录同步失败，本机账号未切换。", "Sign-in sync failed. The local account was not switched.")
    }
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
private fun profileFailure(failure: NodeFailure): String = when (failure) {
    NodeFailure.OtherAccount -> mb("云端资料属于另一个账号，未覆盖", "The profile belongs to another account; nothing was overwritten")
    NodeFailure.Deleted -> mb("云端资料已删除，未重新创建", "The profile was deleted; it was not recreated")
    NodeFailure.Conflict -> mb("云端资料已变更，未覆盖", "The profile changed; nothing was overwritten")
    NodeFailure.Unauthorized -> mb("授权已失效，请重新接入", "Authorization expired; reconnect")
    NodeFailure.Retryable -> mb("节点暂不可达，请稍后重试", "Node temporarily unavailable; try again later")
    NodeFailure.Unsupported -> mb("节点尚未提供资料同步接口，请更新服务器", "This node needs a server update to support profile sync")
    NodeFailure.Rejected -> mb("节点拒绝同步请求，未覆盖本地资料", "The node rejected sync; local data was not overwritten")
    else -> mb("资料校验失败，未覆盖本地资料", "Profile validation failed; local data was not overwritten")
}
