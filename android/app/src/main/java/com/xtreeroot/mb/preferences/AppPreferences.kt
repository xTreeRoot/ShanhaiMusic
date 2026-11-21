package com.xtreeroot.mb.preferences

import com.xtreeroot.mb.music.AudioQuality
import org.json.JSONObject

internal enum class AppAppearance { System, Light, Dark }
internal enum class AppLanguage { System, Chinese, English }
internal enum class PlayerStyle { Cover, Vinyl, Cassette }
internal enum class LyricStyle { Flow, Steps, Tilt }
internal enum class AudioNormalization { Standard, Dynamic, Off }
internal enum class PlaybackMode { Music, Video }

internal data class AppPreferences(
    val appearance: AppAppearance = AppAppearance.System,
    val language: AppLanguage = AppLanguage.System,
    val inAppVideoWindow: Boolean = true,
    val systemVideoWindow: Boolean = true,
    val backgroundOpacity: Float? = null,
    val backgroundStyle: BackgroundSkinStyle = BackgroundSkinStyle(),
    val volume: Float = 0.8f,
    val quality: AudioQuality = AudioQuality.High,
    val normalization: AudioNormalization = AudioNormalization.Standard,
    val playbackMode: PlaybackMode = PlaybackMode.Music,
    val lyricsStage: Boolean = false,
    val nowPlayingLyrics: Boolean = true,
    val automaticUpdateChecks: Boolean = true,
    val hotComments: Boolean = false,
    val playerStyle: PlayerStyle = PlayerStyle.Cover,
    val lyricStyle: LyricStyle = LyricStyle.Flow,
    val stageSparks: Boolean = false,
    val smoke: Boolean = false,
    val fog: Boolean = false,
    val growFromCover: Boolean = false,
    val proxy: ProxyPolicy = ProxyPolicy(),
    val selectedSourceId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("appearance", appearance.name).put("language", language.name)
        .put("inAppVideoWindow", inAppVideoWindow).put("systemVideoWindow", systemVideoWindow)
        .put("backgroundStyle", backgroundStyle.toJson())
        .put("backgroundOpacity", backgroundOpacity?.toDouble()).put("volume", volume.toDouble()).put("quality", quality.name).put("normalization", normalization.name)
        .put("playbackMode", playbackMode.name).put("automaticUpdateChecks", automaticUpdateChecks)
        .put("lyricsStage", lyricsStage).put("nowPlayingLyrics", nowPlayingLyrics).put("hotComments", hotComments)
        .put("playerStyle", playerStyle.name).put("lyricStyle", lyricStyle.name).put("stageSparks", stageSparks)
        .put("smoke", smoke).put("fog", fog).put("growFromCover", growFromCover)
        .put("proxyHosts", proxy.hosts.joinToString("\n")).put("githubProxyRulesApplied", true)
        .put("youtubeAccountProxyRuleApplied", true).put("selectedSourceId", selectedSourceId)

    companion object {
        fun parse(json: JSONObject): AppPreferences = AppPreferences(
            appearance = json.choice("appearance", AppAppearance.System),
            language = json.choice("language", AppLanguage.System),
            inAppVideoWindow = json.optBoolean("inAppVideoWindow", true),
            systemVideoWindow = json.optBoolean("systemVideoWindow", true),
            backgroundOpacity = json.optDouble("backgroundOpacity").takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat(),
            backgroundStyle = BackgroundSkinStyle.parse(json.optJSONObject("backgroundStyle")),
            volume = json.optDouble("volume", 0.8).takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0.8f,
            quality = json.choice("quality", AudioQuality.High),
            normalization = json.choice("normalization", AudioNormalization.Standard),
            playbackMode = json.choice("playbackMode", PlaybackMode.Music),
            selectedSourceId = (json.opt("selectedSourceId") as? String)?.takeIf(String::isNotBlank),
            lyricsStage = json.optBoolean("lyricsStage"),
            nowPlayingLyrics = json.optBoolean("nowPlayingLyrics", true),
            automaticUpdateChecks = json.optBoolean("automaticUpdateChecks", true),
            hotComments = json.optBoolean("hotComments"),
            playerStyle = json.choice("playerStyle", PlayerStyle.Cover),
            lyricStyle = json.choice("lyricStyle", LyricStyle.Flow),
            stageSparks = json.optBoolean("stageSparks"),
            smoke = json.optBoolean("smoke"),
            fog = json.optBoolean("fog"),
            growFromCover = json.optBoolean("growFromCover"),
            proxy = if (json.has("proxyHosts")) runCatching {
                val saved = ProxyPolicy.parse(json.getString("proxyHosts")).hosts
                val github = if (saved.isNotEmpty() && !json.optBoolean("githubProxyRulesApplied")) ProxyPolicy.githubHosts else emptyList()
                val youtube = if (!json.optBoolean("youtubeAccountProxyRuleApplied") && ProxyPolicy.googleTokenHost in saved)
                    listOf(ProxyPolicy.youtubeAccountHost) else emptyList()
                ProxyPolicy((saved + github + youtube).distinct())
            }.getOrDefault(ProxyPolicy()) else ProxyPolicy(),
        )

        private inline fun <reified Choice : Enum<Choice>> JSONObject.choice(key: String, fallback: Choice): Choice =
            enumValues<Choice>().firstOrNull { it.name == optString(key) } ?: fallback
    }
}
