package com.xtreeroot.mb.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Activity 进入前台时后台检查；关闭偏好之后不启动新的请求。 */
@Composable
internal fun AutomaticAppUpdates(enabled: Boolean, canPresent: Boolean = true, model: AppUpdateModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val latestEnabled by rememberUpdatedState(enabled)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled) { if (!enabled) { model.cancel(); model.hideAutomaticPrompt() } }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && latestEnabled && !model.state.value.automaticPrompt) model.check(automatic = true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (state.automaticPrompt && enabled && canPresent) AppUpdateDialog(automatic = true, model = model) { model.hideAutomaticPrompt() }
}
