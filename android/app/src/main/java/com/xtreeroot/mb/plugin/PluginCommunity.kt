package com.xtreeroot.mb.plugin

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class CommunityPlugin(val repository: String, val name: String, val description: String,
                                    val avatar: String?, val stars: Int) {
    val id: String get() = repository.lowercase(java.util.Locale.ROOT)
}
internal data class PluginCommunityPage(val plugins: List<CommunityPlugin>, val more: Boolean)

internal object PluginCommunity {
    const val catalog = "https://github.com/stars/xTreeRoot/lists/fork-mygo-%E5%B1%B1%E6%B5%B7-%E8%87%AA%E8%A1%8C%E7%BB%B4%E6%8A%A4%E4%BB%93%E5%BA%93-%E5%A4%9A%E5%B9%B3%E5%8F%B0"

    fun page(catalog: List<CommunityPlugin>, query: String, page: Int): PluginCommunityPage {
        val search = query.trim().lowercase(java.util.Locale.ROOT)
        val filtered = catalog.filter { search.isEmpty() || "${it.name}\n${it.repository}\n${it.description}".lowercase(java.util.Locale.ROOT).contains(search) }
        val start = minOf((page.coerceAtLeast(1) - 1) * 30, filtered.size)
        val end = minOf(start + 30, filtered.size)
        return PluginCommunityPage(filtered.subList(start, end), end < filtered.size)
    }

    fun repository(repository: String, metadata: JSONObject?): CommunityPlugin {
        val repo = PluginRepository.parse(repository)
        val raw = repo.substringAfter('/')
        return CommunityPlugin(repo, raw.replace(Regex("(?i)^mygo-music-(plugin-)?"), "").ifBlank { raw },
            metadata?.optString("description")?.takeUnless { it.isBlank() || it == "null" } ?: repo,
            metadata?.optJSONObject("owner")?.optString("avatar_url")?.takeIf(String::isNotBlank)
                ?: "https://github.com/${repo.substringBefore('/')}.png?size=120", metadata?.optInt("stargazers_count") ?: 0)
    }
}

internal data class PluginCommunityState(val plugins: List<CommunityPlugin> = emptyList(), val query: String = "",
                                        val page: Int = 0, val loading: Boolean = false, val more: Boolean = true,
                                        val error: String? = null)

/** 分页和搜索共享请求代次，旧查询不能覆盖新查询；失败保留已读页面。 */
internal class PluginCommunityPager(private val scope: CoroutineScope,
                                    private val fetch: suspend (String, Int) -> PluginCommunityPage) {
    private val mutable = MutableStateFlow(PluginCommunityState())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var job: Job? = null

    fun search(query: String) {
        generation++; job?.cancel()
        mutable.value = PluginCommunityState(query = query.trim())
        loadMore()
    }

    fun loadMore() {
        val current = mutable.value
        if (current.loading || !current.more) return
        val gen = generation
        mutable.value = current.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val result = fetch(current.query, current.page + 1)
                if (gen != generation) return@launch
                mutable.value = current.copy(plugins = (current.plugins + result.plugins).distinctBy { it.id },
                    page = current.page + 1, more = result.more && result.plugins.isNotEmpty())
            } catch (cancelled: CancellationException) {
                if (gen == generation) mutable.value = current
                throw cancelled
            } catch (error: Exception) {
                if (gen == generation) mutable.value = current.copy(error = error.message ?: "Cannot load plugins")
            }
        }
    }
}

internal fun InstalledPlugin.iconAddress(): String? {
    val icon = manifest.icon
    if (!icon.isNullOrBlank() && !icon.startsWith('/') && '\\' !in icon && '\u0000' !in icon && ".." !in icon.split('/')) {
        val file = File(directory, icon).canonicalFile
        if (file.toPath().startsWith(directory.canonicalFile.toPath()) && file != directory.canonicalFile) return file.toURI().toString()
    }
    return repository?.let { "https://github.com/${PluginRepository.parse(it).substringBefore('/')}.png?size=120" }
}
