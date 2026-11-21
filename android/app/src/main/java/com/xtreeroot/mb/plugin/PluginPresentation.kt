package com.xtreeroot.mb.plugin

import org.json.JSONArray
import org.json.JSONObject

/** 本地名称、顺序独立于远程清单、插件代码和账号目录。 */
internal data class PluginPresentation(val names: Map<String, String> = emptyMap(), val order: List<String> = emptyList()) {
    fun toJson() = JSONObject().put("names", JSONObject(names)).put("order", JSONArray(order)).toString()
    fun renamed(id: String, value: String): PluginPresentation {
        val name = value.trim()
        require(name.codePointCount(0, name.length) <= 60) { "本地名称最多 60 个字符" }
        return copy(names = if (name.isEmpty()) names - id else names + (id to name))
    }
    companion object {
        fun read(text: String): PluginPresentation {
            val json = JSONObject(text)
            val names = json.optJSONObject("names")
            val order = json.optJSONArray("order")
            return PluginPresentation(names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
                order?.let { list -> (0 until list.length()).map(list::getString).distinct() }.orEmpty())
        }
    }
}

/** 移动已安装项时保留暂未安装项的占位顺序。 */
internal fun preservingMissingPluginOrder(previous: List<String>, visible: List<String>): List<String> {
    val present = visible.toSet()
    val remaining = visible.iterator()
    return previous.map { if (it in present) remaining.next() else it } + remaining.asSequence().toList()
}
