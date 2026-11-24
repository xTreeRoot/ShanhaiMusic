package com.xtreeroot.mb.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.xtreeroot.mb.plugin.PluginOAuthBroker
import com.xtreeroot.mb.plugin.PluginOAuthPage

@Composable
internal fun PluginOAuthDialog(page: PluginOAuthPage, broker: PluginOAuthBroker, cancel: () -> Unit) {
    val context = LocalContext.current
    val browserError = mb("无法打开系统浏览器，请检查浏览器设置", "Could not open a system browser")
    var opened by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = cancel, title = { Text(page.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(page.host, style = MaterialTheme.typography.titleSmall)
            Text(if (opened) mb("在浏览器完成授权后会返回山海。", "Finish in the browser to return to Shanhai.")
                else mb("使用系统浏览器授权。", "Authorize with your system browser."))
        }
    }, confirmButton = {
        if (!opened) TextButton(onClick = {
            val address = broker.begin(page.id) ?: return@TextButton
            opened = true
            try { context.startActivity(Intent(Intent.ACTION_VIEW, address.toASCIIString().toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: Exception) { broker.fail(page.id, browserError) }
        }) { Text(mb("打开浏览器", "Open Browser")) }
    }, dismissButton = { TextButton(onClick = cancel) { Text(mb("取消", "Cancel")) } })
}
