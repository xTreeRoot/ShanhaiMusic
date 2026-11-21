package com.xtreeroot.mb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.edge.syncPluginPreferences
import kotlinx.coroutines.CancellationException

/** 启动、绑定、回到前台和本地变更均先拉再合并；依赖改变时取消旧节点任务。 */
@Composable internal fun PluginPreferencesSynchronization() {
    val app = LocalContext.current.applicationContext as MBApplication
    val owner = LocalLifecycleOwner.current
    val binding by app.settings.nodeBinding.collectAsStateWithLifecycle()
    val changes by app.plugins.changes.collectAsStateWithLifecycle()
    var foreground by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) foreground++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(binding, changes, foreground) {
        val target = binding ?: return@LaunchedEffect
        if (target.expiresAt <= System.currentTimeMillis() / 1_000) return@LaunchedEffect
        try {
            app.nodeClient.syncPluginPreferences(target, app.plugins::pluginPreferences, app.plugins::mergePluginPreferences,
                stillCurrent = { app.settings.nodeBinding.value == target })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 已落盘的本地编辑保留，下次前台或编辑时重新同步。 */ }
    }
}
