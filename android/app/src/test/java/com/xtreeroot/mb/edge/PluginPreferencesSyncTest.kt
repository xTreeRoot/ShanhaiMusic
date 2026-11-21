package com.xtreeroot.mb.edge

import com.xtreeroot.mb.plugin.PluginPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginPreferencesSyncTest {
    private val writer = "AAECAwQFBgcICQoLDA0ODw"
    private val recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val key = PlatformDataKey("app", "plugins")
    private val codec = PlatformDataCodec(recovery, "test-space")

    @Test fun independentFieldsResetAndUnknownIdsRoundTrip() {
        val local = PluginPreferences().withName("Org.plugin-a", "我的音乐", writer)
        val remote = PluginPreferences().withOrder(listOf("org.plugin-b", "Org.plugin-a"), "EBESExQVFhcYGRobHB0eHw")
            .withEnabled("org.plugin-b", false, "EBESExQVFhcYGRobHB0eHw")
        val merged = local.merged(remote)
        assertEquals(merged, remote.merged(local))
        assertEquals("我的音乐", merged.names["Org.plugin-a"]?.value)
        val reset = merged.withName("Org.plugin-a", "", writer).merged(local)
        assertEquals("", reset.names["Org.plugin-a"]?.value)
        assertEquals(3L, reset.maxObservedClock)
        assertEquals(reset, PluginPreferences.parse(reset.toJson()))
        assertNotEquals(PluginPreferences.newWriter(), PluginPreferences.newWriter())
        assertThrows(IllegalArgumentException::class.java) { local.withOrder(listOf("a", "a"), writer) }
        assertThrows(IllegalArgumentException::class.java) { local.withName("bad..id", "Bad", writer) }
    }

    @Test fun emptyLocalPullsRemoteAndConflictMergesEditsBeforeConditionalWrite() = runBlocking {
        withServer { server, client, binding ->
            var local = PluginPreferences()
            suspend fun sync() = client.syncPluginPreferences(binding, { local }, { local = local.merged(it); local })
            server.enqueue(missing())
            sync(); assertEquals(1, server.requestCount)
            val remote = PluginPreferences().withName("unknown-plugin", "保留名称", writer)
            server.enqueue(present(remote, "first"))
            sync(); assertEquals(remote, local); assertEquals(2, server.requestCount)
            local = local.withOrder(listOf("unknown-plugin", "other"), writer)
            val concurrent = remote.withName("other", "远端并发名称", "EBESExQVFhcYGRobHB0eHw")
            server.enqueue(present(remote, "first")); server.enqueue(MockResponse().setResponseCode(412))
            server.enqueue(present(concurrent, "second")); server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"third\""))
            sync()
            assertEquals("远端并发名称", local.names["other"]?.value)
            repeat(3) { assertEquals("GET", server.takeRequest().method) }
            val stale = server.takeRequest(); assertEquals("\"first\"", stale.getHeader("If-Match"))
            assertEquals("GET", server.takeRequest().method)
            val write = server.takeRequest(); assertEquals("\"second\"", write.getHeader("If-Match"))
            val sent = PluginPreferences.parse(codec.open(key, 1, PlatformEnvelope.parse(JSONObject(write.body.readUtf8()))).getJSONObject("value"))
            assertEquals(local, sent)
        }
    }

    @Test fun deletedInvalidAndStaleBindingDoNotChangeLocalOrPublish() = runBlocking {
        withServer { server, client, binding ->
            var commits = 0
            val local = PluginPreferences().withName("keep", "Local", writer)
            suspend fun sync(current: () -> Boolean = { true }) = client.syncPluginPreferences(binding, { local }, { commits++; local.merged(it) }, current)
            server.enqueue(MockResponse().setResponseCode(410).setHeader("ETag", "\"deleted\"").setBody("""{"error":"deleted"}"""))
            assertEquals(NodeFailure.Deleted, (runCatching { sync() }.exceptionOrNull() as NodeException).failure)
            val invalid = codec.seal(key, 1, JSONObject().put("value", JSONObject().put("version", 99)))
            server.enqueue(MockResponse().setHeader("ETag", "\"bad\"").setBody(invalid.encode()))
            assertEquals(NodeFailure.Protocol, (runCatching { sync() }.exceptionOrNull() as NodeException).failure)
            assertTrue(runCatching { sync { false } }.exceptionOrNull() is CancellationException)
            assertEquals(0, commits); assertEquals(2, server.requestCount)
        }
    }
    private fun missing() = MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
    private fun present(value: PluginPreferences, tag: String) = MockResponse().setHeader("ETag", "\"$tag\"")
        .setBody(codec.seal(key, 1, JSONObject().put("value", value.toJson())).encode())
    private suspend fun withServer(action: suspend (MockWebServer, NodeClient, NodeBinding) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = nodeOrigin("https://fixture.test")
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            action(server, NodeClient(http), NodeBinding(origin, "test-device", "test-space", "dGVzdC10b2tlbg", Long.MAX_VALUE, recovery, recovery))
        }
    }
}
