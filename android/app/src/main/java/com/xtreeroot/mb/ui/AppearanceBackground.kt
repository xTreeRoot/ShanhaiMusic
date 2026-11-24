package com.xtreeroot.mb.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.xtreeroot.mb.preferences.BackgroundSkinStyle

internal enum class BackgroundError { Load, Import, Clear }

internal data class AppearanceBackgroundState(val image: Bitmap? = null, val busy: Boolean = true, val error: BackgroundError? = null, val candidate: Bitmap? = null, val preview: Bitmap? = null, val previewOpacity: Float = 0.25f, val previewStyle: BackgroundSkinStyle = BackgroundSkinStyle())

internal class AppearanceBackgroundModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as MBApplication).appearanceBackground
    private val current = MutableStateFlow(AppearanceBackgroundState())
    val state = current.asStateFlow()
    private var editJob: Job? = null

    init {
        viewModelScope.launch {
            store.restores.collect {
                while (editJob?.isActive == true) editJob?.join()
                edit(BackgroundError.Load, store::load)
            }
        }
    }

    fun prepare(uri: Uri) = prepare { store.prepare(uri) }
    fun prepareRemote(address: String) = prepare { store.prepareRemote(address) }
    private fun prepare(action: suspend () -> Bitmap) {
        if (current.value.busy) return
        val previous = current.value
        current.value = previous.copy(busy = true, error = null)
        editJob = viewModelScope.launch {
            try { current.value = previous.copy(busy = false, candidate = action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.value = previous.copy(busy = false, error = BackgroundError.Import) }
        }
    }
    fun discardCandidate() {
        editJob?.cancel()
        current.value = current.value.copy(busy = false, candidate = null, error = null)
    }
    fun beginPreview(candidate: Bitmap, opacity: Float, style: BackgroundSkinStyle = BackgroundSkinStyle()) {
        if (!current.value.busy) current.value = current.value.copy(candidate = null, preview = candidate,
            previewOpacity = opacity.coerceIn(0f, 1f), previewStyle = style, error = null)
    }
    fun setPreviewOpacity(opacity: Float) {
        if (!current.value.busy) current.value = current.value.copy(previewOpacity = opacity.coerceIn(0f, 1f))
    }
    fun setPreviewStyle(transform: (BackgroundSkinStyle) -> BackgroundSkinStyle) {
        if (!current.value.busy) current.value = current.value.copy(previewStyle = transform(current.value.previewStyle))
    }
    fun cancelPreview() {
        if (!current.value.busy) current.value = current.value.copy(preview = null, error = null)
    }
    fun confirmPreview() {
        val previous = current.value
        val candidate = previous.preview ?: return
        if (previous.busy) return
        current.value = previous.copy(busy = true, error = null)
        editJob = viewModelScope.launch {
            try {
                val saved = store.save(candidate)
                getApplication<MBApplication>().settings.update { it.copy(backgroundOpacity = previous.previewOpacity, backgroundStyle = previous.previewStyle) }
                current.value = AppearanceBackgroundState(image = saved, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.value = previous.copy(busy = false, error = BackgroundError.Import) }
        }
    }
    fun apply(candidate: Bitmap) {
        if (!current.value.busy) edit(BackgroundError.Import) { store.save(candidate) }
    }

    fun clear() {
        if (!current.value.busy) edit(BackgroundError.Clear) { store.clear(); null }
    }

    private fun edit(error: BackgroundError, action: suspend () -> Bitmap?) {
        val previous = current.value
        current.value = previous.copy(busy = true, error = null)
        editJob = viewModelScope.launch {
            current.value = try { AppearanceBackgroundState(action(), busy = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { previous.copy(busy = false, error = error) }
        }
    }
}

@Composable
internal fun AppearanceBackgroundSetting(state: AppearanceBackgroundState, choose: () -> Unit, clear: () -> Unit) {
    Column {
        TextButton(onClick = choose, enabled = !state.busy) { Text(mb("选择背景图片", "Choose Background Image")) }
        if (state.image != null || state.error == BackgroundError.Load) {
            TextButton(onClick = clear, enabled = !state.busy) { Text(mb("恢复默认背景", "Restore Default Background")) }
        }
        val error = when (state.error) {
            BackgroundError.Load -> mb("无法读取已保存的背景", "Can't read the saved background")
            BackgroundError.Import -> mb("无法使用这张图片", "Can't use this image")
            BackgroundError.Clear -> mb("无法清除已保存的背景", "Can't clear the saved background")
            null -> null
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
