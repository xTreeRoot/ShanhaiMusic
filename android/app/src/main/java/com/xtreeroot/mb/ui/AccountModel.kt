package com.xtreeroot.mb.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.R
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.account.QrSignInService
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.edge.NodeException
import com.xtreeroot.mb.edge.NodeFailure
import com.xtreeroot.mb.edge.NodeSessionCodec
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AccountState(
    val account: SourceAccount? = null,
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val prompt: SignInPrompt? = null,
    val signInMethods: List<com.xtreeroot.mb.music.SignInMethod> = emptyList(),
    val choosingSignInMethod: Boolean = false,
    val error: String? = null,
    val canSignIn: Boolean = false,
    val canSignOut: Boolean = false,
    val canClearLoginCache: Boolean = false,
    val canSyncSignIn: Boolean = false,
    val verifiedSource: AccountSource? = null,
) {
    fun verifiedFor(source: AccountSource?): SourceAccount? = account.takeIf {
        source != null && verifiedSource === source && !loading && !signingIn && error == null
    }
}

internal enum class NodeSignInResult { Uploading, Restoring, Uploaded, Restored, Missing, Multiple, Blocked, Unverified, Failed }

internal data class NodeSignInState(val result: NodeSignInResult, val failure: NodeFailure? = null) {
    val busy: Boolean get() = result == NodeSignInResult.Uploading || result == NodeSignInResult.Restoring
}

internal class AccountModel(application: Application) : AndroidViewModel(application) {
    private var source: AccountSource? = null
    private val state = MutableStateFlow(AccountState())
    private var job: Job? = null
    private var releaseQrProtection: (() -> Unit)? = null
    private var operation = 0L
    val account = state.asStateFlow()
    private var nodeJob: Job? = null
    private var nodeBinding: com.xtreeroot.mb.edge.NodeBinding? = null
    private var pendingNodeUpload: Pair<PluginSource, String>? = null
    private val nodeState = MutableStateFlow<NodeSignInState?>(null)
    val nodeSignIn = nodeState.asStateFlow()
    private val nodeBlocks = application.getSharedPreferences("node-local-sign-outs", Context.MODE_PRIVATE)

    fun activate(next: AccountSource?) {
        if (source === next) return
        stop()
        pendingNodeUpload = null
        source = next
        state.value = AccountState()
        refresh()
    }

    fun refresh() = launch(false) { active, generation ->
        val canSignIn = active.supportsSignIn()
        val canSignOut = active.supportsSignOut()
        val canClearLoginCache = (active as? com.xtreeroot.mb.music.AccountCacheResetSource)?.supportsAccountCacheReset() == true
        val canSync = (active as? PluginSource)?.supportsSessionSync() == true
        if (generation == operation) state.update { it.copy(canSignIn = canSignIn, canSignOut = canSignOut,
            canClearLoginCache = canClearLoginCache, canSyncSignIn = canSync) }
        active.account()
    }

    fun signIn() {
        if (!state.value.canSignIn) return
        val methods = (source as? com.xtreeroot.mb.music.SignInMethodSource)?.signInMethods.orEmpty()
        if (methods.isNotEmpty()) {
            stop()
            state.update { it.copy(signingIn = true, loading = false, prompt = null, error = null,
                signInMethods = methods, choosingSignInMethod = true) }
        } else startSignIn(null)
    }

    fun chooseSignInMethod(method: String) {
        if (!state.value.choosingSignInMethod || state.value.signInMethods.none { it.id == method }) return
        startSignIn(method)
    }

    private fun startSignIn(method: String?) {
        launch(true) { active, generation ->
            val receive: suspend (SignInPrompt) -> Unit = { prompt ->
                withContext(Dispatchers.Main.immediate) {
                    if (generation != operation) return@withContext
                    state.update { it.copy(prompt = prompt) }
                    if (prompt.qrUrl != null && releaseQrProtection == null) {
                        val app = getApplication<Application>()
                        releaseQrProtection = QrSignInService.protect(app) {
                            if (generation == operation) {
                                stop()
                                state.update { it.copy(loading = false, prompt = null, error = app.getString(R.string.qr_sign_in_expired)) }
                            }
                        }
                    }
                }
            }
            if (method == null) active.signIn(receive)
            else (active as com.xtreeroot.mb.music.SignInMethodSource).signIn(method, receive)
        }
    }

    fun cancelSignIn() {
        if (!state.value.signingIn) return
        stop()
        state.update { it.copy(loading = false, signingIn = false, prompt = null, error = null, choosingSignInMethod = false) }
    }

    fun signOut(clearCache: Boolean = false) {
        if (state.value.loading || if (clearCache) !state.value.canClearLoginCache else !state.value.canSignOut) return
        pendingNodeUpload = null
        launch(false) { active, _ ->
            if (active is PluginSource) withContext(Dispatchers.IO) {
                check(nodeBlocks.edit().putBoolean(active.id, true).commit()) { "无法保存本机退出状态" }
            }
            if (clearCache) (active as com.xtreeroot.mb.music.AccountCacheResetSource).signOutAndClearCache()
            else active.signOut()
            null
        }
    }

