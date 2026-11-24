package com.xtreeroot.mb.update

import java.net.URI
import org.json.JSONArray

internal data class AppRelease(val tag: String, val version: String, val page: URI, val notes: String,
                               val archive: URI, val size: Long, val sha256: String) {
    companion object {
        fun latest(json: String): AppRelease? = JSONArray(json).let { values ->
            (0 until values.length()).mapNotNull { index ->
                val entry = values.optJSONObject(index) ?: return@mapNotNull null
                if (entry.optBoolean("draft") || entry.optBoolean("prerelease")) return@mapNotNull null
                val tag = entry.optString("tag_name")
                val version = Regex("mb-android-v([0-9]+\\.[0-9]+\\.[0-9]+)").matchEntire(tag)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val assets = entry.optJSONArray("assets") ?: return@mapNotNull null
                val asset = (0 until assets.length()).mapNotNull(assets::optJSONObject)
                    .singleOrNull { it.optString("name") == "MB-android-v$version.apk" } ?: return@mapNotNull null
                val archive = runCatching { URI(asset.getString("browser_download_url")) }.getOrNull() ?: return@mapNotNull null
                val page = runCatching { URI(entry.getString("html_url")) }.getOrNull() ?: return@mapNotNull null
                val digest = asset.optString("digest")
                val sha = digest.removePrefix("sha256:")
                val size = asset.optLong("size")
                if (!archive.isGitHubRelease() || !page.isGitHubRelease() || !digest.startsWith("sha256:") ||
                    !Regex("[0-9a-f]{64}").matches(sha) || size !in 1..maxUpdateBytes ||
                    version.split('.').any { it.toLongOrNull() == null }) return@mapNotNull null
                AppRelease(tag, version, page, entry.optString("body"), archive, size, sha)
            }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        }
    }
}

internal const val maxUpdateBytes = 256L * 1024 * 1024
internal fun URI.isGitHubRelease() = scheme == "https" && host == "github.com" && rawUserInfo == null && port == -1 &&
    (path.startsWith("/xTreeRoot/ShanhaiMusic/releases/") || path.startsWith("/xTreeRoot/MB/releases/"))
internal fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').map { it.toLongOrNull() ?: return 0 }
    val right = b.split('.').map { it.toLongOrNull() ?: return 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val result = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (result != 0) return result
    }
    return 0
}
