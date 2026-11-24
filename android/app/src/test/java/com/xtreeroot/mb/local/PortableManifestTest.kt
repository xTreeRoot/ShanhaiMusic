package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.QueueSnapshot
import com.xtreeroot.mb.preferences.AppAppearance
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.AudioNormalization
import com.xtreeroot.mb.preferences.LyricStyle
import com.xtreeroot.mb.preferences.PlaybackMode
import com.xtreeroot.mb.preferences.PlayerStyle
import com.xtreeroot.mb.preferences.ProxyPolicy
import java.io.FileNotFoundException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableManifestTest {
    private val id = "a".repeat(64)
    private val remote = Track("bilibili", "BVsample", "云端歌曲", "作者", "https://example.org/cover",
        artworkHeaders = mapOf("Referer" to "https://example.org", "Cookie" to "private-account-value"))
    private val manifest = PortableManifest(
        listOf(PortableImport(id, "导入", PortableAsset("mb-import-$id.audio", 24, id), "[00:01.00]词", true, 100)),
        listOf(PortableDownload(remote, PortableAsset("mb-saved-22222222-2222-2222-2222-222222222222.m4a", 36, "b".repeat(64)))),
        listOf(LocalPlaylist("playlist", "我的歌单", listOf("local:$id", remote.id))),
        512,
    )

    @Test fun selectsPreviousCheckpointWhenTheNewestDocumentCannotBeOpened() {
        val older = "mb-state-1795120000000-11111111-1111-1111-1111-111111111111.json"
        val newer = "mb-state-1795120000001-22222222-2222-2222-2222-222222222222.json"
        val bytes = manifest.encode()
        val files = mapOf(older to bytes.size.toLong(), newer to -1L)
        val checkpoint = latestPortableCheckpoint(files) { name ->
            if (name == newer) throw FileNotFoundException()
            bytes.inputStream()
        }
        assertEquals(older, checkpoint?.name)
        assertEquals(manifest.playlists, checkpoint?.manifest?.playlists)
        assertTrue(checkpoint?.skippedUnreadable == true)
        val failure = assertThrows(PortableFailure::class.java) {
            latestPortableCheckpoint(files) { throw FileNotFoundException() }
        }
        assertEquals(PortableProblem.Read, failure.problem)
        assertThrows(SecurityException::class.java) {
            latestPortableCheckpoint(files) { throw SecurityException() }
        }
    }

    @Test fun rejectsAWriteReadBackThatIsValidJsonButHasDifferentContent() {
        val expected = manifest.copy(history = listOf("Alpha")).encode()
        val wrong = manifest.copy(history = listOf("Bravo")).encode()
        assertEquals(expected.size, wrong.size)
        assertTrue(portableIndexMatches(expected.inputStream(), expected))
        assertFalse(portableIndexMatches(wrong.inputStream(), expected))
    }

    @Test fun restoresPortableTracksAndPreservesLyricsAndPlaylistOrderWithoutCredentials() {
        val bytes = manifest.encode()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private-account-value"))
        val restored = PortableManifest.decode(bytes)
        assertEquals(manifest.imports, restored.imports)
        assertEquals(manifest.playlists, restored.playlists)
        assertEquals(manifest.assets, restored.assets)
        assertEquals(mapOf("Referer" to "https://example.org"), restored.downloads.single().track.artworkHeaders)
    }

    @Test fun readsLegacyImportActivityWithoutAnActivityField() {
        assertEquals(listOf(LocalTrackActivity("$localSourceId:$id", 1, 100)), PortableManifest.decode(manifest.encode()).activity)
    }

    @Test fun preservesAdditionTimesAndAcceptsOlderBackupsWithoutThem() {
        val updated = manifest.copy(imports = manifest.imports.map { it.copy(addedAt = 200) },
            downloads = manifest.downloads.map { it.copy(addedAt = 300) })
        val restored = PortableManifest.decode(updated.encode())
        assertEquals(200L, restored.imports.single().addedAt)
        assertEquals(300L, restored.downloads.single().addedAt)

        val legacy = JSONObject(updated.encode().decodeToString())
        legacy.getJSONArray("imports").getJSONObject(0).remove("addedAt")
        legacy.getJSONArray("downloads").getJSONObject(0).remove("addedAt")
        val older = PortableManifest.decode(legacy.toString().encodeToByteArray())
        assertEquals(0L, older.imports.single().addedAt)
        assertEquals(0L, older.downloads.single().addedAt)
    }

    @Test fun roundTripsDownloadActivityAndExplicitUnlikeUsingFullTrackIds() {
        val entries = listOf(LocalTrackActivity(remote.id, 250, 350), LocalTrackActivity("other:${remote.sourceTrackId}", 0, 0))
        val restored = PortableManifest.decode(manifest.copy(activity = entries).encode())
        assertEquals(entries, restored.activity)
    }

    @Test fun preservesFolderDigestButDoesNotRestoreAPathAsACollectionIdentifier() {
        val imported = manifest.imports.single().copy(folderId = "b".repeat(64))
        val raw = manifest.copy(imports = listOf(imported)).encode()
        assertEquals(imported, PortableManifest.decode(raw).imports.single())
        val malformed = JSONObject(raw.decodeToString())
        malformed.getJSONArray("imports").getJSONObject(0).put("folderId", "content://example.org/private/path")
        assertEquals(imported.copy(folderId = null), PortableManifest.decode(malformed.toString().encodeToByteArray()).imports.single())
    }

    @Test fun rejectsUnexpectedPathsAndInconsistentImportDigests() {
        val raw = JSONObject(manifest.encode().toString(Charsets.UTF_8))
        raw.getJSONArray("imports").getJSONObject(0).getJSONObject("asset").put("sha256", "c".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { PortableManifest.decode(raw.toString().toByteArray()) }
        val invalidPath = manifest.encode().toString(Charsets.UTF_8).replace("mb-saved-", "../mb-saved-")
        assertThrows(IllegalArgumentException::class.java) { PortableManifest.decode(invalidPath.toByteArray()) }
    }

    @Test fun rejectsOversizedAudioAssetsAndUnknownSchemas() {
        val raw = JSONObject(manifest.encode().toString(Charsets.UTF_8))
        raw.getJSONArray("downloads").getJSONObject(0).getJSONObject("asset").put("bytes", PortableAsset.maximumBytes + 1)
        assertThrows(IllegalArgumentException::class.java) { PortableManifest.decode(raw.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            PortableManifest.decode(JSONObject(manifest.encode().toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray())
        }
    }

    @Test fun restoresQueueAndSearchHistoryWithoutPrivateArtworkPaths() {
        val local = Track(localSourceId, id, "导入", "本地作者", artworkUrl = "file:///private/local-artwork")
        val queue = QueueSnapshot(listOf(local, remote), 1, 1234, 2, true, listOf(1, 0))
        val encoded = manifest.copy(history = listOf("夜曲", "钢琴"), queue = queue).encode()
        assertFalse(encoded.toString(Charsets.UTF_8).contains("file:///private"))
        val restored = PortableManifest.decode(encoded)
        assertEquals(listOf("夜曲", "钢琴"), restored.history)
        assertEquals(queue.copy(tracks = listOf(local.copy(artworkUrl = null), remote.copy(
            artworkHeaders = mapOf("Referer" to "https://example.org")))), restored.queue)
    }

    @Test fun restoresNonSensitiveSettingsAndStillReadsBackupsWithoutSettings() {
        val values = AppPreferences(appearance = AppAppearance.Dark, language = AppLanguage.English, volume = 0.3f,
            quality = AudioQuality.Lossless, normalization = AudioNormalization.Dynamic,
            playbackMode = PlaybackMode.Video,
            lyricsStage = true, nowPlayingLyrics = false,
            hotComments = true, playerStyle = PlayerStyle.Cassette, lyricStyle = LyricStyle.Steps,
            stageSparks = true, smoke = true, fog = true, growFromCover = true, proxy = ProxyPolicy.parse("*.example.org"))
        assertEquals(values, PortableManifest.decode(manifest.copy(preferences = values).encode()).preferences)
        assertEquals(null, PortableManifest.decode(manifest.encode()).preferences)
        assertEquals(AudioNormalization.Standard, AppPreferences.parse(JSONObject("""{"quality":"High"}""")).normalization)
    }

    @Test fun pluginPackagesRoundTripAndCannotUseArbitraryFileNames() {
        val asset = PortableAsset("mb-plugin-3a39c8eb-926f-466c-9ba0-876ca03571a1.zip", 400, "c".repeat(64))
        val plugin = PortablePlugin("sample", "owner/repository", asset, mapOf("quality" to "high"))
        val values = manifest.copy(plugins = listOf(plugin))
        val bytes = values.encode()
        assertEquals(values.plugins, PortableManifest.decode(bytes).plugins)
        assertEquals(emptyList<PortablePlugin>(), PortableManifest.decode(manifest.encode()).plugins)
        val redirected = bytes.decodeToString().replace(asset.name, "../../source.zip").encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) { PortableManifest.decode(redirected) }
    }

    @Test fun backgroundIsAnOptionalContentAddressedAssetWithAnImageSizeLimit() {
        val sha = "e".repeat(64)
        val picture = PortableAsset("mb-background-$sha.png", 800, sha)
        val restored = PortableManifest.decode(manifest.copy(background = picture).encode())
        assertEquals(picture, restored.background)
        assertEquals(manifest.assets + picture, restored.assets)
        assertEquals(null, PortableManifest.decode(manifest.encode()).background)
        for (invalid in listOf(picture.copy(name = "../${picture.name}"), picture.copy(digest = "f".repeat(64)),
            picture.copy(bytes = 12 * 1024 * 1024 + 1L))) {
            assertThrows(IllegalArgumentException::class.java) { PortableManifest.decode(manifest.copy(background = invalid).encode()) }
        }
    }
}
