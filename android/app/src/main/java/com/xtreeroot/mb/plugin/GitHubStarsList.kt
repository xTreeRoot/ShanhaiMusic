package com.xtreeroot.mb.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object GitHubStarsList {
    private val container = Regex("""<div\s+[^>]*\bid\s*=\s*["']user-list-repositories["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val div = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val headingLink = Regex("""<h2\s+[^>]*\bclass\s*=\s*["'](?:[^"']*\s)?h3(?:\s[^"']*)?["'][^>]*>\s*<a\s+([^>]+)>""",
        RegexOption.IGNORE_CASE)
    private val links = Regex("""<a\s+([^>]+)>""", RegexOption.IGNORE_CASE)
    private val nextLink = Regex("""\brel\s*=\s*["']next["']""", RegexOption.IGNORE_CASE)
    private val href = Regex("""(?:^|\s)href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""", RegexOption.IGNORE_CASE)

    fun address(input: String): HttpUrl? {
        val text = input.trim()
        val absolute = if ("://" !in text && '.' in text.substringBefore('/')) "https://$text" else text
        return absolute.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
            ?.newBuilder()?.fragment(null)?.build()
    }

    private fun isStars(url: HttpUrl) = url.host == "github.com" && url.pathSegments.size >= 4 &&
        url.pathSegments[0] == "stars" && url.pathSegments[2] == "lists"

    private fun target(attributes: String) = href.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        ?.replace("&amp;", "&")

    fun repository(input: String, base: HttpUrl? = null): String? {
        val text = input.trim()
        if (text.isEmpty() || text.startsWith('#') || text.startsWith('?')) return null
        PluginRepository.fromInstallLink(text)?.let { return it }
        if (base == null && '.' !in text.substringBefore('/') && "://" !in text) {
            runCatching { PluginRepository.parse(text) }.getOrNull()?.let { return it }
        }
        val url = (if (base == null) address(text) else base.resolve(text)) ?: return null
        if (url.host != "github.com" || url.port !in setOf(443, 80) || isStars(url) || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val parts = url.pathSegments.dropLastWhile { it.isEmpty() }
        if (parts.size < 2 || (parts.size > 2 && parts[2] !in setOf("releases", "tree", "blob"))) return null
        return runCatching { PluginRepository.parse(parts.take(2).joinToString("/")) }.getOrNull()
    }

    fun read(html: String, address: HttpUrl): Page {
        val stars = isStars(address)
        val contents = if (stars) {
            val start = container.find(html)?.range?.last?.plus(1) ?: throw PluginException("GitHub Stars repository list is missing")
            var depth = 1
            val close = div.findAll(html, start).firstOrNull { tag ->
                depth += if (tag.groupValues[1].isEmpty()) 1 else -1
                depth == 0
            } ?: throw PluginException("GitHub Stars repository list is incomplete")
            html.substring(start, close.range.first)
        } else html
        val repositories = (if (stars) headingLink else links).findAll(contents).mapNotNull { match ->
            target(match.groupValues[1])?.let { repository(it, address) }
        }.distinctBy { it.lowercase() }.take(33).toList()
        val next = if (!stars) null else links.findAll(contents).map { it.groupValues[1] }.firstOrNull { nextLink.containsMatchIn(it) }
            ?.let(::target)
            ?.let(address::resolve)
            ?.takeIf { it.isHttps && it.host == address.host && it.port == address.port &&
                it.encodedPath == address.encodedPath && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
        return Page(repositories, next)
    }

    data class Page(val repositories: List<String>, val next: HttpUrl?)
}
