package com.xtreeroot.mb.edge

import com.xtreeroot.mb.plugin.PluginInstaller
import com.xtreeroot.mb.plugin.restoreDeviceArchivePlugins
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import com.xtreeroot.mb.plugin.restorePluginInformation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    @Test fun encryptedChunksRoundTripRejectTamperingAndDelete() = runBlocking {
        MockWebServer().use { server ->
            val objects = java.util.concurrent.ConcurrentHashMap<String, String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer $recovery", request.getHeader("Authorization"))
                    return when (request.method) {
                        "PUT" -> { objects[request.path!!] = request.body.readUtf8(); MockResponse().setResponseCode(201) }
                        "GET" -> MockResponse().setBody(objects.getValue(request.path!!))
                        "DELETE" -> { objects.remove(request.path!!); MockResponse().setResponseCode(204) }
                        else -> MockResponse().setResponseCode(400)
                    }
                }
            }
            server.start()
            val origin = nodeOrigin("fixture.test")
            val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
            val node = NodeBinding(origin, "AAECAwQFBgcICQoLDA0ODw", "test-space", recovery, Long.MAX_VALUE, recovery, recovery)
            val transfer = DeviceBackupTransfer(NodeClient(http), node)
            val input = temporary.newFile().apply { writeBytes(ByteArray(backupChunkBytes + 123) { 73 }) }
            val ref = transfer.upload(input, "android", 2)
            assertEquals(3, objects.size)
            val output = temporary.newFile(); transfer.download(node.backupOwner(), ref, output)
            assertArrayEquals(input.readBytes(), output.readBytes())
            transfer.delete(ref); assertTrue(objects.isEmpty())
            val second = transfer.upload(input, "android", 2)
            objects.keys.first().let { objects[it] = "{}" }
            assertTrue(runCatching { transfer.download(node.backupOwner(), second, temporary.newFile()) }.isFailure)
        }
    }

    private fun manifest() = JSONObject().put("version", 1).put("platform", "ios").put("plugins", JSONArray()).put("library", JSONObject().put("tracks", JSONArray()))
    private fun packageFile(file: File, id: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, value) in mapOf("plugin.json" to """{"id":"$id","name":"Fixture","version":"1.0.0","settings":[{"key":"mode","type":"string","label":"Mode"}]}""", "index.js" to "export function search(){return {tracks:[]};}")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
    }
    @Test fun blankInstallRestoresPackagesFullSettingsStorageNamesOrderAndEnabled() = runBlocking {
        val root = temporary.newFolder(); val zip = File(root, "fixture.zip"); packageFile(zip, "fixture")
        val storage = File(root, "storage.json").apply { writeText("""{"session":"synthetic"}""") }
        val doc = manifest().put("plugins", JSONArray().put(JSONObject().put("id", "fixture").put("name", "我的插件")
            .put("enabled", true).put("settings", JSONObject().put("mode", "complete"))))
        val backup = File(root, "device.zip")
        DeviceArchive.write(doc, listOf(DeviceArchiveEntry("plugins/fixture/package.zip", zip), DeviceArchiveEntry("plugins/fixture/data/storage.json", storage)), backup)
        val stage = File(root, "stage"); val read = DeviceArchive.extract(backup, stage)
        val installer = PluginInstaller(File(root, "plugins")); installer.restoreDeviceArchivePlugins(read, stage)
        val plugin = installer.installed().single()
        assertEquals("我的插件", plugin.displayName); assertTrue(plugin.enabled)
        assertEquals("complete", installer.settings(plugin)["mode"])
        val restored = File(plugin.dataDirectory, "storage.json")
        assertEquals(storage.readText(), restored.readText())
        restored.writeText("current")
        installer.restoreDeviceArchivePlugins(read, stage)
        assertEquals("current", restored.readText()); assertEquals(1, installer.installed().size)
    }
    @Test fun invalidArchiveAndInvalidSecondPackageCannotInstallFirstPlugin() = runBlocking {
        val root = temporary.newFolder(); val unsafe = File(root, "unsafe.zip")
        ZipOutputStream(unsafe.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
        assertTrue(runCatching { DeviceArchive.extract(unsafe, File(root, "stage")) }.isFailure)
        assertFalse(File(root.parentFile, "escape").exists())
        val stage = File(root, "packages").apply { mkdirs() }
        File(stage, "plugins/one").mkdirs(); File(stage, "plugins/two").mkdirs()
        packageFile(File(stage, "plugins/one/package.zip"), "one")
        File(stage, "plugins/two/package.zip").writeText("broken")
        val doc = manifest().put("plugins", JSONArray(listOf("one", "two").map { JSONObject().put("id", it).put("enabled", true).put("settings", JSONObject()) }))
        val installer = PluginInstaller(File(root, "installed"))
        assertTrue(runCatching { installer.restoreDeviceArchivePlugins(doc, stage) }.isFailure)
        assertTrue(installer.installed().isEmpty())
    }
    @Test fun selectedPluginRestoreOverwritesPrivateDataKeepsCodeAndOtherPlugin() = runBlocking {
        val root = temporary.newFolder()
        val stage = File(root, "content").apply { mkdirs() }
        val records = JSONArray()
        for (id in listOf("chosen", "other")) {
            val directory = File(stage, "plugins/$id").apply { mkdirs() }
            packageFile(File(directory, "package.zip"), id)
            File(directory, "data").mkdirs()
            File(directory, "data/storage.json").writeText("from-B-$id")
            records.put(JSONObject().put("id", id).put("enabled", true).put("settings", JSONObject().put("mode", "from-B")))
        }
        val doc = manifest().put("plugins", records)
        val installer = PluginInstaller(File(root, "installed"))
        installer.restoreDeviceArchivePlugins(doc, stage)
        val chosen = installer.installed().first { it.id == "chosen" }
        val other = installer.installed().first { it.id == "other" }
        val code = File(chosen.directory, "index.js").apply { writeText("// newer installed code") }
        File(chosen.dataDirectory, "storage.json").writeText("local-A")
        File(chosen.dataDirectory, "old-file").writeText("old")
        File(other.dataDirectory, "storage.json").writeText("other-local-A")
        assertTrue(runCatching { installer.restorePluginInformation(doc, stage, "missing") }.isFailure)
        assertTrue(runCatching { installer.restorePluginInformation(doc, stage, "chosen") { throw kotlinx.coroutines.CancellationException() } }.isFailure)
        assertEquals("local-A", File(chosen.dataDirectory, "storage.json").readText())
        installer.restorePluginInformation(doc, stage, "chosen")
        assertEquals("from-B-chosen", File(chosen.dataDirectory, "storage.json").readText())
        assertEquals("other-local-A", File(other.dataDirectory, "storage.json").readText())
        assertFalse(File(chosen.dataDirectory, "old-file").exists())
        assertEquals("from-B", installer.settings(chosen)["mode"])
        assertEquals("// newer installed code", code.readText())
        assertEquals(2, installer.installed().size)
    }

}
