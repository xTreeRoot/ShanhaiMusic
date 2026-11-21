package com.xtreeroot.mb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance

/** 三种图片来源统一进入裁剪和预览，选图成功不会立即替换皮肤。 */
@Composable
internal fun BackgroundImageFlow(state: AppearanceBackgroundState, model: AppearanceBackgroundModel) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.xtreeroot.mb.MBApplication
    val preferences by app.settings.values.collectAsState()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val opacity = preferences.backgroundOpacity ?: if (dark) 0.24f else 0.20f
    if (state.image != null) TextButton(onClick = { model.beginPreview(state.image, opacity) }, enabled = !state.busy) {
        Text(mb("预览并调整透明度", "Preview and Adjust Transparency"))
    }
    var choosing by remember { mutableStateOf(false) }
    var askingUrl by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::prepare) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::prepare) }
    AppearanceBackgroundSetting(state, { choosing = true }, model::clear)
    if (state.busy) CircularProgressIndicator()
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(mb("选择图片来源", "Choose Image Source")) },
        text = { Column {
            TextButton(onClick = { choosing = false; file.launch(arrayOf("image/*")) }) { Text(mb("从文件选择", "Choose from Files")) }
            TextButton(onClick = { choosing = false; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(mb("从照片选择", "Choose from Photos")) }
            TextButton(onClick = { choosing = false; askingUrl = true }) { Text(mb("网络图片地址", "Image URL")) }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }) { Text(mb("取消", "Cancel")) } })
    if (askingUrl) AlertDialog(onDismissRequest = { askingUrl = false }, title = { Text(mb("网络图片地址", "Image URL")) },
        text = { OutlinedTextField(address, { address = it }, singleLine = true, placeholder = { Text("https://") }) },
        confirmButton = { TextButton(onClick = { askingUrl = false; model.prepareRemote(address) }, enabled = address.isNotBlank()) { Text(mb("下一步", "Next")) } },
        dismissButton = { TextButton(onClick = { askingUrl = false }) { Text(mb("取消", "Cancel")) } })
    state.candidate?.let { candidate ->
        BackgroundImageEditor(candidate, state.busy, state.error != null, model::discardCandidate, { model.beginPreview(it, opacity) })
    }
    DisposableEffect(model) { onDispose { model.discardCandidate() } }
}
