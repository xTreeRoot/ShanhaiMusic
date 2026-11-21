package com.xtreeroot.mb.music

/** 插件返回的真实画质选项；仅保存标识，不保存会过期的媒体地址。 */
data class VideoQualityOption(val id: String, val label: String) {
    companion object {
        fun valid(id: String) = id.matches(Regex("[A-Za-z0-9._-]{1,64}"))
    }
}
