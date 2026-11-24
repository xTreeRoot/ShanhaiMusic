package com.xtreeroot.mb.plugin

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class GitHubPluginImport(private val installer: PluginInstaller,
                                 private val latest: suspend (String) -> GitHubPluginRevision,
                                 private val download: suspend (GitHubPluginRevision, File) -> Unit) {
    suspend fun run(repositories: List<String>, review: suspend (List<PendingPlugin>) -> Boolean): PluginUpdateReport {
        val candidates = linkedMapOf<String, PendingPlugin>()
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val failed = mutableListOf<PluginUpdateFailure>()
        try {
            for (repository in repositories.map(PluginRepository::parse).distinctBy { it.lowercase() }) {
                if (nonPluginRepositories.any { it.equals(repository, ignoreCase = true) }) {
                    skipped += repository
                    continue
                }
                try {
                    val previous = installer.installed().firstOrNull { it.repository.equals(repository, ignoreCase = true) }
                    val remote = latest(repository)
                    if (previous != null && previous.remoteRevision == remote.revision) {
                        unchanged += previous.manifest.name
                        continue
                    }
                    val candidate = installer.prepareArchive(repository, previous, remoteRevision = remote.revision) { download(remote, it) }.copy(releaseNotes = remote.notes)
                    if (candidate.replaces != null && !candidate.replaces.repository.equals(repository, ignoreCase = true)) {
                        installer.discard(candidate)
                        throw PluginException("Plugin ID belongs to a different repository")
                    }
                    if (candidate.manifest.id == com.xtreeroot.mb.local.localSourceId || candidates.containsKey(candidate.manifest.id)) {
                        installer.discard(candidate)
                        throw PluginException("Reserved or duplicate plugin ID: ${candidate.manifest.id}")
                    }
                    candidates[candidate.manifest.id] = candidate
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failed += PluginUpdateFailure(repository, failure.message) }
            }
            if (candidates.isNotEmpty()) {
                if (review(candidates.values.toList())) for (candidate in candidates.values) {
                    try {
                        installer.install(candidate)
                        installed += candidate.manifest.name
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failed += PluginUpdateFailure(candidate.repository.orEmpty(), failure.message) }
                } else skipped += candidates.values.map { it.repository.orEmpty() }
            }
        } finally {
            withContext(NonCancellable) { candidates.values.forEach { installer.discard(it) } }
        }
        return PluginUpdateReport(installed = installed, skipped = skipped, failures = failed, unchanged = unchanged)
    }

    private companion object {
        val nonPluginRepositories = setOf("xTreeRoot/ShanhaiMusic", "xTreeRoot/MB", "xTreeRoot/mb-plugin-template")
    }
}
