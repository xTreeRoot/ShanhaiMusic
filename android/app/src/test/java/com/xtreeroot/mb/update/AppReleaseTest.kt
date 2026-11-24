package com.xtreeroot.mb.update

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun release(version: String, repository: String = "ShanhaiMusic") = JSONObject().put("tag_name", "mb-android-v$version")
        .put("html_url", "https://github.com/xTreeRoot/$repository/releases/tag/mb-android-v$version")
        .put("assets", JSONArray().put(JSONObject().put("name", "MB-android-v$version.apk").put("size", 3)
            .put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/xTreeRoot/$repository/releases/download/mb-android-v$version/MB-android-v$version.apk")))

    @Test fun acceptsRenamedAndLegacyRepositoryReleases() {
        for (repository in listOf("ShanhaiMusic", "MB")) {
            val update = AppRelease.latest(JSONArray().put(release("0.1.27", repository)).toString())
            assertNotNull(update)
            assertEquals("/xTreeRoot/$repository/releases/tag/mb-android-v0.1.27", update?.page?.path)
        }
        for (repository in listOf("ShanhaiMusic-extra", "MB-extra", "another")) {
            assertNull(AppRelease.latest(JSONArray().put(release("0.1.27", repository)).toString()))
        }
    }

    @Test fun selectsNumericNewestAndroidReleaseRegardlessOfPublishOrder() {
        val ios = release("9.0.0").put("tag_name", "mb-ios-v9.0.0-build500")
        val json = JSONArray().put(release("0.1.9")).put(ios).put(release("0.1.25"))
            .put(release("1.0.0").put("prerelease", true)).put(release("2.0.0").put("draft", true))
        assertEquals("0.1.25", AppRelease.latest(json.toString())?.version)
        assertTrue(compareVersions("0.1.25", "0.1.9") > 0)
        assertEquals(0, compareVersions("0.1.25", "0.1.25"))
    }

    @Test fun rejectsMissingDigestWrongOriginAndDuplicateOrOversizeAssets() {
        for ((key, value) in listOf("digest" to "a".repeat(64), "size" to maxUpdateBytes + 1,
            "browser_download_url" to "https://github.com.evil.test/xTreeRoot/MB/releases/a.apk",
            "browser_download_url" to "http://github.com/xTreeRoot/MB/releases/a.apk",
            "browser_download_url" to "https://github.com/other/MB/releases/a.apk")) {
            val item = release("0.1.25")
            item.getJSONArray("assets").getJSONObject(0).put(key, value)
            assertNull(AppRelease.latest(JSONArray().put(item).toString()))
        }
        val duplicate = release("0.1.25")
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").get(0))
        assertNull(AppRelease.latest(JSONArray().put(duplicate).toString()))
    }

    @Test fun verifiesBytesAndRejectsTruncationCorruptionAndExcessBeforeInstall() {
        val bytes = ByteArray(130_000) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        copyUpdateArchive(bytes.inputStream(), output, bytes.size.toLong(), hash, {}, progress::add)
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        for ((data, size) in listOf(bytes.dropLast(1).toByteArray() to bytes.size, bytes to bytes.size - 1,
            bytes.copyOf().apply { this[0] = 42 } to bytes.size)) {
            assertThrows(IllegalStateException::class.java) {
                copyUpdateArchive(data.inputStream(), ByteArrayOutputStream(), size.toLong(), hash, {}, {})
            }
        }
    }

    @Test fun cancellationStopsWritingBeforeTheNextChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            copyUpdateArchive(ByteArray(130_000).inputStream(), output, 130_000, "", {
                if (++checks == 2) throw CancellationException()
            }, {})
        }
        assertEquals(64 * 1024, output.size())
    }

    @Test fun onlyAllowsNewerSamePackageAndOriginalSignature() {
        val current = UpdateIdentity("com.xtreeroot.mb", 25, "0.1.24", setOf("original"))
        val next = current.copy(versionCode = 26, version = "0.1.25")
        verifyUpdateIdentity(current, next, "0.1.25")
        for (bad in listOf(next.copy(packageName = "another.app"), next.copy(versionCode = 25),
            next.copy(version = "0.1.26"), next.copy(signers = setOf("other")), next.copy(signers = emptySet()))) {
            assertThrows(IllegalStateException::class.java) { verifyUpdateIdentity(current, bad, "0.1.25") }
        }
    }
}
