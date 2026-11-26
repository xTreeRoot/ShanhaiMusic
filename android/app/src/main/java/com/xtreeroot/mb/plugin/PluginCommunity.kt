package com.xtreeroot.mb.plugin

import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal data class CommunityPlugin(val repository: String, val name: String, val description: String,
                                    val avatar: String?, val stars: Int) {
    val id: String get() = repository.lowercase(java.util.Locale.ROOT)
}
internal data class PluginCommunityPage(val plugins: List<CommunityPlugin>, val more: Boolean)

internal object PluginCommunity {
    fun address(query: String, page: Int): URI = "https://api.github.com/search/repositories".toHttpUrl().newBuilder()
        .addQueryParameter("q", "${query.trim()} topic:mygo-music-plugin archived:false".trim())
        .addQueryParameter("sort", "stars").addQueryParameter("per_page", "30")
        .addQueryParameter("page", page.coerceAtLeast(1).toString()).build().toUri()

    fun decode(json: JSONObject, page: Int): PluginCommunityPage {
        val items = json.getJSONArray("items")
        val plugins = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val name = item.getString("name").replace(Regex("(?i)^mygo-music-(plugin-)?"), "")
            CommunityPlugin(PluginRepository.parse(item.getString("full_name")), name,
                if (item.isNull("description")) "" else item.getString("description"),
                item.optJSONObject("owner")?.optString("avatar_url")?.takeIf(String::isNotBlank), item.optInt("stargazers_count"))
        }
        return PluginCommunityPage(plugins, plugins.size == 30 && page.coerceAtLeast(1) * 30 < minOf(json.getInt("total_count"), 1000))
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
