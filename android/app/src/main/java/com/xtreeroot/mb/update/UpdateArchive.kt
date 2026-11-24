package com.xtreeroot.mb.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 流式校验完整更新包；取消、截断和超量立即终止，调用方负责删除未完成文件。 */
internal fun copyUpdateArchive(input: InputStream, output: OutputStream, expectedSize: Long, expectedHash: String,
                               checkActive: () -> Unit, progress: (Float) -> Unit) {
    check(expectedSize in 1..maxUpdateBytes) { "更新包大小不正确" }
    val digest = MessageDigest.getInstance("SHA-256")
    var written = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkActive()
        val size = input.read(buffer)
        if (size < 0) break
        written += size
        check(written <= expectedSize) { "更新包大小不正确" }
        digest.update(buffer, 0, size)
        output.write(buffer, 0, size)
        progress(written.toFloat() / expectedSize)
    }
    check(written == expectedSize && digest.digest().hex() == expectedHash) { "更新包校验失败，请重新下载" }
}

internal data class UpdateIdentity(val packageName: String, val versionCode: Long, val version: String, val signers: Set<String>)

internal fun verifyUpdateIdentity(installed: UpdateIdentity, archive: UpdateIdentity, expectedVersion: String) {
    check(archive.packageName == installed.packageName) { "更新包不属于山海" }
    check(archive.version == expectedVersion && archive.versionCode > installed.versionCode) { "更新包版本不正确或不高于当前版本" }
    check(installed.signers.isNotEmpty() && installed.signers == archive.signers) { "更新包签名不同，不能保留数据覆盖安装" }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
