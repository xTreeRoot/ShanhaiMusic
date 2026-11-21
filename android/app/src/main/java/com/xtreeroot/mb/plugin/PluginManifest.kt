package com.xtreeroot.mb.plugin

import org.json.JSONObject

internal class PluginException(message: String, val code: String? = null, cause: Throwable? = null) : Exception(message, cause)

internal data class PluginManifest(
    val id: String,
    val name: String,
    val entry: String,
    val network: List<String>,
    val requestHeaders: List<PluginHeaderRule>,
    val version: String? = null,
    val minAppVersion: String? = null,
    val settings: List<PluginSetting> = emptyList(),
    val navigation: List<PluginNavigation> = emptyList(),
    val signInMethods: List<com.xtreeroot.mb.music.SignInMethod> = emptyList(),
) {
    fun reaches(host: String): Boolean = host.isNotEmpty() && network.any { it == "*" || hostMatches(it, host) }

    companion object {
        private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
        private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val hostVersion = listOf(0, 1, 28)

        fun parse(json: JSONObject): PluginManifest {
            val id = json.requiredText("id")
            if (!idPattern.matches(id) || ".." in id) throw PluginException("Invalid plugin ID")
            val entry = json.optionalText("main") ?: "index.js"
            if (entry.startsWith('/') || '\\' in entry || '\u0000' in entry || ".." in entry.split('/') || !entry.endsWith(".js")) {
                throw PluginException("Invalid entry path")
            }
            val minAppVersion = json.optionalText("minAppVersion")
            minAppVersion?.let { version ->
                if (!versionPattern.matches(version)) throw PluginException("Invalid compatibility version")
                val required = version.split('.').map { it.toIntOrNull() ?: throw PluginException("Invalid compatibility version") }
                val difference = required.zip(hostVersion).firstOrNull { (needed, available) -> needed != available }
                if (difference != null && difference.first > difference.second) throw PluginException("Requires host $version")
            }
            val permissions = if (json.isNull("permissions")) null else json.getJSONObject("permissions")
            val playback = json.optionalText("playback")
            if ((playback != null && playback != "stream") || !permissions.textList("process").isEmpty()) {
                throw PluginException("Plugin requires unsupported desktop host capabilities")
            }
            val settings = if (json.isNull("settings")) null else json.getJSONArray("settings")
            val values = List(settings?.length() ?: 0) { index ->
                val setting = checkNotNull(settings).getJSONObject(index)
                val options = if (setting.isNull("options")) null else setting.getJSONArray("options")
                PluginSetting(setting.requiredText("key"), setting.requiredText("label"), setting.optionalText("type"),
                    setting.optionalText("help"), setting.optionalText("link"), List(options?.length() ?: 0) { option ->
                        val value = checkNotNull(options).getJSONObject(option)
                        PluginSetting.Option(value.requiredText("value"), value.requiredText("label"))
                    })
            }
            if (values.any { it.key.isBlank() } || values.distinctBy(PluginSetting::key).size != values.size) throw PluginException("Invalid plugin settings")
            val navigation = if (json.isNull("navigation")) null else json.getJSONArray("navigation")
            val destinations = List(navigation?.length() ?: 0) { index ->
                val item = checkNotNull(navigation).getJSONObject(index)
                val placement = item.optionalText("placement")?.let { value ->
                    PluginNavigationPlacement.entries.find { it.key == value } ?: throw PluginException("Invalid navigation placement")
                } ?: PluginNavigationPlacement.Discovery
                PluginNavigation(item.optionalText("title"), item.optionalText("page"), item.optionalText("builtin"), item.optionalText("icon"), placement)
            }
            val methods = json.optJSONArray("signInMethods")
            val signInMethods = List(methods?.length() ?: 0) { index ->
                val method = checkNotNull(methods).getJSONObject(index)
                com.xtreeroot.mb.music.SignInMethod(method.requiredText("id"), method.requiredText("label"))
            }
            if (signInMethods.size > 16 || signInMethods.any { !idPattern.matches(it.id) || it.label.isBlank() } ||
                signInMethods.distinctBy { it.id }.size != signInMethods.size) throw PluginException("Invalid sign-in methods")
            val manifest = PluginManifest(id, json.optionalText("name") ?: id, entry, permissions.textList("network"), emptyList(),
                json.optionalText("version"), minAppVersion, values, destinations, signInMethods)
            val rules = if (json.isNull("requestHeaders")) null else json.getJSONArray("requestHeaders")
            return manifest.copy(requestHeaders = List(rules?.length() ?: 0) { index ->
                val rule = checkNotNull(rules).getJSONObject(index)
                val hosts = rule.textList("hosts")
                val kinds = rule.textList("kinds")
                val fields = rule.getJSONObject("headers")
                if (hosts.isEmpty() || fields.length() == 0 || kinds.any { it != "image" && it != "audio" }) {
                    throw PluginException("Invalid media header rule")
                }
                for (host in hosts) {
                    if (settingHost.matches(host)) continue
                    if (!headerHost.matches(host) || !manifest.reaches(host)) throw PluginException("Header host outside plugin network permissions")
                }
                val headers = fields.keys().asSequence().associateWith { key ->
                    if (!allowedPluginHeader(key)) throw PluginException("Invalid media header name")
                    fields.requiredText(key)
                }
                PluginHeaderRule(hosts, kinds.toSet(), headers)
            })
        }

        private fun JSONObject.requiredText(key: String): String = optionalText(key) ?: throw PluginException("Missing $key")
        private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else
            opt(key) as? String ?: throw PluginException("Invalid $key")

        private fun JSONObject?.textList(key: String): List<String> {
            if (this == null || isNull(key)) return emptyList()
            val values = getJSONArray(key)
            return List(values.length()) { index -> values.get(index) as? String ?: throw PluginException("Invalid $key") }
        }
    }
}

internal data class PluginHeaderRule(val hosts: List<String>, val kinds: Set<String>, val headers: Map<String, String>)

internal enum class PluginNavigationPlacement(val key: String) { Discovery("discovery"), Personal("personal") }

internal data class PluginNavigation(val title: String?, val page: String?, val builtin: String?, val icon: String?,
                                    val placement: PluginNavigationPlacement = PluginNavigationPlacement.Discovery)

internal data class PluginSetting(val key: String, val label: String, val type: String?, val help: String?,
                                  val link: String?, val options: List<Option>) {
    data class Option(val value: String, val label: String)
}

internal val settingHost = Regex("\\{setting:([^{}]+)\\}")
private val headerHost = Regex("(?:\\*\\.)?[A-Za-z0-9.-]+")
private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val protectedHeaders = setOf("host", "content-length", "transfer-encoding", "proxy-authorization")

internal fun allowedPluginHeader(name: String): Boolean = headerName.matches(name) && name.lowercase() !in protectedHeaders

internal fun hostMatches(pattern: String, host: String): Boolean = host.equals(pattern, ignoreCase = true) ||
    (pattern.startsWith("*.") && host.endsWith(pattern.drop(1), ignoreCase = true))
