package com.xtreeroot.mb.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xtreeroot.mb.plugin.PluginFetchRequest
import com.xtreeroot.mb.plugin.PluginHttpTransport
import com.xtreeroot.mb.plugin.copyPluginBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AppUpdates(private val context: Context, private val transport: PluginHttpTransport) {
    suspend fun latest(): AppRelease? = transport.receive(request(URI("https://api.github.com/repos/xTreeRoot/ShanhaiMusic/releases?per_page=100")),
        { it.scheme == "https" && it.host == "api.github.com" }) { response, _ ->
        check(response.code == 200) { "检查版本失败（HTTP ${response.code}）" }
        val output = ByteArrayOutputStream()
        checkNotNull(response.body).byteStream().use { copyPluginBytes(it, output, 4L * 1024 * 1024) }
        AppRelease.latest(output.toString("UTF-8"))
    }

    suspend fun download(release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "MB-${release.version}.apk")
        val context = currentCoroutineContext()
        try {
            transport.receive(request(release.archive), { it.scheme == "https" && it.host in archiveHosts && it.rawUserInfo == null }) { response, _ ->
                check(response.code == 200) { "下载更新失败（HTTP ${response.code}）" }
                val body = checkNotNull(response.body)
                check(body.contentLength() <= maxUpdateBytes) { "更新包过大" }
                body.byteStream().use { input -> file.outputStream().use { output ->
                    copyUpdateArchive(input, output, release.size, release.sha256, { context.ensureActive() }, progress)
                } }
            }
            verifyArchive(file, release.version)
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    @Suppress("DEPRECATION")
    internal fun verifyArchive(file: File, expectedVersion: String) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = checkNotNull(manager.getPackageArchiveInfo(file.path, flags)) { "无法读取更新包" }
        val nextVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val currentVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        fun android.content.pm.PackageInfo.certificates(): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        }
        verifyUpdateIdentity(UpdateIdentity(installed.packageName, currentVersion, installed.versionName.orEmpty(), installed.certificates()),
            UpdateIdentity(archive.packageName, nextVersion, archive.versionName.orEmpty(), archive.certificates()), expectedVersion)
    }

    private fun request(url: URI) = PluginFetchRequest(url, "GET", mapOf("User-Agent" to "MB-Android", "Accept" to "application/vnd.github+json", "Cache-Control" to "no-cache"), null)
    private companion object {
        val archiveHosts = setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
