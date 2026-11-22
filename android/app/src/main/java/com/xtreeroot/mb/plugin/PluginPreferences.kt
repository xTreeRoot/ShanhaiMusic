package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.edge.decodeBase64
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** 三端共享的偏好寄存器，名称空串用于恢复默认名称，逻辑时钟不依赖墙上时间。 */
internal data class PluginPreference<T>(val value: T, val clock: Long, val writer: String) {
    fun validate() { require(clock in 1..PluginPreferences.maxClock && decodeBase64(writer).size == 16) }
    fun merged(other: PluginPreference<T>): PluginPreference<T> {
        require(clock != other.clock || writer != other.writer || value == other.value)
        return if (other.clock > clock || other.clock == clock && other.writer > writer) other else this
    }
}

/** 保留未安装插件的偏好，名称、开关逐插件合并，排序独立合并。 */
internal data class PluginPreferences(val version: Int = 1, val names: Map<String, PluginPreference<String>> = emptyMap(),
    val enabled: Map<String, PluginPreference<Boolean>> = emptyMap(), val order: PluginPreference<List<String>>? = null) : PortablePreferences<PluginPreferences> {
    val isEmpty: Boolean get() = names.isEmpty() && enabled.isEmpty() && order == null
    val maxObservedClock: Long get() = (names.values.map { it.clock } + enabled.values.map { it.clock } + listOfNotNull(order?.clock)).maxOrNull() ?: 0L
    override fun validated(): PluginPreferences = apply {
        require(version == 1 && names.size <= 512 && enabled.size <= 512)
        val ids = mutableSetOf<String>()
        names.forEach { (id, entry) ->
            require(validId(id)); entry.validate()
            require(entry.value.codePointCount(0, entry.value.length) <= 60 && entry.value.encodeToByteArray().size <= 240 &&
                entry.value.none { it.isISOControl() } && validUnicode(entry.value))
            ids += id
        }
        enabled.forEach { (id, entry) -> require(validId(id)); entry.validate(); ids += id }
        order?.let { entry ->
            entry.validate(); require(entry.value.size <= 512 && entry.value.distinct().size == entry.value.size && entry.value.all(::validId))
            ids += entry.value
        }
        require(ids.size <= 512)
    }
    override fun merged(other: PluginPreferences): PluginPreferences {
        validated(); other.validated()
        fun <T> merge(left: Map<String, PluginPreference<T>>, right: Map<String, PluginPreference<T>>) =
            left.toMutableMap().apply { right.forEach { (id, entry) -> this[id] = this[id]?.merged(entry) ?: entry } }.toMap()
        return PluginPreferences(1, merge(names, other.names), merge(enabled, other.enabled),
            if (other.order == null) order else order?.merged(other.order) ?: other.order).validated()
    }
    fun withName(id: String, name: String, writer: String): PluginPreferences =
        copy(names = names + (id to PluginPreference(name, maxObservedClock + 1, writer))).validated()
    fun withEnabled(id: String, value: Boolean, writer: String): PluginPreferences =
        copy(enabled = enabled + (id to PluginPreference(value, maxObservedClock + 1, writer))).validated()
    fun withOrder(ids: List<String>, writer: String): PluginPreferences =
        copy(order = PluginPreference(ids.toList(), maxObservedClock + 1, writer)).validated()
    override fun toJson(): JSONObject {
        validated()
        fun <T> register(entry: PluginPreference<T>, value: Any) = JSONObject().put("value", value).put("clock", entry.clock).put("writer", entry.writer)
        return JSONObject().put("version", version)
            .put("names", JSONObject().apply { names.forEach { (id, entry) -> put(id, register(entry, entry.value)) } })
            .put("enabled", JSONObject().apply { enabled.forEach { (id, entry) -> put(id, register(entry, entry.value)) } })
            .apply { order?.let { put("order", register(it, JSONArray(it.value))) } }
    }
    companion object {
        const val maxClock = 9_007_199_254_740_991L
        fun newWriter(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        private fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && ".." !in id
        private fun validUnicode(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char.isHighSurrogate()) { if (index == value.length || !value[index++].isLowSurrogate()) return false }
                else if (char.isLowSurrogate()) return false
            }
            return true
        }
        fun parse(value: JSONObject): PluginPreferences {
            fun <T> parseMap(key: String, read: (Any) -> T): Map<String, PluginPreference<T>> {
                val map = value.getJSONObject(key)
                require(map.length() <= 512)
                return map.keys().asSequence().associateWith { id ->
                    val entry = map.getJSONObject(id)
                    PluginPreference(read(entry.get("value")), strictLong(entry.get("clock")), entry.get("writer") as String)
                }
            }
            val order = if (!value.has("order") || value.isNull("order")) null else value.getJSONObject("order").let { entry ->
                val ids = entry.getJSONArray("value"); require(ids.length() <= 512)
                PluginPreference((0 until ids.length()).map { ids.get(it) as String }, strictLong(entry.get("clock")), entry.get("writer") as String)
            }
            require(strictLong(value.get("version")) == 1L)
            return PluginPreferences(1, parseMap("names") { it as String }, parseMap("enabled") { it as Boolean }, order).validated()
        }
        private fun strictLong(value: Any): Long = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Invalid preference clock") }
    }
}
