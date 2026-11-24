package com.xtreeroot.mb.plugin

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 插件声明的独立栏目；稳定标识与展示名分离，开关在请求之前生效。 */
internal data class PluginDiscoverySection(val id: String, val title: String, val page: String? = null,
    val builtin: String? = null, val defaultEnabled: Boolean = true, val categories: List<String> = emptyList()) {
    val preferenceSection get() = DiscoveryPreferences.Section(id, defaultEnabled)
    val destination get() = page?.let { PluginCatalogRequest.Browse(it, title) }
        ?: PluginCollection.entries.firstOrNull { it.method == builtin }?.let { PluginCatalogRequest.Collection(it, title) }
}

internal fun parseDiscovery(json: JSONObject): List<PluginDiscoverySection> {
    if (json.isNull("discovery")) return emptyList()
    val array = json.getJSONArray("discovery")
    require(array.length() <= 128)
    val result = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val id = item.getString("id"); val title = item.getString("title")
        val page = if (item.isNull("page")) null else item.getString("page")
        val builtin = if (item.isNull("builtin")) null else item.getString("builtin")
        val categories = if (item.isNull("categories")) emptyList() else item.getJSONArray("categories").let { values -> List(values.length()) { values.getString(it) } }
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && ".." !in id && title.isNotBlank() && title.toByteArray().size <= 240)
        require((page == null) != (builtin == null) && (page?.let { it.isNotBlank() && it.toByteArray().size <= 4096 } ?: PluginCollection.entries.any { it.method == builtin }))
        require(categories.size <= 64 && categories.toSet().size == categories.size && categories.all { it.isNotEmpty() && it.toByteArray().size <= 4096 })
        PluginDiscoverySection(id, title, page, builtin, if (item.isNull("defaultEnabled")) true else item.getBoolean("defaultEnabled"), categories)
    }
    require(result.distinctBy { it.id }.size == result.size)
    return result
}

internal val PluginManifest.discoverySections: List<PluginDiscoverySection> get() {
    if (discovery.isNotEmpty()) return discovery
    return (listOf(PluginDiscoverySection("home", "首页", builtin = "home")) + navigation.mapNotNull { item ->
        val route = item.page ?: item.builtin ?: return@mapNotNull null
        if (item.placement == PluginNavigationPlacement.Personal || route in setOf("home", "search")) return@mapNotNull null
        if (item.page == null && PluginCollection.entries.none { it.method == item.builtin }) return@mapNotNull null
        val key = (if (item.page == null) "collection:" else "page:") + route
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        PluginDiscoverySection("nav-$digest", item.title ?: route, item.page, item.builtin)
    }).distinctBy { it.id }
}

internal suspend fun PluginCatalog.discoveryHome(manifest: PluginManifest, preferences: DiscoveryPreferences, refresh: Boolean): PluginCatalogPage {
    if (refresh) invalidate()
    val declared = manifest.discoverySections
    val selection = preferences.resolve(manifest.id, declared.map { it.preferenceSection })
    var result = PluginCatalogPage(manifest.name, "", "", emptyList())
    for (id in selection.order.filter { selection.enabled[it] == true }) {
        currentCoroutineContext().ensureActive()
        val item = declared.firstOrNull { it.id == id } ?: continue
        if (item.builtin == "home") {
            val home = load(PluginCatalogRequest.Home, refresh = refresh)
            result = home.copy(sections = result.sections + home.sections.filter { row -> result.sections.none { it.title == row.title } }); continue
        }
        val destination = item.destination ?: continue
        if (result.sections.any { it.more?.destination == destination || it.title == item.title }) continue
        val page = try { load(destination, refresh = refresh) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            result = result.copy(sections = result.sections + PluginCatalogSection(item.title, emptyList(), emptyList(), emptyList(),
                item.page?.let { PluginCatalogLink(item.title, "", destination) }, error.message ?: "加载失败", false)); continue
        }
        val section = PluginCatalogSection(item.title, page.sections.flatMap { it.tracks }.take(12), page.sections.flatMap { it.entities }.take(12),
            page.sections.flatMap { it.links }.take(12), PluginCatalogLink(item.title, "", destination), page.sections.firstOrNull()?.empty.orEmpty(), page.sections.firstOrNull()?.videos == true)
        result = result.copy(sections = result.sections + section,
            tabs = page.tabs.ifEmpty { result.tabs }, selectedTab = page.selectedTab ?: result.selectedTab)
    }
    return result
}
