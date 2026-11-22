package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ResourceBookmarksTest {
    @Test fun bookmarksRoundTripResourceLinksAndReplaceByIdentity() {
        val root = Files.createTempDirectory("mb-bookmark").toFile()
        try {
            val file = root.resolve("resources.json")
            val track = Track("fixture", "1", "Example", "Fixture", resourceType = "video", resourceUrl = "https://example.test/file")
            val store = ResourceBookmarks(file)
            store.save(track); store.save(track.copy(title = "Updated"))
            val restored = ResourceBookmarks(file); restored.refresh()
            assertEquals(listOf(track.copy(title = "Updated")), restored.tracks.value)
            restored.remove(track.id)
            store.refresh(); assertTrue(store.tracks.value.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
