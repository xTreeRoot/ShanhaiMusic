package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal class PluginSignInDiagnostics(
    private val directory: File,
    private val operation: Operation,
    private val pluginVersion: String?,
) {
    enum class Operation { signIn, account, signOut, clearLoginCache, signOutAndClearCache }
    private enum class Outcome { completed, accountReturned, noAccount, invalidAccount, failed, cancelled }

    private val started = System.currentTimeMillis()
    private val storage = JSONObject().put("hits", 0).put("misses", 0).put("writes", 0).put("removals", 0).put("failures", 0)
    private val entries = ArrayDeque<JSONObject>(32)
    private var finished = false

    fun recordStorage(operation: String, value: Any? = null) {
        val field = when (operation) {
            "storage.get" -> if (value === JSONObject.NULL) "misses" else "hits"
            "storage.set" -> "writes"
            "storage.remove" -> "removals"
            else -> return
        }.let { if (value == null) "failures" else it }
        storage.put(field, storage.getInt(field) + 1)
    }

    fun record(kind: String, started: Long, address: URI?, request: PluginFetchRequest?,
               response: PluginFetchResponse?, failure: Exception?) {
        if (finished) return
        val entry = JSONObject().put("kind", kind).put("started", started)
            .put("durationMilliseconds", (System.currentTimeMillis() - started).coerceAtLeast(0))
        (request?.url ?: address)?.let {
            val hash = MessageDigest.getInstance("SHA-256").digest(it.path.orEmpty().encodeToByteArray()).toHexString()
            entry.put("host", it.host?.take(253)).put("pathHash", hash)
        }
        request?.let {
            entry.put("cookieHeaderPresent", it.headers.any { (name, value) -> name.equals("cookie", true) && value.isNotEmpty() })
            entry.put("authorizationHeaderPresent", it.headers.any { (name, value) -> name.equals("authorization", true) && value.isNotEmpty() })
        }
        response?.let {
            entry.put("status", it.status).put("setCookieCount", it.cookies.size)
            val codes = serviceCodes(it.bytes)
            if (codes.isNotEmpty()) entry.put("serviceCodes", JSONArray(codes))
        }
        failure?.let { entry.put("failureType", (it.cause ?: it).javaClass.simpleName) }
        if (entries.size == 32) entries.removeFirst()
        entries.addLast(entry)
    }

    fun finish(result: Any? = null, failure: Throwable? = null) {
        finished = true
        val outcome = when {
            failure is CancellationException -> Outcome.cancelled
            failure != null -> Outcome.failed
            operation != Operation.account -> Outcome.completed
            result === JSONObject.NULL -> Outcome.noAccount
            result is JSONObject && result.opt("id") is String && result.opt("name") is String -> Outcome.accountReturned
            else -> Outcome.invalidAccount
        }
        val report = JSONObject().put("operation", operation.name).put("pluginVersion", pluginVersion?.take(64))
            .put("started", started).put("finished", System.currentTimeMillis())
            .put("completed", failure == null).put("outcome", outcome.name).put("storage", storage)
            .put("entries", JSONArray(entries))
        failure?.let { report.put("failure", JSONObject().put("type", it.javaClass.simpleName)) }
        synchronized(historyLock) {
            if (operation == Operation.signIn) {
                runCatching { writeAtomic(File(directory, "sign-in-diagnostics.json"), report.toString()) }
            }
            runCatching {
                val file = File(directory, "account-diagnostics.json")
                val previous = readHistory(file)
                val history = JSONArray()
                for (index in (previous.length() - historyLimit + 1).coerceAtLeast(0) until previous.length()) {
                    previous.optJSONObject(index)?.let { history.put(it) }
                }
                writeAtomic(file, history.put(report).toString())
            }
        }
    }

    private fun readHistory(file: File): JSONArray = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size !in 1..historyByteLimit.toLong()) return@use JSONArray()
            val bytes = ByteArray(size.toInt())
            input.readFully(bytes)
            JSONArray(bytes.decodeToString())
        }
    }.getOrElse { JSONArray() }

    private fun serviceCodes(bytes: ByteArray): List<Int> {
        if (bytes.size > 2 * 1024 * 1024) return emptyList()
        val body = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return emptyList()
        val status = body.optJSONObject("status_info")
        return listOf(body.opt("code"), body.opt("status_code"), body.opt("error_code"),
            status?.opt("status_code"), status?.opt("code"), body.optJSONObject("data")?.opt("error_code"))
            .mapNotNull { value ->
                when (value) {
                    is Int -> value
                    is Long -> value.toInt().takeIf { it.toLong() == value }
                    is String -> value.toIntOrNull()
                    else -> null
                }
            }.distinct().sorted()
    }

    companion object {
        private val historyLock = Any()
        private const val historyLimit = 24
        private const val historyByteLimit = 512 * 1024
    }
}
