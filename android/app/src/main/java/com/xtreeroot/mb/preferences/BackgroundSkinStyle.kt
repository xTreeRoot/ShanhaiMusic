package com.xtreeroot.mb.preferences

import org.json.JSONObject

internal enum class BackgroundAccent { System, Peach, Rose, Silver, Forest, Brick, Red, Orange }

/** 原图与视觉参数分离，预览期间只修改草稿，确认后再持久化。 */
internal data class BackgroundSkinStyle(
    val zoom: Float = 1f,
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
    val blur: Float = 0f,
    val accent: BackgroundAccent = BackgroundAccent.System,
    val appliesToPlayer: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("zoom", zoom.toDouble()).put("horizontal", horizontal.toDouble())
        .put("vertical", vertical.toDouble()).put("blur", blur.toDouble()).put("accent", accent.name)
        .put("appliesToPlayer", appliesToPlayer)

    companion object {
        fun parse(json: JSONObject?): BackgroundSkinStyle {
            if (json == null) return BackgroundSkinStyle()
            fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>) =
                json.optDouble(key).takeIf(Double::isFinite)?.toFloat()?.coerceIn(range.start, range.endInclusive) ?: fallback
            return BackgroundSkinStyle(number("zoom", 1f, 1f..4f), number("horizontal", 0f, -1f..1f),
                number("vertical", 0f, -1f..1f), number("blur", 0f, 0f..30f),
                BackgroundAccent.entries.firstOrNull { it.name == json.optString("accent") } ?: BackgroundAccent.System,
                json.optBoolean("appliesToPlayer"))
        }
    }
}
