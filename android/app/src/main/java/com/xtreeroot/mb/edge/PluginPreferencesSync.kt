package com.xtreeroot.mb.edge

import com.xtreeroot.mb.plugin.PluginPreferences
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 本地回调须在同一锁内合并最新文档并落盘，节点写入只发生在成功保存之后。 */
internal suspend fun NodeClient.syncPluginPreferences(binding: NodeBinding,
    load: suspend () -> PluginPreferences,
    merge: suspend (PluginPreferences) -> PluginPreferences,
    stillCurrent: () -> Boolean = { true }) {
    val key = PlatformDataKey("app", "plugins")
    val codec = PlatformDataCodec(binding.recoveryKey, binding.space)
    suspend fun check() { currentCoroutineContext().ensureActive(); if (!stillCurrent()) throw CancellationException() }
    repeat(6) {
        check()
        val record = try { read(binding, key) }
        catch (failure: NodeException) { if (failure.failure == NodeFailure.Retryable) return@repeat else throw failure }
        check()
        val remote = when (record) {
            PlatformRecord.Missing -> PluginPreferences()
            is PlatformRecord.Deleted -> throw NodeException(NodeFailure.Deleted, 410)
            is PlatformRecord.Present -> decodePreferences(codec, key, record.envelope)
        }
        val merged = merge(remote).validated()
        check()
        if (merged == remote) { val latest = load(); check(); if (latest == merged) return; return@repeat }
        val sealed = codec.seal(key, 1, JSONObject().put("value", merged.toJson()))
        check()
        try {
            if (record is PlatformRecord.Present) replace(binding, key, record.tag, sealed) else create(binding, key, sealed)
        } catch (failure: NodeException) {
            if (failure.failure == NodeFailure.Conflict || failure.failure == NodeFailure.Retryable) return@repeat else throw failure
        }
        val latest = load(); check(); if (latest == merged) return
    }
    throw NodeException(NodeFailure.Conflict, 412)
}

private fun decodePreferences(codec: PlatformDataCodec, key: PlatformDataKey, envelope: PlatformEnvelope): PluginPreferences = try {
    PluginPreferences.parse(codec.open(key, 1, envelope).getJSONObject("value"))
} catch (_: GeneralSecurityException) { throw NodeException(NodeFailure.Protocol) }
catch (_: IllegalArgumentException) { throw NodeException(NodeFailure.Protocol) }
catch (_: IllegalStateException) { throw NodeException(NodeFailure.Protocol) }
catch (_: org.json.JSONException) { throw NodeException(NodeFailure.Protocol) }
catch (_: ClassCastException) { throw NodeException(NodeFailure.Protocol) }
catch (_: java.nio.charset.CharacterCodingException) { throw NodeException(NodeFailure.Protocol) }
