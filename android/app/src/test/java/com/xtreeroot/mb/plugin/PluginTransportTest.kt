package com.xtreeroot.mb.plugin

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTransportTest {
    private val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["127.0.0.1"]}}"""))

    @Test fun manifestChecksAndroidRequirementWithoutIgnoringMalformedOverrides() {
        fun parse(overrides: String) = PluginManifest.parse(JSONObject("""
            {"id":"fixture","minAppVersion":"0.1.28","minAppVersionByPlatform":$overrides}
        """))
        assertEquals("0.1.28", parse("""{"desktop":"0.1.25"}""").minAppVersion)
        assertEquals("0.1.27", parse("""{"android":"0.1.27","ios":"0.1.29"}""").minAppVersion)
        for (overrides in listOf("""{"android":"0.1.29"}""", """{"ios":"v0.1.25"}""", """{"macos":"0.1.25"}""", """{"android":null}""")) {
            assertThrows(PluginException::class.java) { parse(overrides) }
        }
    }

    @Test fun manifestRejectsUnsupportedHostAndMatchesOnlyTheAllowedDomain() {
        listOf(
            """{"id":"../escape"}""",
            """{"id":"fixture","main":"../outside.js"}""",
            """{"id":"fixture","minAppVersion":"99.0.0"}""",
            """{"id":"fixture","permissions":{"process":["ffmpeg"]}}""",
            """{"id":"fixture","requestHeaders":[{"hosts":["unapproved.example.test"],"headers":{"Cookie":"fixture"}}]}""",
        ).forEach { text -> assertThrows(PluginException::class.java) { PluginManifest.parse(JSONObject(text)) } }
        val restricted = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["*.Example.test"]}}"""))
        assertTrue(restricted.reaches("media.example.test"))
        assertFalse(restricted.reaches("notexample.test"))
        assertFalse(restricted.reaches("example.test"))
    }

    @Test fun manifestAcceptsTheImplementedLoginContractsButNotFutureRequirements() {
        for (version in listOf("0.1.26", "0.1.27", "0.1.28")) {
            val info = PluginManifest.parse(JSONObject().put("id", "fixture").put("minAppVersion", version))
            assertEquals(version, info.minAppVersion)
        }
        val future = JSONObject().put("id", "fixture").put("minAppVersion", "0.1.29")
        assertThrows(PluginException::class.java) { PluginManifest.parse(future) }
    }

    @Test fun crossOriginRedirectStripsCredentialsAndKeepsSeparateSetCookieHeaders() = runBlocking {
        withServer { target ->
            target.enqueue(MockResponse().addHeader("Set-Cookie", "first=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
                .addHeader("Set-Cookie", "second=2; Path=/"))
            withServer { origin ->
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${target.port}/receive"))
                val initial = PluginFetchRequest(uri("http://127.0.0.1:${origin.port}/request"), "POST",
                    mapOf("Cookie" to "test-only=1", "Authorization" to "Bearer fake-token"), "fixture")
                val result = PluginHttpTransport().fetch(initial, manifest)
                assertEquals(200, result.status)
                val redirected = checkNotNull(target.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", redirected.method)
                assertNull(redirected.getHeader("Cookie"))
                assertNull(redirected.getHeader("Authorization"))
                assertEquals(setOf("first=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT", "second=2; Path=/"), result.cookies.toSet())
            }
        }
    }

    @Test fun redirectChecksPermissionsBeforeContactingTarget() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:${server.port}/receive"))
            server.enqueue(MockResponse())
            val initial = PluginFetchRequest(uri("http://127.0.0.1:${server.port}/request"), "GET", emptyMap(), null)
            val error = runCatching { PluginHttpTransport().fetch(initial, manifest) }.exceptionOrNull()
            assertEquals("Request outside plugin network permissions", error?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun manualRedirectPreservesStatusLocationAndCookiesWithoutFollowing() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next")
                .addHeader("Set-Cookie", "session=fixture; HttpOnly"))
            val url = uri("http://127.0.0.1:${server.port}/request")
            val request = PluginFetchRequest(url, "POST", emptyMap(), "fixture", PluginRedirectMode.Manual)
            val response = PluginHttpTransport().fetch(request, manifest)
            assertEquals(302, response.status)
            assertEquals(url, response.url)
            assertEquals("/next", response.headers["location"])
            assertEquals(listOf("session=fixture; HttpOnly"), response.cookies)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun errorModeRejectsRedirectWithoutFollowing() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/next"))
            val url = uri("http://127.0.0.1:${server.port}/request")
            val request = PluginFetchRequest(url, "GET", emptyMap(), null, PluginRedirectMode.Error)
            val result = runCatching { PluginHttpTransport().fetch(request, manifest) }
            assertEquals("Unexpected plugin redirect", result.exceptionOrNull()?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun perRequestResponseLimitAlsoAppliesToChunkedBodies() = runBlocking {
        withServer { server ->
            val exact = "x".repeat(oauthResponseLimit)
            server.enqueue(MockResponse().setChunkedBody(exact, 4096))
            server.enqueue(MockResponse().setChunkedBody(exact + "x", 4096))
            val url = uri("http://127.0.0.1:${server.port}/token")
            val request = PluginFetchRequest(url, "POST", emptyMap(), "fixture", PluginRedirectMode.Error, oauthResponseLimit)
            val client = PluginHttpTransport()
            assertEquals(oauthResponseLimit, client.fetch(request, manifest).bytes.size)
            val error = runCatching { client.fetch(request, manifest) }.exceptionOrNull()
            assertEquals("Plugin response body too large", error?.message)
        }
    }

    @Test fun sameOrigin307RetainsRequestWhileCrossOriginCannotForwardBody() {
        val original = PluginFetchRequest(uri("https://example.test/current"), "POST", mapOf("Cookie" to "example=1", "Content-Type" to "text/plain"), "payload")
        val sameOrigin = original.redirect(307, "/other")!!
        assertEquals(original.headers, sameOrigin.headers)
        assertEquals(original.body, sameOrigin.body)
        assertEquals(original.method, sameOrigin.method)
        assertThrows(PluginException::class.java) { original.redirect(308, "https://other.example.test/") }
        assertThrows(PluginException::class.java) { original.redirect(302, "http://example.test/") }
        val seeOther = original.redirect(303, "/other")!!
        assertEquals("GET", seeOther.method)
        assertNull(seeOther.body)
        assertFalse(seeOther.headers.containsKey("Content-Type"))
    }

    private suspend fun withServer(run: suspend (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            withTimeout(5000) { run(server) }
        }
    }
}
