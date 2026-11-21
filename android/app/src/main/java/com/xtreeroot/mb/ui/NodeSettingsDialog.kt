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
import com.xtreeroot.mb.edge.NodeException
import com.xtreeroot.mb.edge.NodeFailure
import com.xtreeroot.mb.edge.nodeOrigin
import com.xtreeroot.mb.ui.components.MBButton
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
                              publish: () -> Unit, surface: Color, close: () -> Unit) {
    val binding by app.settings.nodeBinding.collectAsStateWithLifecycle()
    val valid = binding?.let { it.expiresAt > System.currentTimeMillis() / 1_000 } == true
    var address by remember { mutableStateOf(binding?.origin?.toString().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<NodeFailure?>(null) }
    var invalidAddress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = loading || profileSync == NodeProfileSyncState.Publishing
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
            MBButton(onClick = connect, enabled = !busy && address.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(if (loading) mb("正在接入…", "Connecting…") else if (binding != null) mb("重新接入", "Reconnect") else mb("接入节点", "Connect to Node"))
            }
            error?.let { Text(nodeJoinFailure(it), Modifier.padding(horizontal = 32.dp), color = MaterialTheme.colorScheme.error) }
            if (binding != null) MBGroupedSection(mb("当前节点", "Current Node")) {
                MBFormRow(if (valid) mb("已接入", "Connected") else mb("授权已过期", "Authorization expired"), detail = binding?.origin?.host)
                MBFormDivider()
                MBFormRow(mb("同步当前平台资料", "Sync Current Platform Profile"), enabled = !busy && valid && canPublish, onClick = publish)
                MBFormDivider()
                MBFormRow(mb("解除此设备绑定", "Disconnect This Device"), enabled = !busy, color = MaterialTheme.colorScheme.error, onClick = {
                    app.settings.setNodeBinding(null); password = ""; error = null
                })
            }
            val result = when (profileSync) {
                NodeProfileSyncState.Idle -> null
                NodeProfileSyncState.Publishing -> mb("正在同步…", "Syncing…")
                NodeProfileSyncState.Published -> mb("平台资料已同步", "Platform profile synced")
                is NodeProfileSyncState.Failed -> profileFailure(profileSync.failure)
            }
            if (binding != null && result != null) Text(result, Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                color = if (profileSync is NodeProfileSyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
