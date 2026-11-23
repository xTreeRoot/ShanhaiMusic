package com.xtreeroot.mb.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal object PluginRepository {
    private val repository = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    fun parse(input: String): String {
        val text = input.trim()
        if (text.length > 2048) throw PluginException("Invalid GitHub repository")
        val repo = if ("://" in text) {
            val url = uri(text)
            if (!url.onGitHub("github.com")) throw PluginException("Invalid GitHub repository")
            val parts = url.path.orEmpty().split('/').filter(String::isNotEmpty)
            if (parts.size != 2 || url.rawQuery != null || url.rawFragment != null) throw PluginException("Invalid GitHub repository")
            parts.joinToString("/")
        } else text
        val normalized = repo.removeSuffix(".git")
        if (!repository.matches(normalized) || ".." in normalized || normalized.split('/').any { it == "." }) {
            throw PluginException("Use owner/repository or a GitHub repository URL")
        }
        return normalized
    }

    fun fromInstallLink(input: String): String? = runCatching {
        if (input.length > 2048) return null
        val link = uri(input)
        if (link.scheme.lowercase() !in setOf("mb", "mygo-music") || !link.host.equals("install-plugin", true) ||
            link.port != -1 || link.rawUserInfo != null || link.rawFragment != null || link.rawPath.orEmpty() !in setOf("", "/")) return null
        val values = link.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            if (URLDecoder.decode(pair.substringBefore('='), "UTF-8") != "repo") null
            else URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        val repo = values.singleOrNull() ?: return null
        if (!repository.matches(repo)) return null
        parse(repo)
    }.getOrNull()

    fun assetIndex(names: List<String>, supportedAbis: List<String>): Int? {
        val namesLower = names.map { it.lowercase() }
        for (abi in supportedAbis) {
            val tokens = when (abi) {
                "arm64-v8a" -> listOf("arm64", "aarch64")
                "armeabi-v7a" -> listOf("armeabi-v7a", "armv7")
                "x86_64" -> listOf("x86_64", "x64", "amd64")
                "x86" -> listOf("x86")
                else -> listOf(abi)
            }
            val index = namesLower.indexOfFirst { name -> name.endsWith(".zip") && "android" in name &&
                tokens.any { token -> token in name } && (abi != "x86" || "x86_64" !in name) }
            if (index >= 0) return index
        }
        return namesLower.indices.firstOrNull { index -> namesLower[index].endsWith(".zip") && "android" in namesLower[index] &&
            listOf("universal", "all-abi").any { it in namesLower[index] } }
            ?: namesLower.indices.firstOrNull { index -> namesLower[index].endsWith(".zip") &&
                listOf("darwin", "macos", "windows", "linux", "android", "ios").none { it in namesLower[index] } }
    }

    fun universalRelease(repository: String, release: JSONObject): GitHubPluginRevision? {
        val universalName = "plugin-universal.zip"
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.opt("name") != universalName) continue
            val assetID = (asset.opt("id") as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
            val tag = (release.opt("tag_name") as? String)?.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it) }
            if (assetID == null || tag == null) throw PluginException("Invalid universal plugin release")
            val archive = uri("https://github.com/$repository/releases/download/$tag/$universalName")
            return GitHubPluginRevision("asset-$assetID", archive, (release.opt("body") as? String)?.takeIf(String::isNotBlank))
        }
        return null
    }
}

