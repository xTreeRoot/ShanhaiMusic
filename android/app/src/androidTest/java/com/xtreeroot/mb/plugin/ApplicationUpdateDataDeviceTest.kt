package com.xtreeroot.mb.plugin

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式启用的覆盖安装数据检查，只写本次交付的独立备份，不改写用户数据。 */
class ApplicationUpdateDataDeviceTest {
    @Test fun snapshotOrVerifyPrivateDataAcrossAnAppUpdate() {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("mbApplicationDataStage")
        assumeTrue(stage == "before" || stage == "after")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.applicationInfo.dataDir)
        val output = File(checkNotNull(context.getExternalFilesDir(null)), "delivery-android44").apply { mkdirs() }
        val files = listOf("files", "shared_prefs", "databases", "no_backup").flatMap { name ->
            File(root, name).walkTopDown().filter { file -> file.isFile && !file.relativeTo(root).invariantSeparatorsPath.let {
                it.startsWith("files/plugins/code/") || it.startsWith("files/plugins/pending/") || "/catalog-cache/" in it
            } }.toList()
        }
        val hashes = JSONObject()
        for (file in files) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            hashes.put(file.relativeTo(root).invariantSeparatorsPath, digest.digest().joinToString("") { "%02x".format(it) })
        }
        val checkpoint = File(output, "before-hashes.json")
        val metadataName = if (stage == "before") "private-metadata-backup.zip" else "after-metadata-backup.zip"
        ZipOutputStream(File(output, metadataName).outputStream()).use { archive ->
                for (file in files.filter { it.length() <= 16L * 1024 * 1024 && it.extension in setOf("json", "xml", "db", "db-wal", "db-shm", "txt") }) {
                    archive.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
        }
        if (stage == "before") {
            assertTrue(files.isNotEmpty())
            checkpoint.writeText(hashes.toString())
        } else {
            val before = JSONObject(checkpoint.readText())
            // AndroidX ProfileVerifier/ProfileInstaller 写入包的 lastUpdateTime；覆盖后必然变化，并非用户资料。
            val installationMarkers = listOf("files/profileInstalled", "files/profileinstaller_profileWrittenFor_lastUpdateTime.dat")
            File(output, "after-hashes.json").writeText(hashes.toString())
            for (name in installationMarkers) { before.remove(name); hashes.remove(name) }
            assertEquals(before.keys().asSequence().toSet(), hashes.keys().asSequence().toSet())
            for (name in before.keys()) assertEquals("Private file changed: $name", before.getString(name), hashes.getString(name))
        }
        File(output, "$stage-receipt.json").writeText(JSONObject().put("privateFiles", files.size).put("verified", true).toString())
    }
}
