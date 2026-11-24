package com.xtreeroot.mb.plugin

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class PluginUpdate(val plugin: InstalledPlugin, val release: GitHubPluginRevision)
internal data class PluginUpdateFailure(val pluginName: String, val detail: String?)
internal data class PluginUpdateReport(
    val available: List<PluginUpdate> = emptyList(),
    val installed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<PluginUpdateFailure> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
)

internal class PluginUpdates(private val installer: PluginInstaller,
                             private val release: suspend (String) -> GitHubPluginRevision?,
                             private val download: suspend (GitHubPluginRevision, File) -> Unit) {
    suspend fun compare(plugins: List<InstalledPlugin>): PluginUpdateReport {
        val available = mutableListOf<PluginUpdate>()
        val failures = mutableListOf<PluginUpdateFailure>()
        val unchanged = mutableListOf<String>()
        for (plugin in plugins) {
            val repository = plugin.repository ?: continue
            try {
                val latest = release(repository) ?: continue
                if (latest.revision != plugin.remoteRevision) available += PluginUpdate(plugin, latest)
                else unchanged += plugin.manifest.name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += PluginUpdateFailure(plugin.manifest.name, error.message)
            }
        }
        return PluginUpdateReport(available = available, failures = failures, unchanged = unchanged)
    }

    suspend fun prepare(update: PluginUpdate): PendingPlugin = installer.prepareArchive(
        update.plugin.repository, update.plugin, remoteRevision = update.release.revision,
    ) { download(update.release, it) }.copy(releaseNotes = update.release.notes)

    suspend fun apply(report: PluginUpdateReport, review: suspend (PendingPlugin) -> Boolean): PluginUpdateReport {
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = report.failures.toMutableList()
        for (update in report.available) {
            val name = update.plugin.manifest.name
            try {
                val candidate = prepare(update)
                try {
                    if (!candidate.addsNetworkPermissions() || review(candidate)) {
                        installer.install(candidate)
                        installed += name
                    } else skipped += name
                } finally { withContext(NonCancellable) { installer.discard(candidate) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += PluginUpdateFailure(name, error.message)
            }
        }
        return PluginUpdateReport(installed = installed, skipped = skipped, failures = failures, unchanged = report.unchanged,
            notes = report.available.mapNotNull { update -> update.release.notes?.let { update.plugin.displayName to it } }.toMap())
    }
}

internal fun PendingPlugin.addsNetworkPermissions(): Boolean {
    val previous = replaces?.manifest ?: return true
    return manifest.network.any { host -> previous.network.none { it == "*" || it.equals(host, ignoreCase = true) } }
}

private val pluginVersion = Regex("[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?")

internal fun isNewerPluginVersion(candidate: String?, current: String?): Boolean {
    val next = pluginVersion.matchEntire(candidate ?: return false) ?: return false
    val old = pluginVersion.matchEntire(current ?: "0.0.0") ?: return false
    val nextCore = next.groupValues[1].split('.')
    val oldCore = old.groupValues[1].split('.')
    for (index in 0 until maxOf(nextCore.size, oldCore.size)) {
        val comparison = compareVersionNumbers(nextCore.getOrElse(index) { "0" }, oldCore.getOrElse(index) { "0" })
        if (comparison != 0) return comparison > 0
    }
    val nextSuffix = next.groups[2]?.value?.split('.') ?: return old.groups[2] != null
    val oldSuffix = old.groups[2]?.value?.split('.') ?: return false
    for ((left, right) in nextSuffix.zip(oldSuffix)) {
        val leftNumeric = left.all { it in '0'..'9' }
        val rightNumeric = right.all { it in '0'..'9' }
        val comparison = when {
            leftNumeric && rightNumeric -> compareVersionNumbers(left, right)
            leftNumeric != rightNumeric -> if (leftNumeric) -1 else 1
            else -> left.compareTo(right)
        }
        if (comparison != 0) return comparison > 0
    }
    return nextSuffix.size > oldSuffix.size
}

private fun compareVersionNumbers(left: String, right: String): Int {
    val lhs = left.trimStart('0')
    val rhs = right.trimStart('0')
    return if (lhs.length != rhs.length) lhs.length.compareTo(rhs.length) else lhs.compareTo(rhs)
}
