package com.xtreeroot.mb.plugin

import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyPluginStorageTest {
    @Test fun migrationCopiesOnlyItsOwnPreferencesAndNeverOverwritesPluginData() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MBApplication
        val id = "migration-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences("host.storage.$id", 0)
        prefs.edit().putString("cookie", "fixture-private").putBoolean("enabled", false).commit()
        val candidate = app.plugins.prepareArchive { file -> ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in listOf("plugin.json" to """{"id":"$id"}""", "index.js" to "export function home(){return {sections:[]}}")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        } }
        val plugin = app.plugins.install(candidate)
        try {
            plugin.dataDirectory.mkdirs()
            val file = File(plugin.dataDirectory, "storage.json")
            file.writeText("""{"session":{"keep":true}}""")
            LegacyPluginStorage.migrate(app, plugin)
            val first = JSONObject(file.readText())
            assertTrue(first.getJSONObject("session").getBoolean("keep"))
            assertEquals("fixture-private", first.getJSONObject("legacy").getJSONObject("preferences").getString("cookie"))
            assertFalse(first.getJSONObject("legacy").getJSONObject("preferences").getBoolean("enabled"))
            prefs.edit().putString("cookie", "old-late-value").commit()
            val bytes = file.readBytes(); LegacyPluginStorage.migrate(app, plugin)
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(prefs.contains("cookie"))
        } finally { prefs.edit().clear().commit(); app.plugins.remove(plugin); plugin.dataDirectory.deleteRecursively() }
    }
}