    private fun stop() {
        operation++
        job?.cancel()
        nodeJob?.cancel()
        nodeJob = null
        nodeState.value = null
        endQrProtection()
    }

    fun syncSignInIfNeeded() {
        val plugin = source as? PluginSource ?: return
        val local = state.value.verifiedFor(plugin)
        if (local == null) syncSignIn(publish = false)
        else if (pendingNodeUpload == (plugin to local.id)) syncSignIn(publish = true)
    }

    fun syncSignIn(publish: Boolean, manual: Boolean = false) {
        val app = getApplication<MBApplication>()
        val binding = app.settings.nodeBinding.value
        if (nodeBinding !== binding) {
            nodeJob?.cancel()
            nodeJob = null
            nodeBinding = binding
            nodeState.value = null
        }
        if (binding == null || binding.expiresAt <= System.currentTimeMillis() / 1_000) return
        val plugin = source as? PluginSource ?: return
        val local = state.value
        if (!local.canSyncSignIn || local.verifiedSource !== plugin || local.loading || local.signingIn || nodeJob?.isActive == true) return
        val account = local.verifiedFor(plugin)
        if (local.error != null || publish != (account != null)) return
        val generation = operation
        val current = { generation == operation && source === plugin && nodeBinding === binding && app.settings.nodeBinding.value === binding }
        val status = if (publish) NodeSignInResult.Uploading else NodeSignInResult.Restoring
        nodeState.value = NodeSignInState(status)
        nodeJob = viewModelScope.launch {
            var result: NodeSignInState? = null
            try {
                if (!publish && nodeBlocks.getBoolean(plugin.id, false)) {
                    if (manual) result = NodeSignInState(NodeSignInResult.Blocked)
                    return@launch
                }
                val codec = NodeSessionCodec(binding, plugin.id)
                val records = app.nodeClient.sessions(binding, plugin.id)
                ensureActive()
                if (!current()) return@launch
                if (account != null) {
                    val digest = plugin.verifyNodeSessionPlugin(app.nodeClient, binding)
                    ensureActive()
                    if (!current()) return@launch
                    val exported = plugin.exportNodeSession(account.id, digest)
                    val ref = codec.reference(account.id)
                    val envelope = codec.seal(exported, ref)
                    ensureActive()
                    if (!current()) return@launch
                    app.nodeClient.publishSession(binding, plugin.id, ref, records.firstOrNull { it.ref == ref }, envelope)
                    if (current()) pendingNodeUpload = null
                    result = NodeSignInState(NodeSignInResult.Uploaded)
                } else {
                    val live = records.filterNot { it.deleted }
                    if (live.isEmpty()) {
                        if (manual) result = NodeSignInState(NodeSignInResult.Missing)
                    } else if (live.size > 1) result = NodeSignInState(NodeSignInResult.Multiple)
                    else {
                        val digest = plugin.verifyNodeSessionPlugin(app.nodeClient, binding)
                        ensureActive()
                        if (!current()) return@launch
                        val record = live.single()
                        val session = codec.open(app.nodeClient.fetchSession(binding, plugin.id, record), record.ref)
                        ensureActive()
                        if (!current()) return@launch
                        val restored = plugin.applyNodeSession(session, digest, current)
                        ensureActive()
                        if (current() && restored != null) {
                            state.update { it.copy(account = restored, error = null) }
                            result = NodeSignInState(NodeSignInResult.Restored)
                        } else result = NodeSignInState(NodeSignInResult.Unverified)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: NodeException) { result = NodeSignInState(NodeSignInResult.Failed, failure.failure) }
            catch (_: Exception) { result = NodeSignInState(NodeSignInResult.Failed) }
            finally { if (current()) nodeState.value = result }
        }
    }

    private fun endQrProtection() {
        releaseQrProtection?.invoke()
        releaseQrProtection = null
    }

    override fun onCleared() = stop()

    private fun launch(login: Boolean, work: suspend (AccountSource, Long) -> SourceAccount?) {
        val active = source ?: return
        stop()
        val generation = operation
        state.update { it.copy(loading = true, signingIn = login, prompt = null, error = null, choosingSignInMethod = false) }
        job = viewModelScope.launch {
            try {
                val result = work(active, generation)
                ensureActive()
                if (generation == operation) {
                    val unblocked = if (login && result != null && active is PluginSource) withContext(Dispatchers.IO) {
                        nodeBlocks.edit().remove(active.id).commit()
                    } else false
                    ensureActive()
                    if (generation != operation) return@launch
                    state.update { it.copy(account = result, loading = false, signingIn = false, prompt = null, verifiedSource = active) }
                    if (unblocked && active is PluginSource && result != null && state.value.canSyncSignIn) pendingNodeUpload = active to result.id
                    if (unblocked || !login) syncSignInIfNeeded()
                }
            } catch (timedOut: TimeoutCancellationException) {
                if (generation == operation) state.update { it.copy(loading = false, prompt = null, error = "Sign-in timed out") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                if (generation == operation) state.update { it.copy(loading = false, prompt = null, error = failure.message ?: "账号请求失败") }
            } finally {
                if (generation == operation) endQrProtection()
            }
        }
    }
}
