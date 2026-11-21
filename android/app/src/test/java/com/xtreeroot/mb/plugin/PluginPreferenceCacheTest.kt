package com.xtreeroot.mb.plugin

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PluginPreferenceCacheTest {
    @Test fun deletedOrCorruptPrimaryRestoresResetAndVersionMetadata() {
        val root = Files.createTempDirectory("plugin-cache").toFile()
        try {
            val cache = PluginPreferenceCache(root)
            val migrate: (String) -> PluginPreferences = { PluginPreferences().withName("one", "旧名字", it).withEnabled("one", false, it) }
            val before = cache.load(migrate)
            val reset = cache.update(migrate) { value, writer -> value.withName("one", "", writer) }
            assertTrue(reset.names.getValue("one").clock > before.names.getValue("one").clock)
            for (corrupt in listOf(false, true)) {
                val primary = File(root, "preferences.json")
                if (corrupt) primary.writeText("broken") else primary.delete()
                assertEquals(reset, PluginPreferenceCache(root).load(migrate))
            }
            File(root, "preferences.json").writeText("broken")
            File(root, "recovery/preferences.json").writeText("broken")
            assertTrue(runCatching { cache.load(migrate) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
