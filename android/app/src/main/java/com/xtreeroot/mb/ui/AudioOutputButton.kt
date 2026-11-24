package com.xtreeroot.mb.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.xtreeroot.mb.R

@Composable
internal fun AudioOutputButton(enabled: Boolean, onOpen: () -> Unit = {}) {
    val context = LocalContext.current
    val label = mb("音频输出", "Audio Output")
    var showFallback by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    IconButton(enabled = enabled, onClick = {
        onOpen()
        settingsUnavailable = false
        showFallback = try { !SystemOutputSwitcherDialogController.showDialog(context) }
        catch (_: ActivityNotFoundException) { true }
        catch (_: SecurityException) { true }
    }) { Icon(painterResource(R.drawable.ic_audio_output), label) }
    if (showFallback) AlertDialog(
        onDismissRequest = { showFallback = false },
        title = { Text(label) },
        text = {
            Text(if (settingsUnavailable) mb("无法打开蓝牙设置。", "Could not open Bluetooth settings.")
                else mb("此设备没有可用的音频输出面板。你可以在系统设置里连接蓝牙设备。",
                    "The output switcher is unavailable. Use system settings to connect a Bluetooth device."))
        },
        confirmButton = {
            TextButton(enabled = !settingsUnavailable, onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    showFallback = false
                } catch (_: ActivityNotFoundException) { settingsUnavailable = true }
                catch (_: SecurityException) { settingsUnavailable = true }
            }) { Text(mb("蓝牙设置", "Bluetooth Settings")) }
        },
        dismissButton = { TextButton(onClick = { showFallback = false }) { Text(mb("取消", "Cancel")) } },
    )
}