internal class GitHubPluginPackages(private val transport: PluginHttpTransport, private val abis: List<String>) {
    suspend fun repositories(input: String): List<String> {
        GitHubStarsList.repository(input)?.let { return listOf(it) }
        val initial = GitHubStarsList.address(input) ?: throw PluginException("Enter a plugin repository or catalog web address")
        val visited = mutableSetOf<HttpUrl>()
        val repositories = linkedMapOf<String, String>()
        var next: HttpUrl? = initial
        while (next != null) {
            val page = next
            if (!visited.add(page) || visited.size > 8) throw PluginException("Too many plugin catalog pages")
            val request = request(uri(page.toString())).copy(headers = mapOf("User-Agent" to "MB-Android", "Accept" to "text/html, */*;q=0.8"))
            val listing = transport.receive(request, { target ->
                target.host != null && target.rawUserInfo == null
            }) { response, target ->
                if (response.code != 200) throw PluginException("Plugin catalog failed (HTTP ${response.code})")
                GitHubStarsList.repository(target.toString())?.let { return@receive GitHubStarsList.Page(listOf(it), null) }
                val incoming = response.body ?: throw PluginException("Plugin catalog is empty")
                val data = ByteArrayOutputStream()
                incoming.byteStream().use { copyPluginBytes(it, data, 4L * 1024 * 1024) }
                GitHubStarsList.read(data.toByteArray().decodeToString(), checkNotNull(GitHubStarsList.address(target.toString())))
            }
            listing.repositories.forEach { repositories.putIfAbsent(it.lowercase(), it) }
            if (repositories.size > 32) throw PluginException("Import supports up to 32 repositories per batch")
            next = listing.next
        }
        return repositories.values.toList().ifEmpty { throw PluginException("No plugin repository links found") }
    }

    suspend fun latestRevision(input: String): GitHubPluginRevision {
        val repository = PluginRepository.parse(input)
        val base = repositoryUrl(repository)
        metadata(uri(base.newBuilder().addPathSegments("releases/latest").build().toString()), allowMissing = true)
            ?.let { PluginRepository.universalRelease(repository, it) }?.let { return it }
        val result = checkNotNull(metadata(uri(base.newBuilder().addPathSegments("commits/HEAD").build().toString())))
        val sha = (result.opt("sha") as? String)?.takeIf { Regex("[0-9a-f]{40}").matches(it) }
            ?: throw PluginException("GitHub commit response has no valid SHA")
        val archive = uri(base.newBuilder().addPathSegment("zipball").addPathSegment(sha).build().toString())
        return GitHubPluginRevision(sha, archive, result.optJSONObject("commit")?.optString("message")?.takeIf(String::isNotBlank))
    }

    suspend fun download(revision: GitHubPluginRevision, output: File) = downloadArchive(revision.archive, output)

    private fun repositoryUrl(input: String) = "https://api.github.com/repos/".toHttpUrl().newBuilder().apply {
        PluginRepository.parse(input).split('/').forEach { addPathSegment(it) }
    }.build()

    private suspend fun downloadArchive(archive: URI, output: File) {
        if (!allowedArchive(archive)) throw PluginException("Invalid GitHub archive URL")
        transport.receive(request(archive), ::allowedArchive) { response, _ ->
            if (response.code != 200) throw PluginException("GitHub archive failed (HTTP ${response.code})")
            val body = response.body ?: throw PluginException("GitHub archive is missing")
            if (body.contentLength() > pluginZipLimit) throw PluginException("Plugin ZIP exceeds 64 MB")
            body.byteStream().use { input -> output.outputStream().use { copyPluginBytes(input, it, pluginZipLimit) } }
        }
    }

    private suspend fun metadata(address: URI, allowMissing: Boolean = false): JSONObject? =
        transport.receive(request(address), { it.onGitHub("api.github.com") }) { response, _ ->
            if (allowMissing && response.code == 404) return@receive null
            if (response.code != 200) throw PluginException("GitHub request failed (HTTP ${response.code})")
            val incoming = response.body ?: throw PluginException("No GitHub metadata")
            val copy = ByteArrayOutputStream()
            incoming.byteStream().use { copyPluginBytes(it, copy, 4L * 1024 * 1024) }
            JSONObject(copy.toByteArray().decodeToString(throwOnInvalidSequence = true))
        }

    private fun request(address: URI) = PluginFetchRequest(address, "GET",
        mapOf("User-Agent" to "MB-Android", "Accept" to "application/vnd.github+json", "Cache-Control" to "no-cache"), null)

    private fun allowedArchive(address: URI): Boolean = archiveHosts.any { address.onGitHub(it) }

    companion object {
        private val archiveHosts = setOf("github.com", "api.github.com", "codeload.github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com")
    }
}

internal data class GitHubPluginRevision(val revision: String, val archive: URI, val notes: String? = null)

private fun URI.onGitHub(hostname: String): Boolean = scheme.equals("https", true) && host.equals(hostname, true) &&
    rawUserInfo == null && (port == -1 || port == 443)
