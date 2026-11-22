package com.xtreeroot.mb.plugin

import android.annotation.SuppressLint
import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal data class PluginPrompt(val qrUrl: String?, val message: String)

internal class PluginHostState(rootDirectory: File, dataDirectory: File, settings: Map<String, String>, language: String) : QuickJsHost {
    private val root = rootDirectory.canonicalFile
    private val data = dataDirectory.canonicalFile
    private val storageFile = File(data, "storage.json")
    private var storage = JSONObject()
    var settings = settings.toMap()
    var language = language
    var onPrompt: ((PluginPrompt) -> Unit)? = null

    init {
        require(!root.toPath().startsWith(data.toPath()) && !data.toPath().startsWith(root.toPath()))
    }

    fun loadStorage() {
        if (storageFile.exists()) storage = JSONObject(storageFile.readPluginText(storageLimit))
    }

    /** 仅重置本插件的宿主存储；设置、代码、资料库与下载都在各自目录中保留。 */
    fun clearLoginStorage() {
        writeAtomic(storageFile, "{}")
        storage = JSONObject()
        onPrompt = null
    }

    fun module(path: String): String {
        val file = pluginFile(path)
        if (file.extension != "js") throw PluginException("Invalid plugin module")
        return file.readPluginText(moduleLimit).also { if ('\u0000' in it) throw PluginException("Invalid plugin module") }
    }

    fun headers(address: URI, manifest: PluginManifest, kind: String): Map<String, String> {
        val hostname = address.host ?: return emptyMap()
        if (!address.isHttp() || !manifest.reaches(hostname)) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (rule in manifest.requestHeaders) {
            if (rule.kinds.isNotEmpty() && kind !in rule.kinds) continue
            if (rule.hosts.none { pattern ->
                    val setting = settingHost.matchEntire(pattern)
                    if (setting == null) hostMatches(pattern, hostname) else {
                        val server = settings[setting.groupValues[1]].orEmpty()
                        val target = runCatching { uri(if ("://" in server) server else "http://$server") }.getOrNull()
                        target != null && target.isHttp() && hostname.equals(target.host, ignoreCase = true)
                    }
                }) continue
            for ((name, template) in rule.headers) {
                if (result.keys.any { it.equals(name, true) }) continue
                var missing = false
                val expanded = headerTemplate.replace(template) { token ->
                    val key = token.groupValues[2]
                    val value = if (token.groupValues[1] == "setting") settings[key]
                    else storage.opt(key)?.takeUnless { it === JSONObject.NULL }?.toString()
                    if (value.isNullOrEmpty()) missing = true
                    value.orEmpty()
                }
                if (!missing && expanded.none { it == '\r' || it == '\n' }) result[name] = expanded
            }
        }
        return result
    }

    override fun call(operation: ByteArray, arguments: ByteArray): ByteArray {
        val response = try {
            JSONObject().put("value", call(operation.decodeToString(), JSONArray(arguments.decodeToString())))
        } catch (error: PluginException) {
            JSONObject().put("error", error.message)
        } catch (_: Exception) {
            JSONObject().put("error", "Invalid plugin host operation")
        }
        return response.toString().encodeToByteArray()
    }

    @SuppressLint("GetInstance")
    private fun call(operation: String, values: JSONArray): Any {
        val first = values.opt(0) as? String ?: ""
        return when (operation) {
            "module" -> module(first)
            "storage.get" -> storage.opt(first) ?: JSONObject.NULL
            "storage.set", "storage.remove" -> {
                val updated = JSONObject(storage.toString())
                if (operation == "storage.remove") updated.remove(first)
                else updated.put(first, values.opt(1) ?: JSONObject.NULL)
                val text = updated.toString()
                if (text.encodeToByteArray().size > storageLimit) throw PluginException("Plugin storage limit exceeded")
                writeAtomic(storageFile, text)
                storage = updated
                JSONObject.NULL
            }
            "setting" -> settings[first].orEmpty()
            "language" -> language
            "t" -> {
                val file = pluginFile("locales/${if (language == "zh-CN") "zh-CN" else "en"}.json")
                if (file.isFile) (JSONObject(file.readPluginText(moduleLimit)).opt(first) as? String ?: first) else first
            }
            "md5" -> MessageDigest.getInstance("MD5").digest(first.encodeToByteArray()).toHexString()
            "aes" -> {
                val key = first.encodeToByteArray()
                if (key.size !in setOf(16, 24, 32)) throw PluginException("Invalid AES key length")
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.doFinal((values.opt(1) as? String ?: "").encodeToByteArray()).toHexString()
            }
            "prompt" -> {
                val prompt = values.optJSONObject(0) ?: throw PluginException("Invalid sign-in prompt")
                val qr = if (prompt.isNull("qr")) null else prompt.opt("qr") as? String ?: throw PluginException("Invalid sign-in prompt")
                val url = qr?.takeIf(String::isNotEmpty)
                if (url != null && !uri(url).isHttp()) throw PluginException("Invalid sign-in prompt")
                onPrompt?.invoke(PluginPrompt(url, prompt.opt("text") as? String ?: ""))
                JSONObject.NULL
            }
            "url" -> {
                val url = (values.opt(1) as? String)?.let { uri(it).resolve(uri(first)) } ?: uri(first)
                val scheme = url.scheme ?: throw PluginException("Invalid URL")
                val host = url.host.orEmpty()
                JSONObject().put("href", url.toASCIIString()).put("protocol", "$scheme:")
                    .put("host", host + if (url.port == -1) "" else ":${url.port}")
                    .put("hostname", host).put("pathname", url.rawPath.orEmpty())
                    .put("search", url.rawQuery?.let { "?$it" }.orEmpty())
                    .put("hash", url.rawFragment?.let { "#$it" }.orEmpty())
            }
            "base64.encode" -> {
                if (first.any { it.code > 255 }) throw PluginException("Invalid binary string")
                Base64.getEncoder().encodeToString(first.toByteArray(Charsets.ISO_8859_1))
            }
            "base64.decode" -> Base64.getDecoder().decode(first).toString(Charsets.ISO_8859_1)
            "console" -> JSONObject.NULL
            else -> throw PluginException("Host operation is unavailable on Android")
        }
    }

    private fun pluginFile(path: String): File {
        if (File(path).isAbsolute || '\u0000' in path) throw PluginException("Module outside plugin directory")
        val file = File(root, path).canonicalFile
        if (file == root || !file.toPath().startsWith(root.toPath())) throw PluginException("Module outside plugin directory")
        return file
    }

    companion object {
        private const val moduleLimit = 4 * 1024 * 1024
        private const val storageLimit = 16 * 1024 * 1024
        private val headerTemplate = Regex("\\{(setting|storage):([^{}]+)\\}")
    }
}

internal fun uri(value: String): URI = try { URI(value) } catch (_: Exception) { throw PluginException("Invalid URL") }

internal fun URI.isHttp(): Boolean = (scheme.equals("https", true) || scheme.equals("http", true)) &&
    !host.isNullOrEmpty() && rawUserInfo == null

private fun File.readPluginText(limit: Int): String {
    if (!isFile || length() > limit) throw PluginException("Invalid plugin file")
    val content = readBytes()
    if (content.size > limit) throw PluginException("Invalid plugin file")
    return content.decodeToString(throwOnInvalidSequence = true)
}
