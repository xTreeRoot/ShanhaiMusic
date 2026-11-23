package com.xtreeroot.mb.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRepositoryTest {
    @Test fun universalReleaseUsesOneAssetDespiteLegacyPlatformPackages() {
        val metadata = JSONObject("""{"tag_name":"v1.2.3","body":"notes","assets":[
            {"name":"plugin-android-arm64.zip","id":1},{"name":"plugin-universal.zip","id":2}]}""")
        val release = checkNotNull(PluginRepository.universalRelease("Owner/Fixture", metadata))
        assertEquals("asset-2", release.revision)
        assertEquals("https://github.com/Owner/Fixture/releases/download/v1.2.3/plugin-universal.zip", release.archive.toString())
        assertEquals("notes", release.notes)
        assertNull(PluginRepository.universalRelease("Owner/Fixture", JSONObject("""{"assets":[{"name":"legacy.zip"}]}""")))
        assertThrows(PluginException::class.java) { PluginRepository.universalRelease("Owner/Fixture", metadata.put("tag_name", "../invalid")) }
    }
    @Test fun starsListReadsOnlyRepositoryHeadingsInsideTheListAndKeepsPaginationOnThatList() {
        val address = checkNotNull(GitHubStarsList.address("https://github.com/stars/example/lists/%E6%B5%8B%E8%AF%95"))
        val html = """
            <h2 class="h3"><a href="/outside/top">Not a repository entry</a></h2>
            <div class="border-top" id="user-list-repositories"><div class="entry">
                <h2 class="h3"><a href="/Example/player"><span>Example / </span>player</a></h2>
                <a href="/Example/player/stargazers">Stars</a>
                <h2 class="existing h3 extra"><a href="/example/PLAYER">Duplicate</a></h2>
                <h2 class="h3"><a href="/xTreeRoot/MB">Host</a></h2>
                <h2 class="h3"><a href="https://other.test/user/repo">Other origin</a></h2>
            </div><div><a href="?page=2" rel="next">Next</a></div></div>
            <h2 class="h3"><a href="/outside/bottom">Not a repository entry</a></h2>
        """.trimIndent()
        val page = GitHubStarsList.read(html, address)
        assertEquals(listOf("Example/player", "xTreeRoot/MB"), page.repositories)
        assertEquals("2", page.next?.queryParameter("page"))
        assertNull(GitHubStarsList.read(html.replace("?page=2", "https://github.com/stars/other/lists/test"), address).next)
        assertThrows(PluginException::class.java) { GitHubStarsList.read("<main>404</main>", address) }
    }

    @Test fun releaseVersionDoesNotAllowDowngradeOrReinstall() {
        assertTrue(isNewerPluginVersion("v1.10.0", "1.9.5"))
        assertTrue(isNewerPluginVersion("v2.0.0", "2.0.0-rc.2"))
        assertTrue(isNewerPluginVersion("2.0.0-rc.10", "2.0.0-rc.2"))
        assertFalse(isNewerPluginVersion("1.9.5", "v1.10.0"))
        assertFalse(isNewerPluginVersion("2.0.0-rc.2", "2.0.0"))
        assertFalse(isNewerPluginVersion("v2.1.0+build.7", "2.1"))
        assertFalse(isNewerPluginVersion("latest", "1.0.0"))
    }

    @Test fun repositoryAndInstallLinksAreStrict() {
        assertEquals("octocat/player", PluginRepository.parse("octocat/player"))
        assertEquals("octocat/player", PluginRepository.parse("https://github.com/octocat/player.git/"))
        assertEquals("octocat/player", PluginRepository.fromInstallLink("mb://install-plugin?repo=octocat%2Fplayer"))
        assertEquals("octocat/player", PluginRepository.fromInstallLink("mygo-music://install-plugin?repo=octocat%2Fplayer"))
        listOf("octocat", "../repo", "octocat/../repo", "https://github.com.evil.test/octocat/player", "octocat/player#fragment", "https://github.com/octocat/player/tree/main", "https://github.com/octocat/player?q=1")
            .forEach { value -> assertThrows(PluginException::class.java) { PluginRepository.parse(value) } }
        listOf(
            "https://install-plugin?repo=octocat%2Fplayer",
            "mb://install-plugin?repo=octocat%2Fplayer&repo=octocat%2Fother",
            "mb://install-plugin?repo=octocat%2F..%2Frepo",
            "mb://install-plugin/path?repo=octocat%2Fplayer",
            "mb://install-plugin?repo=octocat%2Fplayer#other",
        ).forEach { assertNull(PluginRepository.fromInstallLink(it)) }
    }

    @Test fun releaseAssetSelectionPrefersTheDeviceAbiAndNeverTheWrongArchitecture() {
        val names = listOf("plugin-ios-arm64.zip", "plugin-android-universal.zip", "plugin-android-arm64-v8a.zip", "plugin-android-x86_64.zip")
        assertEquals(2, PluginRepository.assetIndex(names, listOf("arm64-v8a", "x86_64")))
        assertEquals(3, PluginRepository.assetIndex(names, listOf("x86_64")))
        assertEquals(1, PluginRepository.assetIndex(listOf("plugin-ios-arm64.zip", "plugin-android-universal.zip"), listOf("x86_64")))
        assertTrue(PluginRepository.assetIndex(listOf("source.tar.gz", "plugin.exe"), listOf("arm64-v8a")) == null)
    }
}
