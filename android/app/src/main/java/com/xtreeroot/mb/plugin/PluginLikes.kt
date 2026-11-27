package com.xtreeroot.mb.plugin

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 仅通过已有 liked 分页合同读取；不把不完整列表当作未喜欢。 */
internal suspend fun readPluginLikedIDs(invoke: suspend (Int) -> JSONObject): Set<String> {
    val ids = mutableSetOf<String>()
    var offset = 0
    repeat(512) {
        currentCoroutineContext().ensureActive()
        val page = invoke(offset)
        val rows = page.optJSONArray("tracks") ?: throw PluginException("Invalid liked list")
        val before = ids.size
        for (index in 0 until rows.length()) rows.optJSONObject(index)?.optString("id")?.takeIf(String::isNotEmpty)?.let(ids::add)
        if (!page.optBoolean("more")) return ids
        val next = page.catalogNextOffset(offset, rows.length())
        if (next == null || next <= offset || ids.size == before || ids.size > 25_000) throw PluginException("Liked list is incomplete")
        offset = next
    }
    throw PluginException("Liked list exceeds paging limit")
}
