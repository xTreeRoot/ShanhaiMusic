package com.xtreeroot.mb.plugin

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPreferencesTest {
    private val sections = listOf(DiscoveryPreferences.Section("one", true), DiscoveryPreferences.Section("two", true))
    @Test fun draftsPreserveUntouchedRemoteFieldsAndNewColumnsRemainOff() {
        val empty = DiscoveryPreferences(); val before = empty.resolve("fixture", sections); val writer = PluginPreferences.newWriter()
        val initial = empty.edited("fixture", before, before, writer)
        val remote = initial.edited("fixture", before, before.copy(enabled = before.enabled + ("two" to false)), PluginPreferences.newWriter())
        val result = remote.edited("fixture", before, before.copy(order = before.order.reversed()), writer)
        val resolved = result.resolve("fixture", sections + DiscoveryPreferences.Section("new", true))
        assertEquals(listOf("two", "one", "new"), resolved.order); assertEquals(false, resolved.enabled["two"]); assertEquals(false, resolved.enabled["new"])
        assertEquals(result, DiscoveryPreferences.parse(result.toJson()))
        assertEquals(listOf("one"), result.resolve("fixture", sections.take(1)).order)
        assertNotNull(result.plugins["fixture"]?.enabled?.get("two"))
    }
    @Test fun cacheRepairsOneCopyAndNeverSilentlyResetsBothCorruptCopies() {
        val root = Files.createTempDirectory("discovery-prefs").toFile()
        try {
            val cache = PreferenceCache(root, DiscoveryPreferences::parse); val initial = DiscoveryPreferences().resolve("fixture", sections)
            val doc = cache.update({ DiscoveryPreferences() }) { d, w -> d.edited("fixture", initial, initial, w) }
            root.resolve("preferences.json").writeText("broken")
            assertEquals(doc, cache.load { DiscoveryPreferences() })
            root.resolve("preferences.json").writeText("broken"); root.resolve("recovery/preferences.json").writeText("broken")
            assertThrows(Exception::class.java) { cache.load { DiscoveryPreferences() } }
        } finally { root.deleteRecursively() }
    }
    @Test fun allOffDoesNotFallBackAndIndependentDevicesConverge() {
        val empty = DiscoveryPreferences(); val before = empty.resolve("fixture", sections)
        val a = empty.edited("fixture", before, before.copy(enabled = mapOf("one" to false, "two" to false)), PluginPreferences.newWriter())
        val b = empty.edited("fixture", before, before, PluginPreferences.newWriter())
        assertEquals(a.merged(b), b.merged(a)); assertTrue(a.resolve("fixture", sections).enabled.values.none { it })
    }
}
