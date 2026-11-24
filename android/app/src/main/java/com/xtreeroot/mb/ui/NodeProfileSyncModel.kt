package com.xtreeroot.mb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.edge.NodeBinding
import com.xtreeroot.mb.edge.NodeException
import com.xtreeroot.mb.edge.NodeFailure
import com.xtreeroot.mb.edge.PlatformDataKey
import com.xtreeroot.mb.edge.PlatformProfile
import com.xtreeroot.mb.edge.syncProfile
import com.xtreeroot.mb.music.SourceAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface NodeProfileSyncState {
    data object Idle : NodeProfileSyncState
    data object Publishing : NodeProfileSyncState
    data object Published : NodeProfileSyncState
    data class Failed(val failure: NodeFailure) : NodeProfileSyncState
}

internal data class NodeProfileTarget(val binding: NodeBinding, val platform: String, val profile: PlatformProfile) {
    companion object {
        fun verified(binding: NodeBinding?, platform: String?, account: SourceAccount?): NodeProfileTarget? {
            if (binding == null || binding.expiresAt <= System.currentTimeMillis() / 1_000 || platform == null || account == null) return null
            return runCatching {
                PlatformDataKey(platform, "profile")
                NodeProfileTarget(binding, platform, PlatformProfile(account.id, account.name))
            }.getOrNull()
        }
    }
}

internal class NodeProfileSyncModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val mutable = MutableStateFlow<NodeProfileSyncState>(NodeProfileSyncState.Idle)
    val state = mutable.asStateFlow()
    private var current: NodeProfileTarget? = null
    private var sync: Job? = null
    private var revision = 0L

    fun select(target: NodeProfileTarget?, isCurrent: () -> Boolean) {
        if (current == target) return
        revision++
        sync?.cancel()
        current = target
        mutable.value = NodeProfileSyncState.Idle
        if (target != null) start(target, publish = false, isCurrent)
    }

    fun publish(target: NodeProfileTarget?, isCurrent: () -> Boolean) {
        if (target == null || mutable.value == NodeProfileSyncState.Publishing) return
        revision++
        sync?.cancel()
        current = target
        start(target, publish = true, isCurrent)
    }

    private fun start(target: NodeProfileTarget, publish: Boolean, isCurrent: () -> Boolean) {
        val expected = revision
        if (publish) mutable.value = NodeProfileSyncState.Publishing
        sync = viewModelScope.launch {
            try {
                val synced = app.nodeClient.syncProfile(target.binding, target.platform, target.profile, publish, isCurrent)
                ensureActive()
                if (expected == revision && isCurrent()) {
                    app.settings.cacheNodeProfile(target.binding, target.platform, synced)
                    mutable.value = if (publish) NodeProfileSyncState.Published else NodeProfileSyncState.Idle
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: NodeException) {
                if (expected == revision && isCurrent()) mutable.value = NodeProfileSyncState.Failed(failure.failure)
            } finally {
                if (expected == revision && publish && mutable.value == NodeProfileSyncState.Publishing) {
                    mutable.value = NodeProfileSyncState.Idle
                }
            }
        }
    }
}
