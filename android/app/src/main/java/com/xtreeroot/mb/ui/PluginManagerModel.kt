package com.xtreeroot.mb.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.plugin.GitHubPluginImport
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PendingPlugin
import com.xtreeroot.mb.plugin.PluginException
import com.xtreeroot.mb.plugin.PluginRepository
import com.xtreeroot.mb.plugin.PluginUpdateReport
import com.xtreeroot.mb.plugin.PluginUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface PluginCheck {
    data object Checking : PluginCheck
    data class Failed(val message: String) : PluginCheck
}

internal data class PluginManagerState(
    val installed: List<InstalledPlugin> = emptyList(),
    val pending: PendingPlugin? = null,
    val requestedRepository: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val check: PluginCheck? = null,
    val batchReview: Boolean = false,
    val updateReport: PluginUpdateReport? = null,
    val importReview: List<PendingPlugin>? = null,
    val importReport: PluginUpdateReport? = null,
)

internal class PluginManagerModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val updates = PluginUpdates(app.plugins, app.pluginPackages::latestRevision, app.pluginPackages::download)
    private val mutable = MutableStateFlow(PluginManagerState())
    private var retryPreparation: (suspend () -> PendingPlugin)? = null
    private var approval: CompletableDeferred<Boolean>? = null
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch { app.installedPlugins.collect { items -> mutable.update { it.copy(installed = items) } } }
        run { app.refreshPlugins() }
    }

    fun openRepository(repository: String) { mutable.update { it.copy(requestedRepository = repository) } }

    fun clearRepository(repository: String) {
        mutable.update { if (it.requestedRepository == repository) it.copy(requestedRepository = null) else it }
    }

    fun prepareZip(uri: Uri) = prepare {
        app.plugins.prepareZip { app.contentResolver.openInputStream(uri) ?: throw PluginException("Cannot read plugin ZIP") }
    }

    fun prepareTree(tree: Uri) = prepare { app.plugins.prepareTree(app.contentResolver, tree) }

    fun prepareGitHub(input: String) = prepare {
        val repo = PluginRepository.parse(input)
        val remote = app.pluginPackages.latestRevision(repo)
        app.plugins.prepareArchive(repo, remoteRevision = remote.revision) { app.pluginPackages.download(remote, it) }.copy(releaseNotes = remote.notes)
    }

    fun importGitHub(input: String) = run {
        mutable.update { it.copy(check = PluginCheck.Checking, importReport = null) }
        try {
            val repositories = app.pluginPackages.repositories(input)
            val report = GitHubPluginImport(app.plugins, app.pluginPackages::latestRevision, app.pluginPackages::download)
                .run(repositories, ::reviewImport)
            mutable.update { it.copy(importReport = report) }
            app.refreshPlugins()
        } finally { mutable.update { it.copy(check = null, importReview = null) } }
    }

    fun dismissImportReport() { mutable.update { it.copy(importReport = null) } }

    fun update(plugin: InstalledPlugin? = null) = scanUpdates(plugin?.let(::listOf)) { report ->
        val update = report.available.singleOrNull()
        if (plugin == null || update == null) mutable.update { it.copy(updateReport = report) }
        else {
            val candidate = updates.prepare(update)
            mutable.update { it.copy(pending = candidate) }
        }
    }

    fun updateAll() = scanUpdates { report ->
        val result = updates.apply(report, ::reviewUpdate)
        app.refreshPlugins()
        mutable.update { it.copy(updateReport = result) }
    }

    fun dismissUpdateReport() { mutable.update { it.copy(updateReport = null) } }

    fun install() {
        approval?.let { it.complete(true); return }
        run {
            val pending = mutable.value.pending ?: return@run
            app.plugins.install(pending)
            mutable.update { it.copy(pending = null) }
            app.refreshPlugins()
        }
    }

    fun discard() {
        approval?.let { it.complete(false); return }
        run {
            mutable.value.pending?.let { app.plugins.discard(it) }
            mutable.update { it.copy(pending = null) }
        }
    }

    fun enable(plugin: InstalledPlugin, enabled: Boolean) = run {
        app.plugins.setEnabled(plugin, enabled)
        app.refreshPlugins()
    }

    fun remove(plugin: InstalledPlugin, removed: (String) -> Unit) = run {
        app.plugins.remove(plugin)
        app.settings.update { values -> if (values.selectedSourceId == plugin.id) values.copy(selectedSourceId = null) else values }
        removed(plugin.id)
        app.refreshPlugins()
    }

    fun rename(plugin: InstalledPlugin, name: String) = run { app.plugins.rename(plugin, name); app.refreshPlugins() }
    fun move(plugin: InstalledPlugin, step: Int) = run { app.plugins.move(plugin.id, step); app.refreshPlugins() }

    suspend fun settings(plugin: InstalledPlugin) = app.plugins.settings(plugin)

    suspend fun save(plugin: InstalledPlugin, values: Map<String, String>, saved: () -> Unit) {
        try {
            app.plugins.saveSettings(plugin, values)
            app.refreshPlugins()
            mutable.update { it.copy(error = null) }
            saved()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Plugin settings could not be saved") } }
    }

    fun retryCheck() { retryPreparation?.let(::prepare) }

    fun dismissCheck() {
        if (mutable.value.check !is PluginCheck.Failed) return
        retryPreparation = null
        mutable.update { it.copy(check = null) }
    }

    private fun scanUpdates(targets: List<InstalledPlugin>? = null, operation: suspend (PluginUpdateReport) -> Unit) = run {
        mutable.update { it.copy(check = PluginCheck.Checking, updateReport = null) }
        try { operation(updates.compare(targets ?: app.plugins.installed())) }
        finally { mutable.update { it.copy(check = null) } }
    }

    private suspend fun reviewUpdate(candidate: PendingPlugin): Boolean {
        val answer = CompletableDeferred<Boolean>()
        approval = answer
        mutable.update { it.copy(pending = candidate, batchReview = true, check = null) }
        return try { answer.await() }
        finally {
            approval = null
            mutable.update { it.copy(pending = null, batchReview = false, check = PluginCheck.Checking) }
        }
    }

    private suspend fun reviewImport(candidates: List<PendingPlugin>): Boolean {
        val answer = CompletableDeferred<Boolean>()
        approval = answer
        mutable.update { it.copy(importReview = candidates, check = null) }
        return try { answer.await() }
        finally {
            approval = null
            mutable.update { it.copy(importReview = null, check = PluginCheck.Checking) }
        }
    }

    private fun prepare(build: suspend () -> PendingPlugin) {
        if (mutable.value.busy) return
        retryPreparation = build
        mutable.update { it.copy(busy = true, error = null, check = PluginCheck.Checking) }
        viewModelScope.launch {
            try {
                mutable.value.pending?.let { app.plugins.discard(it) }
                mutable.update { it.copy(pending = null) }
                val prepared = build()
                retryPreparation = null
                mutable.update { it.copy(pending = prepared, check = null, busy = false) }
            } catch (cancelled: CancellationException) {
                retryPreparation = null
                mutable.update { it.copy(check = null, busy = false) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(check = PluginCheck.Failed(error.message ?: "Plugin check failed"), busy = false) }
            }
        }
    }

    private fun run(operation: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { operation() }
            catch (error: CancellationException) { throw error }
            catch (error: PluginException) { mutable.update { it.copy(error = error.message) } }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Plugin operation failed") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
