package com.xtreeroot.mb.edge

import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.AppAppearance
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.plugin.PluginSetting
import com.xtreeroot.mb.music.AudioQuality
import org.json.JSONObject

internal data class SettingsDevice(val key: String, val name: String, val updated: Long, val current: Boolean)
internal data class BackupPluginChoice(val id: String, val name: String)
internal data class DeviceSettings(val app: Map<String, String>, val plugins: Map<String, Map<String, String>>, val accounts: Map<String, String>,
                                  val backup: DeviceBackupReference? = null, val previousBackup: DeviceBackupReference? = null) {
    fun encode() = JSONObject().put("value", JSONObject().put("version", 1).put("app", JSONObject(app))
        .put("plugins", JSONObject(plugins.mapValues { JSONObject(it.value) })).put("accounts", JSONObject(accounts)).apply {
            backup?.let { put("backup",it.encode()) };previousBackup?.let { put("previous_backup",it.encode()) }
        })
    companion object {
        fun decode(payload: JSONObject): DeviceSettings {
            val doc = payload.getJSONObject("value")
            require(doc.getInt("version") == 1)
            fun values(p: JSONObject) = p.keys().asSequence().associateWith { p.get(it) as String }
            val plugins = doc.getJSONObject("plugins")
            return DeviceSettings(values(doc.getJSONObject("app")), plugins.keys().asSequence().associateWith {
                values(plugins.getJSONObject(it)) }, values(doc.getJSONObject("accounts")),
                doc.optJSONObject("backup")?.let(DeviceBackupReference::decode),doc.optJSONObject("previous_backup")?.let(DeviceBackupReference::decode))
        }
    }
}

internal fun PluginSetting.portable(value: String) = when (type) {
    "boolean" -> value == "true" || value == "false"
    "select" -> value.isNotEmpty() && options.any { it.value == value }
    else -> false
}

private val flags = listOf("smoke", "fog", "growFromCover", "lyricsStage", "hotComments", "stageSparks",
    "inAppVideoWindow", "systemVideoWindow", "pauseOtherSourcesOnSwitch", "automaticUpdateChecks", "nowPlayingLyrics")
internal fun AppPreferences.deviceSettings(): Map<String, String> {
    val current = toJson()
    return flags.associateWith { current.getBoolean(it).toString() } + mapOf("appearance" to appearance.name.lowercase(),
        "quality" to quality.name.lowercase(), "language" to when(language) {AppLanguage.Chinese -> "zh-CN"; AppLanguage.English -> "en"; else -> "system"})
}
internal fun AppPreferences.restoreSettings(values: Map<String, String>): Pair<AppPreferences, Int> {
    var result = this
    var count = 0
    val bools = toJson()
    for ((k,v) in values) {
        when (k) {
            "appearance" -> result = result.copy(appearance = AppAppearance.entries.firstOrNull { it.name.equals(v, true) } ?: continue)
            "quality" -> result = result.copy(quality = AudioQuality.entries.firstOrNull { it.name.equals(v, true) } ?: continue)
            "language" -> result = result.copy(language = when(v) {"system" -> AppLanguage.System; "zh-CN" -> AppLanguage.Chinese; "en" -> AppLanguage.English; else -> continue})
            in flags -> bools.put(k,v.toBooleanStrictOrNull() ?: continue)
            else -> continue
        }
        count++
    }
    return result.copy(smoke=bools.getBoolean("smoke"),fog=bools.getBoolean("fog"),growFromCover=bools.getBoolean("growFromCover"),
        lyricsStage=bools.getBoolean("lyricsStage"),hotComments=bools.getBoolean("hotComments"),stageSparks=bools.getBoolean("stageSparks"),
        inAppVideoWindow=bools.getBoolean("inAppVideoWindow"),systemVideoWindow=bools.getBoolean("systemVideoWindow"),
        pauseOtherSourcesOnSwitch=bools.getBoolean("pauseOtherSourcesOnSwitch"),automaticUpdateChecks=bools.getBoolean("automaticUpdateChecks"),
        nowPlayingLyrics=bools.getBoolean("nowPlayingLyrics")) to count
}
internal suspend fun NodeClient.saveSettingsSnapshot(node: NodeBinding, snapshot: DeviceSettings) {
    val key = PlatformDataKey("device-settings", node.device.toByteArray().joinToString("") { "%02x".format(it) })
    val envelope = PlatformDataCodec(node.recoveryKey, node.space).seal(key, 1, snapshot.encode())
    when (val existing = read(node, key)) {
        PlatformRecord.Missing -> create(node, key, envelope)
        is PlatformRecord.Present -> replace(node,key,existing.tag,envelope)
        is PlatformRecord.Deleted -> throw NodeException(NodeFailure.Deleted)
    }
}
internal suspend fun NodeClient.readSettingsSnapshot(node: NodeBinding, device: SettingsDevice): DeviceSettings {
    val key = PlatformDataKey("device-settings",device.key)
    val record = read(node,key) as? PlatformRecord.Present ?: throw NodeException(NodeFailure.Deleted)
    return DeviceSettings.decode(PlatformDataCodec(node.recoveryKey,node.space).open(key,1,record.envelope))
}
