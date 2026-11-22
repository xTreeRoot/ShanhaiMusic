package com.xtreeroot.mb.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xtreeroot.mb.music.SignInStatus
import com.xtreeroot.mb.music.LibraryRoute
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountSignOutDialog(close: () -> Unit, signOut: () -> Unit, clearCache: Boolean = false) {
    AlertDialog(onDismissRequest = close, title = { Text(if (clearCache) mb("退出并清理登录缓存？", "Clear this sign-in session?") else mb("退出登录？", "Sign out?")) },
        text = if (clearCache) {{ Text(mb("将清理此插件的登录会话，下次登录需要重新授权。资料库、下载和插件设置会保留。", "Clear this plugin's sign-in session and sign in again. Your library, downloads and plugin settings will be kept.")) }} else null,
        confirmButton = { TextButton(onClick = { close(); signOut() }) { Text(mb("退出", "Sign Out")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}

@Composable
internal fun SignInDialog(state: AccountState, model: AccountModel) {
    if (state.choosingSignInMethod) {
        AlertDialog(onDismissRequest = model::cancelSignIn, title = { Text(mb("选择登录方式", "Choose sign-in method")) },
            text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.signInMethods.forEach { method ->
                    TextButton(onClick = { model.chooseSignInMethod(method.id) }, modifier = Modifier.fillMaxWidth()) { Text(method.label) }
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = model::cancelSignIn) { Text(mb("取消", "Cancel")) } })
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt = state.prompt
    val image = remember(prompt?.qrUrl) { prompt?.qrUrl?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    var shareError by remember(prompt?.qrUrl) { mutableStateOf(false) }
    val qrLabel = mb("登录二维码", "Sign In QR Code")
    val shareLabel = mb("分享二维码", "Share QR Code")
    AlertDialog(onDismissRequest = model::cancelSignIn,
        title = { Text(mb("登录音源", "Sign In to Source")) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (image != null) {
                    Image(bitmap = image.asImageBitmap(), contentDescription = qrLabel,
                        modifier = Modifier.size(228.dp), filterQuality = FilterQuality.None)
                    TextButton(onClick = {
                        scope.launch {
                            try { shareQr(context, image, qrLabel, shareLabel) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { shareError = true }
                        }
                    }) { Text(shareLabel) }
                }
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                else if (prompt == null) Loading()
                else Text(when (prompt.status) {
                    SignInStatus.Scan -> mb("使用对应平台 App 扫码登录", "Scan with the platform app.")
                    SignInStatus.Confirm -> mb("已扫码，请在对应平台 App 确认登录", "Scanned. Confirm sign in in the platform app.")
                    SignInStatus.Refreshed -> mb("二维码已刷新，请扫描新的二维码", "QR code refreshed. Scan the new code.")
                    SignInStatus.Message -> prompt.message
                })
                if (shareError) Text(mb("无法分享二维码", "Could not share the QR code"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (state.error != null) TextButton(onClick = model::signIn) { Text(mb("重新登录", "Try Again")) }
        },
        dismissButton = { TextButton(onClick = model::cancelSignIn) { Text(mb("取消", "Cancel")) } })
}

private fun qrBitmap(url: String): Bitmap {
    val size = 384
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val colors = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
}

private suspend fun shareQr(context: Context, image: Bitmap, qrLabel: String, shareLabel: String) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "sign-in").apply { mkdirs() }
        val file = File(directory, "mb-login.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(qrLabel, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, shareLabel))
}
