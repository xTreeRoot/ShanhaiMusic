package com.xtreeroot.mb.edge

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal data class DeviceArchiveEntry(val name: String, val file: File)

/** 三端共用的归档格式。先校验并解压到新目录，再由业务层合并本机数据。 */
internal object DeviceArchive {
    const val maxFiles = 50_000
    const val manifestLimit = 32 * 1024 * 1024
    fun validPath(name: String): Boolean = name.isNotEmpty() && name.toByteArray().size <= 1024 &&
        name.none { it in "\\:\u0000" } && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    fun tree(root: File, prefix: String): List<DeviceArchiveEntry> {
        if (!root.exists()) return emptyList()
        require(!Files.isSymbolicLink(root.toPath()) && root.isDirectory)
        return root.walkTopDown().filter { file ->
            require(!Files.isSymbolicLink(file.toPath()))
            file != root && !file.isDirectory
        }.map { file ->
            require(file.isFile)
            DeviceArchiveEntry(prefix + "/" + file.relativeTo(root).invariantSeparatorsPath, file).also { require(validPath(it.name)) }
        }.toList()
    }

    fun validate(doc: JSONObject) {
        require(doc.getInt("version") == 1 && doc.getString("platform") in listOf("desktop", "ios", "android"))
        val plugins = doc.getJSONArray("plugins").objects()
        require(plugins.size <= 1000 && plugins.map { it.getString("id").lowercase(Locale.ROOT) }.toSet().size == plugins.size)
        plugins.forEach {
            val id = it.getString("id")
            require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}").matches(id) && ".." !in id && !id.equals("local", true))
            it.getJSONObject("settings").stringValues()
            it.getBoolean("enabled")
        }
        val tracks = doc.getJSONObject("library").optJSONArray("tracks").objects()
        require(tracks.map { it.getString("source") + ":" + it.getString("track") }.toSet().size == tracks.size)
        tracks.forEach { track ->
            require(track.getString("source").isNotEmpty() && track.getString("track").isNotEmpty())
            track.text("asset")?.let { require(validPath(it) && it.startsWith("media/")) }
        }
    }

    suspend fun write(doc: JSONObject, entries: List<DeviceArchiveEntry>, output: File) {
        validate(doc)
        val metadata = doc.toString().toByteArray()
        require(metadata.size <= manifestLimit && entries.size <= maxFiles && !output.exists())
        var total = metadata.size.toLong()
        val names = mutableSetOf("manifest.json")
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(metadata); zip.closeEntry()
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                require(validPath(entry.name) && names.add(entry.name.lowercase(Locale.ROOT)) &&
                    !Files.isSymbolicLink(entry.file.toPath()) && entry.file.isFile)
                val size = entry.file.length(); val modified = entry.file.lastModified()
                total += size; require(total <= backupArchiveLimit)
                zip.putNextEntry(ZipEntry(entry.name))
                val copied = entry.file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024); var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        zip.write(buffer, 0, n); bytes += n
                    }
                    bytes
                }
                zip.closeEntry()
                require(copied == size && entry.file.length() == size && entry.file.lastModified() == modified)
            }
        }
    }

    suspend fun extract(input: File, stage: File): JSONObject {
        require(!stage.exists())
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= maxFiles + 1)
            val names = mutableSetOf<String>(); var total = 0L
            for (entry in entries) {
                val name = entry.name.removeSuffix("/")
                require(validPath(name) && names.add(name.lowercase(Locale.ROOT)) && entry.size >= 0)
                require(entry.size <= backupArchiveLimit - total); total += entry.size
            }
            val metadata = zip.getEntry("manifest.json") ?: error("Missing device manifest")
            require(metadata.size in 1..manifestLimit.toLong())
            val doc = zip.getInputStream(metadata).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            validate(doc)
            for (plugin in doc.getJSONArray("plugins").objects()) require("plugins/${plugin.getString("id")}/package.zip".lowercase(Locale.ROOT) in names)
            for (track in doc.getJSONObject("library").optJSONArray("tracks").objects()) track.text("asset")?.let { require(it.lowercase(Locale.ROOT) in names) }
            require(stage.mkdirs())
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val file = File(stage, entry.name)
                require(file.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                if (entry.isDirectory) { require(file.mkdirs() || file.isDirectory); continue }
                require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                val crc = CRC32(); var bytes = 0L
                zip.getInputStream(entry).use { source -> file.outputStream().use { target ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer); if (n < 0) break
                        bytes += n; require(bytes <= entry.size)
                        crc.update(buffer, 0, n); target.write(buffer, 0, n)
                    }
                } }
                require(bytes == entry.size && crc.value == entry.crc)
            }
            return doc
        }
    }

    suspend fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map(::getString)
internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
internal fun JSONObject.stringValues(): Map<String, String> = keys().asSequence().associateWith { get(it) as String }
