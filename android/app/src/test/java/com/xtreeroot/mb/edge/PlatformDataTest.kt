package com.xtreeroot.mb.edge

import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PlatformDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val space = "test-space"
    private val key = PlatformDataKey("soda", "profile")
    private val clear = """{"value":{"display_name":"Fixture"}}"""
    private val vector = PlatformEnvelope(1, "AAECAwQFBgcICQoL",
        "1CiWbBgaQJw8-59H-Td2k9vAIU8_zVxQKtQ5h2qz8sxykNcXpd4_KjnyCXHP5GHzDDuqEw")
    private val origin = nodeOrigin("fixture.example")
    private val binding = NodeBinding(origin, "test-device", space, "dGVzdC10b2tlbg", Long.MAX_VALUE, recovery, recovery)

    @Test fun opensTheDesktopSessionVectorAndRejectsWrongPlugin() {
        val ref = "rQgGydt1-xJIAjpM4GXEO4Q0GyBkb4Sro0a5saijxaY"
        val wire = PlatformEnvelope(1, "AAECAwQFBgcICQoL",
            "4pKzDTCYmD-RlfPCsaqP3vVTdyYeYH7RZNXWMsAplG6Y_w3pJB2uUQ-IuPszwsAhbCxTVArDavI3up94c68j9YUzB1KJ1Tnm")
        val codec = NodeSessionCodec(binding, "example")
        val restored = codec.open(wire, ref)
        assertEquals("user-123", restored.accountId)
        assertEquals("synthetic", restored.data.getString("demo"))
        assertEquals(ref, codec.reference(restored.accountId))
        assertThrows(AEADBadTagException::class.java) { NodeSessionCodec(binding, "other").open(wire, ref) }
        val sealed = codec.seal(restored, ref)
        assertNotEquals(wire.nonce, sealed.nonce)
        assertEquals(restored.accountId, codec.open(sealed, ref).accountId)
    }

    @Test fun verifiesCatalogBeforeTrustingAnInstalledPluginOfTheSameId() = runBlocking {
        val privateKey = ByteArray(32) { index -> index.toByte() }
        val publicKey = ByteArray(32).also { Ed25519.generatePublicKey(privateKey, 0, it, 0) }
        val encoding = Base64.getUrlEncoder().withoutPadding()
        val node = NodeBinding(origin, binding.device, space, binding.token, Long.MAX_VALUE, recovery, encoding.encodeToString(publicKey))
        val folder = temporary.newFolder()
        folder.resolve("plugin.json").writeText("{}")
        folder.resolve("index.js").writeText("example")
        folder.resolve(".mb-origin.json").writeText("metadata")
        val digest = NodePluginContent.digest(folder)
        assertEquals("390039247f1da0ab6446e475561ec319f02d20fe8fca759719d02e75358c4bee", digest)
        val payload = """{"protocol":1,"revision":1,"plugins":[{"id":"example","version":"1.0.0","content_sha256":"$digest"}]}""".encodeToByteArray()
        val signature = ByteArray(64).also { Ed25519.sign(privateKey, 0, payload, 0, payload.size, it, 0) }
        fun signed(bytes: ByteArray): String = JSONObject().put("payload", encoding.encodeToString(bytes))
            .put("signature", encoding.encodeToString(signature)).toString()
        withServer { server, client ->
            server.enqueue(MockResponse().setBody(signed(payload)))
            client.verifySessionPlugin(node, "example", "1.0.0", digest)
            val request = server.takeRequest()
            assertEquals("/v1/catalog", request.path)
            assertNull(request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(signed(payload)))
            expect(NodeFailure.UntrustedPlugin) { client.verifySessionPlugin(node, "example", "1.0.0", "b".repeat(64)) }
            server.enqueue(MockResponse().setBody(signed(payload + 32.toByte())))
            expect(NodeFailure.UntrustedPlugin) { client.verifySessionPlugin(node, "example", "1.0.0", digest) }
        }
    }

    @Test fun profileCreationAndExplicitRenameUseTheIosProfileContract() = runBlocking {
        withServer { server, client ->
            val codec = PlatformDataCodec(recovery, space)
            val local = PlatformProfile("listener", "Phone")
            val remote = local.copy(displayName = "Desktop")
            val envelope = codec.seal(key, 1, remote.payload())
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"first\""))
            assertEquals(local, client.syncProfile(binding, key.platform, local, publish = false))
            assertEquals("/v1/data/soda/profile", server.takeRequest().path)
            val create = server.takeRequest()
            assertEquals("*", create.getHeader("If-None-Match"))
            val decoded = codec.open(key, 1, PlatformEnvelope.parse(JSONObject(create.body.readUtf8())))
            val value = decoded.getJSONObject("value")
            assertEquals(setOf("account_id", "display_name"), value.keys().asSequence().toSet())
            assertEquals(local, PlatformProfile.parse(decoded))

            repeat(2) { server.enqueue(MockResponse().setHeader("ETag", "\"first\"").setBody(envelope.encode())) }
            assertEquals(remote, client.syncProfile(binding, key.platform, local, publish = false))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"second\""))
            assertEquals(local, client.syncProfile(binding, key.platform, local, publish = true))
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("\"first\"", server.takeRequest().getHeader("If-Match"))
        }
    }

    @Test fun changingTheAccountOrNodeBeforeTheConditionalWriteDoesNotSendIt() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            val result = runCatching {
                client.syncProfile(binding, key.platform, PlatformProfile("listener", "Phone"), publish = true) { false }
            }
            assertEquals(CancellationException::class.java, result.exceptionOrNull()?.javaClass)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun profileSyncNeverWritesOverAnotherAccountTombstoneOrUnsupportedEndpoint() = runBlocking {
        withServer { server, client ->
            val codec = PlatformDataCodec(recovery, space)
            val local = PlatformProfile("listener", "Phone")
            val other = codec.seal(key, 1, PlatformProfile("other", "Desktop").payload())
            server.enqueue(MockResponse().setHeader("ETag", "\"first\"").setBody(other.encode()))
            expect(NodeFailure.OtherAccount) { client.syncProfile(binding, key.platform, local, publish = true) }
            server.enqueue(MockResponse().setResponseCode(410).setHeader("ETag", "\"deleted\"").setBody("""{"error":"deleted"}"""))
            expect(NodeFailure.Deleted) { client.syncProfile(binding, key.platform, local, publish = true) }
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"unsupported"}"""))
            expect(NodeFailure.Unsupported) { client.syncProfile(binding, key.platform, local, publish = true) }
            repeat(3) { assertEquals("GET", server.takeRequest().method) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun conflictingProfileWriteIsReadBackButNeverRetriedOverAnotherRevision() = runBlocking {
        withServer { server, client ->
            val codec = PlatformDataCodec(recovery, space)
            val local = PlatformProfile("listener", "Phone")
            val remote = codec.seal(key, 1, local.copy(displayName = "Desktop").payload())
            server.enqueue(MockResponse().setHeader("ETag", "\"old\"").setBody(remote.encode()))
            server.enqueue(MockResponse().setResponseCode(412))
            server.enqueue(MockResponse().setHeader("ETag", "\"new\"").setBody(remote.encode()))
            expect(NodeFailure.Conflict) { client.syncProfile(binding, key.platform, local, publish = true) }
            assertEquals("GET", server.takeRequest().method)
            assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
            assertEquals("GET", server.takeRequest().method)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun matchesCrossClientVectorAndBindsCiphertextToSpaceKeyAndFormat() {
        assertEquals("jMZa0IZgOPiLSkJz1EqCZDIcV_iuXtq_XuV857DCPm4",
            Base64.getUrlEncoder().withoutPadding().encodeToString(derivePlatformKey(recovery, space)))
        val codec = PlatformDataCodec(recovery, space) { ByteArray(12) { index -> index.toByte() } }
        assertEquals(vector, codec.seal(key, 1, JSONObject(clear)))
        assertEquals(clear, codec.open(key, 1, vector).toString())
        assertThrows(AEADBadTagException::class.java) { codec.open(PlatformDataKey("soda", "other"), 1, vector) }
        assertThrows(AEADBadTagException::class.java) { PlatformDataCodec(recovery, "other").open(key, 1, vector) }
        assertThrows(AEADBadTagException::class.java) { codec.open(key, 2, vector.copy(format = 2)) }
        assertThrows(IllegalArgumentException::class.java) { codec.open(key, 2, vector) }
        val random = PlatformDataCodec(recovery, space)
        assertNotEquals(random.seal(key, 1, JSONObject(clear)).nonce, random.seal(key, 1, JSONObject(clear)).nonce)
        assertThrows(IllegalArgumentException::class.java) {
            random.seal(key, 1, JSONObject().put("value", "x".repeat(100_000)))
        }
    }

    @Test fun exchangesConditionalRevisionsWithoutOverwritingConflictsOrTombstones() = runBlocking {
        withServer { server, client ->
            val first = "\"first\""
            val tombstone = "\"deleted\""
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            assertEquals(PlatformRecord.Missing, client.read(binding, key))
            val read = server.takeRequest()
            assertEquals("/v1/data/soda/profile", read.path)
            assertEquals("Bearer ${binding.token}", read.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", first))
            assertEquals(first, client.create(binding, key, vector))
            val create = server.takeRequest()
            assertEquals("*", create.getHeader("If-None-Match"))
            assertEquals(vector, PlatformEnvelope.parse(JSONObject(create.body.readUtf8())))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", first).setBody(vector.encode()))
            assertEquals(PlatformRecord.Present(first, vector), client.read(binding, key))
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(412).setBody("""{"error":"revision_conflict"}"""))
            expect(NodeFailure.Conflict) { client.replace(binding, key, first, vector) }
            assertEquals(first, server.takeRequest().getHeader("If-Match"))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", tombstone))
            assertEquals(tombstone, client.delete(binding, key, first))
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals(first, delete.getHeader("If-Match"))
            server.enqueue(MockResponse().setResponseCode(410).setHeader("ETag", tombstone).setBody("""{"error":"deleted"}"""))
            assertEquals(PlatformRecord.Deleted(tombstone), client.read(binding, key))
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(401))
            expect(NodeFailure.Unauthorized) { client.read(binding, key) }
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(404).setBody("not a platform endpoint"))
            expect(NodeFailure.Unsupported) { client.read(binding, key) }
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun joinSendsRawPasswordOnlyToTheRequestedOriginAndDoesNotFollowRedirects() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/another")))
            expect(NodeFailure.Protocol) { client.join(origin, " synthetic ", "Phone") }
            assertEquals(1, server.requestCount)
            val join = server.takeRequest()
            assertEquals("/v1/join", join.path)
            assertNull(join.getHeader("Authorization"))
            val data = JSONObject(join.body.readUtf8())
            assertEquals(" synthetic ", data.getString("password"))
            assertEquals("android", data.getString("platform"))
            server.enqueue(MockResponse().setResponseCode(201).setBody(JSONObject().put("device_id", binding.device)
                .put("space_id", binding.space).put("token", binding.token).put("expires_at", binding.expiresAt)
                .put("recovery_key", binding.recoveryKey).put("catalog_public_key", binding.catalogPublicKey).toString()))
            assertEquals(binding.space, client.join(origin, "fixture", "Phone").space)
            for (input in listOf("http://fixture.example", "https://name@fixture.example", "https://fixture.example/prefix")) {
                assertThrows(IllegalArgumentException::class.java) { nodeOrigin(input) }
            }
        }
    }

    private suspend fun withServer(block: suspend (MockWebServer, NodeClient) -> Unit) = MockWebServer().use { server ->
        server.start()
        val http = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            val request = chain.request()
            assertEquals(origin.host, request.url.host)
            val testUrl = server.url(request.url.encodedPath)
            chain.proceed(request.newBuilder().url(testUrl).build())
        }.build()
        block(server, NodeClient(http))
    }

    private suspend fun expect(kind: NodeFailure, block: suspend () -> Any) {
        val result = runCatching { block() }.exceptionOrNull()
        assertEquals(kind, (result as? NodeException)?.failure)
    }
}
