package com.xtreeroot.mb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.plugin.PluginHttpTransport
import com.xtreeroot.mb.update.AppRelease
import com.xtreeroot.mb.update.AppUpdates
import com.xtreeroot.mb.update.compareVersions
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AppUpdateState(val checking: Boolean = false, val checked: Boolean = false, val release: AppRelease? = null,
                                   val downloading: Boolean = false, val progress: Float = 0f, val file: File? = null, val error: String? = null, val automaticPrompt: Boolean = false)
internal class AppUpdateModel @JvmOverloads constructor(application: Application, private val lookup: (suspend () -> AppRelease?)? = null) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val updates = AppUpdates(app, PluginHttpTransport(app.network.proxySelector, callTimeoutSeconds = 600))
    private var operation: Job? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    val version: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    fun check(automatic: Boolean = false) {
        if (automatic && !app.settings.values.value.automaticUpdateChecks) return
        if (mutable.value.checking || mutable.value.downloading) return
        val request = ++generation
        mutable.update { it.copy(checking = true, error = null) }
        operation = viewModelScope.launch {
            try {
                val latest = checkNotNull(if (lookup != null) lookup.invoke() else updates.latest()) { "远程尚无可用的安卓更新包" }
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                val release = latest.takeIf { compareVersions(it.version, version) > 0 }
                mutable.value.file?.delete()
                mutable.update { it.copy(checked = true, release = release, file = null, automaticPrompt = automatic && release != null && app.settings.values.value.automaticUpdateChecks) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == generation) mutable.update { it.copy(error = error.message ?: "检查版本失败") } }
            finally { if (request == generation) mutable.update { it.copy(checking = false) } }
        }
    }
    fun download() {
        if (mutable.value.checking) return
        val release = mutable.value.release ?: return
        if (mutable.value.downloading) return
        val request = ++generation
        mutable.update { it.copy(downloading = true, error = null, progress = 0f) }
        operation = viewModelScope.launch {
            try {
                val file = updates.download(release) { progress -> if (request == generation) mutable.update { it.copy(progress = progress) } }
                currentCoroutineContext().ensureActive()
                if (request == generation) mutable.update { it.copy(file = file) } else file.delete()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == generation) mutable.update { it.copy(error = error.message ?: "下载更新失败") } }
            finally { if (request == generation) mutable.update { it.copy(downloading = false) } }
        }
    }
    fun hideAutomaticPrompt() { mutable.update { it.copy(automaticPrompt = false) } }
    fun report(error: Exception) { mutable.update { it.copy(error = error.message) } }
    fun cancel() { generation++; operation?.cancel(); mutable.update { it.copy(checking = false, downloading = false) } }
}

@Composable
internal fun AppUpdateDialog(automatic: Boolean = false, model: AppUpdateModel = viewModel(), close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dismiss = { model.cancel(); model.hideAutomaticPrompt(); close() }
    AppUpdateContent(model.version, state, { model.check() }, model::download, install = {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", checkNotNull(state.file))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (error: Exception) { model.report(error) }
    }, dismiss = dismiss, dontDisturb = if (automatic) ({
        (context.applicationContext as MBApplication).settings.update { it.copy(automaticUpdateChecks = false) }
        dismiss()
    }) else null)
}

@Composable
internal fun AppUpdateContent(version: String, state: AppUpdateState, check: () -> Unit, download: () -> Unit,
                              install: () -> Unit, dismiss: () -> Unit, dontDisturb: (() -> Unit)? = null) {
    val primaryAction: @Composable () -> Unit = {
        when {
            state.file != null -> TextButton(onClick = install) { Text(mb("安装更新", "Install Update")) }
            state.release != null -> TextButton(onClick = download, enabled = !state.downloading) { Text(mb("下载更新", "Download Update")) }
            else -> TextButton(onClick = check, enabled = !state.checking) { Text(mb("检查版本", "Check Version")) }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(mb("检查版本", "App Updates")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(mb("当前版本 $version", "Current version $version"))
            if (state.checking) Text(mb("正在检查远程版本…", "Checking releases…"))
            state.release?.let { release -> Text(mb("发现新版本 ${release.version}", "Version ${release.version} available")); Text(release.notes) }
            if (state.checked && state.release == null && !state.checking && state.error == null) Text(mb("已是最新版本", "You're up to date"))
            if (state.downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.padding(vertical = 8.dp))
            state.error?.let { Text(it) }
        }
    }, confirmButton = {
        if (dontDisturb != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = dontDisturb) { Text(mb("不再打扰", "Don't Remind Me")) }
            primaryAction()
        } else primaryAction()
    }, dismissButton = if (dontDisturb == null) ({
        TextButton(onClick = dismiss) { Text(if (state.downloading) mb("取消下载", "Cancel Download") else mb("关闭", "Close")) }
    }) else null)
}
