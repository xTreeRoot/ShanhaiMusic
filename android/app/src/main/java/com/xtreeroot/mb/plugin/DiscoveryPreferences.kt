package com.xtreeroot.mb.plugin

import org.json.JSONObject

/** 独立的 app/discovery 文档；复用字段寄存器，避免旧版本插件偏好写回时擦除栏目配置。 */
internal data class DiscoveryPreferences(val version: Int = 1, val plugins: Map<String, PluginPreferences> = emptyMap()) : PortablePreferences<DiscoveryPreferences> {
    val isEmpty get() = plugins.isEmpty()
    override fun validated(): DiscoveryPreferences = apply {
        require(version == 1 && plugins.size <= 512 && plugins.values.sumOf { it.enabled.size } <= 4096)
        plugins.forEach { (id, prefs) ->
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && ".." !in id)
            require(prefs.names.isEmpty() && prefs.enabled.size <= 128 && (prefs.order?.value?.size ?: 0) <= 128)
            prefs.validated()
        }
    }
    override fun merged(other: DiscoveryPreferences): DiscoveryPreferences {
        validated(); other.validated()
        val next = plugins.toMutableMap()
        other.plugins.forEach { (id, prefs) -> next[id] = (next[id] ?: PluginPreferences()).merged(prefs) }
        return copy(plugins = next.toMap()).validated()
    }
    data class Section(val id: String, val defaultEnabled: Boolean)
    data class Selection(val order: List<String>, val enabled: Map<String, Boolean>)
    fun resolve(plugin: String, sections: List<Section>): Selection {
        val prefs = plugins[plugin] ?: PluginPreferences()
        val configured = prefs.order != null
        val ids = sections.map { it.id }.toSet()
        val ordered = prefs.order?.value.orEmpty().filter { it in ids }
        return Selection(ordered + sections.map { it.id }.filter { it !in ordered },
            sections.associate { it.id to (prefs.enabled[it.id]?.value ?: (it.defaultEnabled && !configured)) })
    }
    /** 仅把实际编辑的字段写入最新文档，不覆盖打开编辑器期间到达的远端修改。 */
    fun edited(plugin: String, before: Selection, draft: Selection, writer: String): DiscoveryPreferences {
        validated()
        require(before.enabled.keys == draft.enabled.keys && draft.order.size == draft.enabled.size && draft.order.toSet() == draft.enabled.keys)
        var prefs = plugins[plugin] ?: PluginPreferences()
        val initialize = prefs.order == null
        draft.order.forEach { id -> if (initialize || before.enabled[id] != draft.enabled[id]) prefs = prefs.withEnabled(id, draft.enabled[id] == true, writer) }
        if (initialize || before.order != draft.order) prefs = prefs.withOrder(draft.order + prefs.order?.value.orEmpty().filter { it !in draft.order }, writer)
        return copy(plugins = plugins + (plugin to prefs)).validated()
    }
    override fun toJson(): JSONObject = JSONObject().put("version", version).put("plugins", JSONObject().apply {
        validated(); plugins.forEach { (id, prefs) -> put(id, prefs.toJson()) }
    })
    companion object {
        fun parse(json: JSONObject): DiscoveryPreferences {
            require(json.get("version") == 1)
            val plugins = json.getJSONObject("plugins")
            require(plugins.length() <= 512)
            return DiscoveryPreferences(plugins = plugins.keys().asSequence().associateWith { PluginPreferences.parse(plugins.getJSONObject(it)) }).validated()
        }
    }
}

internal interface PortablePreferences<T> {
    fun validated(): T
    fun merged(other: T): T
    fun toJson(): JSONObject
}
