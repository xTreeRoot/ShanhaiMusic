package com.xtreeroot.mb.plugin

import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 仅显式交付参数启用；按已验证提交更新公开代码，核对账号和本地偏好保留。 */
class PublicPluginUpdateDeviceTest {
    @Test fun updatesReviewedCommitAndKeepsLocalData() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mbPluginDelivery") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MBApplication
        val id = checkNotNull(args.getString("pluginId"))
        val repository = PluginRepository.parse(checkNotNull(args.getString("repository")))
        val commit = checkNotNull(args.getString("commit"))
        val old = app.plugins.installed().single { it.id == id }
        assertEquals(repository, old.repository?.let(PluginRepository::parse))
        val privateFiles = fingerprint(old.dataDirectory)
        val order = app.plugins.installed().map { it.id }
        val remote = app.pluginPackages.latestRevision(repository)
        assertEquals(commit, remote.revision)
        val candidate = app.plugins.prepareArchive(repository, old, remote.revision) { app.pluginPackages.download(remote, it) }
        try {
            assertEquals(id, candidate.manifest.id)
            assertEquals(args.getString("version"), candidate.manifest.version)
            assertTrue("New network access requires review", old.manifest.network.containsAll(candidate.manifest.network))
            app.plugins.install(candidate)
            val installed = app.plugins.installed().single { it.id == id }
            assertEquals(old.localName, installed.localName)
            assertEquals(old.enabled, installed.enabled)
            assertEquals(order, app.plugins.installed().map { it.id })
            assertTrue("Private plugin data changed", privateFiles == fingerprint(installed.dataDirectory))
            app.refreshPlugins()
        } finally { app.plugins.discard(candidate) }
    }
    private fun fingerprint(directory: File): Map<String, List<Byte>> = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
}
