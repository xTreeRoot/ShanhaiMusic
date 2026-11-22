package com.xtreeroot.mb.plugin

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.TrackUnavailableReason
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginRuntimeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["api.example.test"]}}"""))

    @Test fun videoQualityForwardsIndependentPreferenceAndReadsActualFallbackAndRealChoices() = fixture(
        """
        export function video({quality}) { return {url:'https://api.example.test/video.mp4',type:'mp4',videoQuality:quality==='1080p'?'720p':quality,
            videoQualities:[{id:'1080p',label:'1080p60'},{id:'720p',label:'720p'},{id:'720p',label:'duplicate'},{id:'bad key',label:'invalid'}]}; }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val result = source.video("one", "1080p")
        assertEquals("720p", result.videoQuality)
        assertEquals(listOf("1080p", "720p"), result.videoQualities.map { it.id })
        assertEquals("1080p60", result.videoQualities.first().label)
        assertEquals("720p", source.video("one", "720p").videoQuality)
    }

    @Test fun explicitCatalogRefreshReplacesPluginSnapshotAndHostCache() = fixture(
        """
        let revision = 0, snapshot;
        export function browse({refresh}={}) {
            if (!snapshot || refresh) snapshot = {sections:[{tracks:[{id:'snapshot',name:'Snapshot '+(++revision)}]}],more:false};
            return snapshot;
        }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val request = PluginCatalogRequest.Browse("shelf", "Shelf")
        assertEquals("Snapshot 1", source.catalog.load(request).sections[0].tracks[0].title)
        assertEquals("Snapshot 2", source.catalog.load(request, refresh = true).sections[0].tracks[0].title)
        assertEquals("Snapshot 2", source.catalog.load(request).sections[0].tracks[0].title)
    }

    @Test fun browsePagesForwardOffsetsAndPreservePortraitMetadata() = fixture(
        """
        export function browse({page,offset = 0}) { return {tabs:[{title:'All',page:'all'},{title:'Music',page:'music'}],selectedTab:page,sections:[{tracks:[{id:String(offset),name:'Portrait',video:{width:1080,height:1920,short:true}}]}],more:offset===0}; }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val request = PluginCatalogRequest.Browse("shorts", "Shorts")
        val first = source.catalog.load(request)
        assertEquals(1, first.nextOffset)
        assertEquals("shorts", first.selectedTab)
        assertEquals(listOf("all", "music"), first.tabs.map { (it.destination as PluginCatalogRequest.Browse).page })
        val track = first.sections.single().tracks.single()
        assertEquals(1080, track.videoWidth)
        assertEquals(1920, track.videoHeight)
        assertTrue(track.shortVideo)
        assertEquals(track, track.toMetadataJson().toTrackMetadata())
        val second = source.catalog.load(request, checkNotNull(first.nextOffset))
        assertEquals("1", second.sections.single().tracks.single().sourceTrackId)
        assertNull(second.nextOffset)
    }

    @Test fun openUrlResolvesWithoutNetworkFetchAndRejectsInvalidAddresses() {
        val requests = mutableListOf<PluginWebLink>()
        fixture("export function open({url}) { return host.openURL(url); }", openURL = { requests.add(it) }) { runtime, _, _ ->
            suspend fun open(address: String) = runtime.invoke("open", JSONObject().put("url", address))
            val url = "https://outside-plugin-permissions.example.test/account"
            assertEquals(JSONObject.NULL, open(url))
            assertEquals(url, requests.single().url.toASCIIString())
            assertEquals(manifest.name, requests.single().title)
            for (address in listOf("file:///local", "intent://example.test", "https://user@example.test/", "//example.test", "http://example.test/")) {
                rejected { open(address) }
            }
            assertEquals(1, requests.size)
        }
    }

    @Test fun audioResolutionPreservesStructuredPluginFailuresWithoutInferringFromErrorText() = fixture(
        """
        export async function stream({id}) {
          if (id === 'empty') return {};
          if (id === 'blank') return {url:'   '};
          if (id === 'null') return null;
          if (id === 'invalidUrl') return {url:'file:///audio.mp3'};
          if (id === 'ok') return {url:'https://api.example.test/recovered.mp3',type:'mp3'};
          const error = new Error('This message says VIP but is not a permission signal');
          if (id !== 'unknown') error.code = id;
          throw error;
        }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        for ((id, expected) in mapOf("vip" to PlaybackIssue.Vip, "noSource" to PlaybackIssue.SourceUnavailable,
            "unavailable" to PlaybackIssue.Unavailable, "empty" to PlaybackIssue.SourceUnavailable,
            "blank" to PlaybackIssue.SourceUnavailable, "null" to PlaybackIssue.SourceUnavailable,
            "signIn" to PlaybackIssue.SignIn, "network" to PlaybackIssue.Network)) {
            try { source.audio(id); fail("Expected classified failure for $id") }
            catch (error: PlaybackIssueException) { assertEquals(expected, error.issue) }
        }
        for (id in listOf("unknown", "401", "403", "VIP", "invalidUrl")) rejected { source.audio(id) }
        assertEquals("https://api.example.test/recovered.mp3", source.audio("ok").url)
    }

    @Test fun videoResolutionPreservesSynchronousAndAsynchronousPluginFailures() = fixture(
        """
        export function video({id}) {
          if (id === 'signed-out') throw Object.assign(new Error('Sign in first'), {code: 'signIn'});
          if (id === 'offline') return Promise.reject(Object.assign(new Error('Offline'), {code: 'network'}));
          if (id === 'unknown') throw new Error('Error text says network and signIn');
          return {url:'https://api.example.test/video.mp4'};
        }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        for ((id, expected) in mapOf("signed-out" to PlaybackIssue.SignIn, "offline" to PlaybackIssue.Network)) {
            try { source.video(id); fail("Expected classified video failure for $id") }
            catch (error: PlaybackIssueException) { assertEquals(expected, error.issue) }
        }
        rejected { source.video("unknown") }
        assertEquals("https://api.example.test/video.mp4", source.video("ok").url)
    }

    @Test fun failedFetchReachesAudioAsNetworkWithoutMisclassifyingPermissionDenial() {
        val port = ServerSocket(0).use { it.localPort }
        val local = manifest.copy(network = listOf("127.0.0.1"))
        fixture(
            """
            export async function stream({id}) {
              await fetch(id === 'permitted' ? 'https://127.0.0.1:$port/audio' : 'https://blocked.example.invalid/audio');
            }
            """.trimIndent(), plugin = local, transport = PluginHttpTransport(),
        ) { runtime, _, _ ->
            val source = PluginSource(local, runtime)
            try { source.audio("permitted"); fail("Expected classified connection failure") }
            catch (error: PlaybackIssueException) { assertEquals(PlaybackIssue.Network, error.issue) }
            rejected { source.audio("denied") }
        }
    }

    @Test fun trackReasonsRoundTripWithoutInventingOneForLegacyPluginTracks() = fixture(
        """
        export function search() {
          const reasons = ['vip', 'noSource', 'signIn', 'unavailable', 'VIP'];
          const tracks = reasons.map(id => ({id, name:id, unavailableReason:id}));
          tracks.push({id:'legacy', name:'Legacy', unavailable:true}, {id:'normal', name:'Normal'});
          return {tracks};
        }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val tracks = source.search("test", 1).tracks.associateBy { it.sourceTrackId }
        for (reason in TrackUnavailableReason.entries) {
            val track = tracks.getValue(reason.code)
            assertEquals(reason, track.unavailableReason)
            assertTrue(track.isUnavailable)
            assertEquals(track, track.toMetadataJson().toTrackMetadata())
        }
        val legacy = tracks.getValue("legacy")
        assertTrue(legacy.isUnavailable)
        assertNull(legacy.unavailableReason)
        assertEquals(legacy, legacy.toMetadataJson().toTrackMetadata())
        for (id in listOf("VIP", "normal")) assertFalse(tracks.getValue(id).isUnavailable)
        val capabilities = source.interactionCapabilities()
        assertFalse(capabilities.likes || capabilities.comments || capabilities.posting || capabilities.commentLikes)
        source.played("normal")
        assertTrue(source.suggestions("title").isEmpty())
    }

    @Test fun optionalSuggestUsesTheIosContractWithoutCoercingOtherJsonTypes() = fixture(
        """
        export function suggest(args) {
          if (Object.keys(args).join() !== 'query' || args.query !== ' 夜曲 🎵 ') throw Error('suggest arguments');
          return ['夜曲', 123, null, {name:'ignored'}, true, '夜曲 现场'];
        }
        """.trimIndent(),
    ) { runtime, _, _ ->
        assertEquals(listOf("夜曲", "夜曲 现场"), PluginSource(manifest, runtime).suggestions(" 夜曲 🎵 "))
    }

    @Test fun pluginInteractionsUseIndependentCursorsAndIosArguments() = fixture(
        """
        const comment = {id:'first', author:'User', avatarUrl:'https://api.example.test/avatar.png',
          text:'hello', time:123456789, likes:2, liked:true};
        const events = [];
        let liked = false;
        export function isLiked({ids}) { if (ids.join() !== 'song') throw Error('ids'); return {song:liked}; }
        export function like({id,on}) { if (id !== 'song' || typeof on !== 'boolean') throw Error('like'); liked = on; }
        export function comments({trackId,offset}) {
          if (trackId !== 'song') throw Error('track');
          if (offset === 0) return {comments:[comment, {id:'invalid'}], total:3, more:true};
          if (offset !== 2) throw Error('wrong offset');
          if (!host.storage.get('retried')) {
            host.storage.set('retried', true);
            throw Error('page not ready');
          }
          return {comments:[{...comment, id:'last'}], total:3, more:false};
        }
        export function postComment(args) { events.push(args); return {...comment, id:'sent', text:args.text}; }
        export function likeComment(args) { events.push(args); }
        export function played(args) { events.push(args); }
        export function state() { return {events}; }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val capabilities = source.interactionCapabilities()
        assertTrue(capabilities.likes && capabilities.comments && capabilities.posting && capabilities.commentLikes)
        assertFalse(source.isLiked("song"))
        source.like("song", true)
        assertTrue(source.isLiked("song"))
        source.like("song", false)
        assertFalse(source.isLiked("song"))
        val first = source.comments("song")
        assertEquals(2, first.nextCursor)
        assertEquals(3, first.total)
        assertEquals(123456789L, first.comments.single().timeMs)
        assertEquals(2, first.comments.single().likes)
        assertTrue(first.comments.single().liked)
        rejected { source.comments("song", first.nextCursor) }
        assertEquals(first, source.comments("song"))
        val last = source.comments("song", first.nextCursor)
        assertEquals("last", last.comments.single().id)
        assertNull(last.nextCursor)
        assertEquals("new comment", source.postComment("song", "new comment").text)
        source.likeComment("song", "first", false)
        source.played("song")
        val events = (runtime.invoke("state") as JSONObject).getJSONArray("events")
        assertEquals(3, events.length())
        assertEquals("song", events.getJSONObject(0).getString("trackId"))
        assertEquals("new comment", events.getJSONObject(0).getString("text"))
        assertEquals("song", events.getJSONObject(1).getString("trackId"))
        assertEquals("first", events.getJSONObject(1).getString("commentId"))
        assertFalse(events.getJSONObject(1).getBoolean("on"))
        assertEquals("song", events.getJSONObject(2).getString("id"))
    }

    @Test fun modulePromisesStorageCryptoAndUnicodeMatchTheHostContract() = fixture(
        """
        import { label } from './helper.js';
        export async function search({query}) {
          await new Promise(resolve => setTimeout(resolve, 1));
          host.storage.set('previous', {query, platform: host.platform});
          return {label, quality:host.setting('quality'), digest:host.crypto.md5('abc'),
            encrypted:host.crypto.aesEcbEncrypt('0123456789abcdef','abc'), roundtrip:btoa(atob('AP8='))};
        }
        export function previous() { return host.storage.get('previous'); }
        """.trimIndent(),
    ) { runtime, root, data ->
        File(root, "helper.js").writeText("export const label = '模块🎵';")
        assertTrue(runtime.supports("search"))
        assertFalse(runtime.supports("video"))
        val query = "中文🎶\u0000"
        val result = runtime.invoke("search", JSONObject().put("query", query)) as JSONObject
        assertEquals("模块🎵", result.getString("label"))
        assertEquals("high", result.getString("quality"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", result.getString("digest"))
        assertEquals("1d25821c3e311eea2d4dd8633a25c1b5", result.getString("encrypted"))
        assertEquals("AP8=", result.getString("roundtrip"))
        runtime.close()
        val reopened = PluginRuntime(app.assets, root, data, manifest)
        try {
            val previous = reopened.invoke("previous") as JSONObject
            assertEquals(query, previous.getString("query"))
            assertEquals("android", previous.getString("platform"))
        } finally { reopened.close() }
    }

    @Test fun cancelledInvocationReleasesInFlightTransportAndQueuedCallRecovers() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        fixture(
            """
            let state = 42;
            export async function wait() { state = -1; await fetch('https://api.example.test/wait'); }
            export function ok() { return state; }
            """.trimIndent(),
            transport = PluginTransport { _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            },
        ) { runtime, _, _ ->
            val operation = async { runtime.invoke("wait") }
            started.await()
            val next = async { runtime.invoke("ok") }
            yield()
            operation.cancelAndJoin()
            cancelled.await()
            assertTrue(operation.isCancelled)
            assertEquals(42, next.await())
        }
    }

    @Test fun playbackBypassesBlockedCatalogAndItsVmSurvivesCatalogCancellation() {
        val started = CompletableDeferred<Unit>()
        fixture(
            """
            let count = 0;
            export async function search() { host.storage.set('shared', 'catalog'); await fetch('https://api.example.test/wait'); }
            export function stream() { return {count: ++count, shared: host.storage.get('shared')}; }
            export function video() { return ++count; }
            """.trimIndent(),
            transport = PluginTransport { _, _ -> started.complete(Unit); awaitCancellation() },
        ) { runtime, _, _ ->
            val catalog = async { runtime.invoke("search") }
            started.await()
            val timeout = launch { delay(1_000); catalog.cancel() }
            try {
                assertTrue(runtime.supports("video"))
                val first = runtime.stream(JSONObject()) as JSONObject
                assertFalse(catalog.isCancelled)
                assertEquals("catalog", first.getString("shared"))
                assertEquals(1, first.getInt("count"))
                catalog.cancelAndJoin()
                assertEquals(2, runtime.invoke("video"))
            } finally { timeout.cancel(); catalog.cancelAndJoin() }
        }
    }

    @Test fun largePublicResponseCanBeParsedWithinABoundedBudget() = fixture(
        """
        export async function parse() { const value = await (await fetch('https://api.example.test/large')).json(); return value.rows.filter(row => row.title.includes('fixture')).length; }
        """.trimIndent(), transport = PluginTransport { request, _ ->
            val row = JSONObject().put("title", "fixture".repeat(20))
            val rows = org.json.JSONArray((1..7000).map { row })
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(), JSONObject().put("rows", rows).toString().encodeToByteArray())
        },
    ) { runtime, _, _ -> assertEquals(7000, runtime.invoke("parse")) }

    @Test fun declaredLoginMethodReachesPluginArgumentsAndUnknownChoiceNeverExecutes() = fixture(
        """
        let calls = 0;
        export function signIn({method}) { calls++; return {id:method,name:method}; }
        export function count() { return calls; }
        """.trimIndent(), plugin = PluginManifest.parse(JSONObject("""{"id":"choice","signInMethods":[{"id":"first","label":"First"},{"id":"second","label":"Second"}]}""")),
    ) { runtime, _, _ ->
        val manifest = PluginManifest.parse(JSONObject("""{"id":"choice","signInMethods":[{"id":"first","label":"First"},{"id":"second","label":"Second"}]}"""))
        val source = PluginSource(manifest, runtime)
        assertEquals("second", source.signIn("second") {}.id)
        rejected { source.signIn("invalid") {} }
        assertEquals(1, runtime.invoke("count"))
    }

    @Test fun loginComputationBudgetCoversStartAndResponsesWithoutLeakingToOrdinaryCalls() = fixture(
        """
        function work() { let sum = 0; for (let n = 1; n <= 3000000; n++) sum = (sum + n) % 65521; return sum; }
        export async function signIn() { const first = work(); await fetch('https://api.example.test/qr'); return first + work(); }
        export function ordinary() { return work(); }
        export function ok() { return 42; }
        """.trimIndent(), transport = PluginTransport { request, _ ->
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(), "small response".encodeToByteArray())
        },
    ) { runtime, _, _ ->
        val sum = ((3_000_000L * 3_000_001L / 2) % 65521).toInt()
        assertEquals(sum * 2, runtime.signIn {})
        rejected { runtime.invoke("ordinary") }
        assertEquals(42, runtime.invoke("ok"))
    }

    @Test fun loginInfiniteLoopStillHasABoundedBudgetAndNextCallRecovers() = fixture(
        """
        export async function signIn() { await fetch('https://api.example.test/qr'); while (true) {} }
        export function ok() { return 42; }
        """.trimIndent(), transport = PluginTransport { request, _ ->
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(), byteArrayOf())
        },
    ) { runtime, _, _ ->
        rejected { runtime.signIn {} }
        assertEquals(42, runtime.invoke("ok"))
    }

    @Test fun infiniteLoopAndSymlinkImportAreRejectedWithoutPoisoningNextInvocation() = fixture(
        """
        export function bad() { while (true) {} }
        export async function outside() { return (await import('./link.js')).leaked; }
        export function ok() { return 42; }
        """.trimIndent(),
    ) { runtime, root, _ ->
        val outside = File(root.parentFile, "outside.js").apply { writeText("export const leaked = 'private';") }
        Files.createSymbolicLink(File(root, "link.js").toPath(), outside.toPath())
        rejected { runtime.invoke("bad") }
        rejected { runtime.invoke("outside") }
        assertEquals(42, runtime.invoke("ok"))
    }

    @Test fun fetchSerializesFormsAndLimitsNetworkAndKeepsDetachedTimersAlive() = fixture(
        """
        export async function search() {
            const url = new URL('/find', 'https://api.example.test/root');
            const response = await fetch(url, {method:'POST', redirect:'manual', headers:{'X-Fixture':'yes'}, body:new URLSearchParams({q:'你好 + !'})});
            setTimeout(() => { globalThis.ready = true; }, 5);
            return {status:response.status, header:response.headers.get('X-Answer'), bytes:[...new Uint8Array(await response.arrayBuffer())]};
        }
        export async function ready() {
            while (!globalThis.ready) await new Promise(resolve => setTimeout(resolve, 2));
            return true;
        }
        export async function forbidden() { return await fetch('https://other.example.test/'); }
        export async function invalidRedirect() { return await fetch('https://api.example.test/find', {redirect:'invalid'}); }
        """.trimIndent(),
        transport = PluginTransport { request, _ ->
            assertEquals("https://api.example.test/find", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals(PluginRedirectMode.Manual, request.redirectMode)
            assertEquals("yes", request.headers["x-fixture"])
            assertEquals("application/x-www-form-urlencoded;charset=UTF-8", request.headers["content-type"])
            assertEquals("q=%E4%BD%A0%E5%A5%BD+%2B+%21", request.body)
            PluginFetchResponse(201, request.url, mapOf("x-answer" to "done"), emptyList(), byteArrayOf(0, -1))
        },
    ) { runtime, _, _ ->
        val result = runtime.invoke("search") as JSONObject
        assertEquals(201, result.getInt("status"))
        assertEquals("done", result.getString("header"))
        assertEquals("[0,255]", result.getJSONArray("bytes").toString())
        assertEquals(true, runtime.invoke("ready"))
        rejected { runtime.invoke("forbidden") }
        rejected { runtime.invoke("invalidRedirect") }
    }

    @Test fun desktopBilibiliFixtureRunsWithoutChanges() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val script = assets.open("index.js").bufferedReader().use { it.readText() }
        val bilibili = assets.open("plugin.json").bufferedReader().use { PluginManifest.parse(JSONObject(it.readText())) }
        fixture(script, plugin = bilibili, transport = PluginTransport { request, _ ->
            val body = when (request.url.path) {
                "/x/web-interface/nav" -> """{"code":-101,"data":{"wbi_img":{"img_url":"https://i.example/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
                "/x/web-interface/wbi/search/type" -> {
                    val query = request.url.rawQuery.split('&').associate { part ->
                        part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
                    }
                    assertEquals("中文 music", query["keyword"])
                    assertTrue(Regex("[0-9a-f]{32}").matches(query["w_rid"].orEmpty()))
                    """{"code":0,"data":{"numPages":1,"result":[{"bvid":"BV123","title":"<em>Test</em>","author":"UP","pic":"//i0.hdslb.com/image.jpg","duration":"3:07"}]}}"""
                }
                else -> throw PluginException("Unexpected fixture request")
            }
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(), body.encodeToByteArray())
        }) { runtime, _, _ ->
            val result = PluginSource(bilibili, runtime).search("中文 music", 1)
            val track = result.tracks.single()
            assertEquals("bilibili:BV123", track.id)
            assertEquals(187_000L, track.durationMs)
            val artwork = uri(checkNotNull(track.artworkUrl))
            assertEquals("https://www.bilibili.com/", track.artworkHeaders["Referer"])
            assertTrue(runtime.headersFor(artwork, "audio").isEmpty())
            assertTrue(runtime.headersFor(uri("https://other.example.test/"), "image").isEmpty())
        }
    }

    @Test fun pluginSourceMapsCursorMediaAndTimedCaptions() {
        val sample = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["api.example.test"]},
            "requestHeaders":[{"hosts":["api.example.test"],"kinds":["audio"],"headers":{"X-Quality":"{setting:quality}","Referer":"manifest"}}]}"""))
        fixture(
            """
            export function search({query,kind,offset}) {
                const track = {id: String(offset), name: query, artists: [{id:'person', name:'Artist'}], duration:1234};
                return {tracks: kind === 'tracks' ? [track, null] : [], more: offset === 0};
            }
            export function stream({id,quality}) {
                return {url: 'https://api.example.test/' + id + '/' + quality, type:'flac', headers:{referer:'custom'}};
            }
            export function video({quality}) { return {url:'https://api.example.test/video/' + quality}; }
            export function lyrics() {
                return {lines:[{start:500,end:1200,translation:'译文',words:[{start:500,end:900,text:'Lyric'}]}]};
            }
            """.trimIndent(), plugin = sample,
        ) { runtime, _, _ ->
            val source = PluginSource(sample, runtime)
            rejected { source.search("song", 2) }
            val first = source.search(" song ", 1)
            assertTrue(first.hasMore)
            assertEquals("fixture:0", first.tracks.single().id)
            assertEquals("Artist", first.tracks.single().artist)
            assertEquals("fixture:2", source.search("song", 2).tracks.single().id)
            val audio = source.audio("0", AudioQuality.Lossless)
            assertEquals("https://api.example.test/0/lossless", audio.url)
            assertEquals("flac", audio.fileExtension)
            assertEquals(mapOf("X-Quality" to "high", "referer" to "custom"), audio.headers)
            assertTrue(source.supportsVideo())
            assertEquals("https://api.example.test/video/high", source.video("0", AudioQuality.High).url)
            val lyrics = source.captions("0").single()
            assertEquals("Lyric", lyrics.text)
            assertEquals("译文", lyrics.translation)
            assertEquals(900L, lyrics.words.single().endMs)
            source.configure(mapOf("quality" to "low"), "en")
            assertEquals("low", source.audio("0").headers["X-Quality"])
        }
    }

    @Test fun catalogPreservesEntitiesNavigationAndRawPageCounts() = fixture(
        """
        const track = {id:'song', name:'Song'};
        export function home() { return {title:'Home',description:'Details',sections:[{
          title:'Catalog', morePage:'more', albums:[{id:'al',name:'Album'}], artists:[{id:'ar',name:'Artist'}],
          playlists:[{id:'pl',name:'List',mine:true}], shows:[{id:'sh',name:'Show'}], stations:[{id:'st',name:'Station'}]
        }]}; }
        export function browse({page}) { return {title:page,sections:[{tracks:[track],trackLayout:'videos'}]}; }
        export function album({id}) { return {album:{id,name:'Album name',description:'Album details'},tracks:[track]}; }
        export function show({id,offset}) { return {show:{id,name:'Podcast'},episodes:offset ? [] : [track,null],more:true}; }
        export function playlists() { return [{id:'pl',name:'List',mine:true}]; }
        export function account() { return {id:'me',name:'Me',page:'personal'}; }
        export function search({kind,query,offset}) { return {albums:[{id:String(offset),name:kind + ' ' + query},null],more:true}; }
        export function searchVideos({query,offset,kind}) {
          if (kind !== undefined) throw Error('Unexpected kind');
          if (offset === 0) return {tracks:[null,{}],more:true};
          return {tracks:[{id:String(offset),name:query}],more:false};
        }
        export function saveAlbum(args) { host.storage.set('save',args); }
        export function createPlaylist(args) { host.storage.set('create',args); }
        export function renamePlaylist(args) { host.storage.set('rename',args); }
        export function deletePlaylist(args) { host.storage.set('delete',args); }
        export function addToPlaylist(args) { host.storage.set('add',args); }
        export function removeFromPlaylist(args) { host.storage.set('remove',args); }
        export function movePlaylistTrack(args) { host.storage.set('move',args); }
        export function action({name}) { return host.storage.get(name); }
        """.trimIndent(),
    ) { runtime, _, _ ->
        val source = PluginSource(manifest, runtime)
        val catalog = source.catalog
        val home = catalog.load(PluginCatalogRequest.Home)
        val section = home.sections.single()
        assertEquals("Details", home.description)
        assertEquals(PluginEntityKind.entries.toList(), section.entities.map(PluginEntity::kind))
        val more = catalog.load(checkNotNull(section.more).destination)
        assertEquals("more", more.title)
        assertTrue(more.sections.single().videos)
        val album = section.entities.first()
        val albumPage = catalog.load(PluginCatalogRequest.Entity(album))
        assertEquals("Album details", albumPage.description)
        assertEquals("fixture:song", albumPage.sections.single().tracks.single().id)
        val playlist = catalog.load(PluginCatalogRequest.Collection(PluginCollection.Playlists, "Collections"))
            .sections.single().entities.single()
        assertTrue(playlist.editable)
        assertEquals("personal", source.account()?.page)
        val show = PluginCatalogRequest.Entity(section.entities.first { it.kind == PluginEntityKind.Show })
        assertEquals(2, catalog.load(show).nextOffset)
        assertEquals(null, catalog.load(show, offset = 2).nextOffset)
        val search = PluginCatalogRequest.Search("hello", PluginSearchKind.Albums)
        val results = catalog.load(search, offset = 2)
        assertEquals(4, results.nextOffset)
        assertEquals("albums hello", results.sections.single().entities.single().title)
        assertTrue(catalog.supports("searchVideos"))
        assertFalse(catalog.supports("stationNext"))
        val videoSearch = PluginCatalogRequest.Search("clip", PluginSearchKind.Videos)
        val emptyBatch = catalog.load(videoSearch)
        assertTrue(emptyBatch.sections.single().tracks.isEmpty())
        assertEquals(2, emptyBatch.nextOffset)
        val video = catalog.load(videoSearch, offset = 2).sections.single()
        assertTrue(video.videos)
        assertEquals("fixture:2", video.tracks.single().id)

        suspend fun action(name: String) = runtime.invoke("action", JSONObject().put("name", name)) as JSONObject
        catalog.setSaved(album, true)
        catalog.namePlaylist(null, "New")
        catalog.namePlaylist(playlist, "Updated")
        catalog.changePlaylistTracks(playlist, listOf("song"), add = true)
        catalog.changePlaylistTracks(playlist, listOf("song"), add = false)
        catalog.movePlaylistTrack(playlist, 2, 0)
        catalog.deletePlaylist(playlist)
        assertEquals("al", action("save").getString("id"))
        assertTrue(action("save").getBoolean("on"))
        assertEquals("New", action("create").getString("name"))
        assertEquals("Updated", action("rename").getString("name"))
        listOf("rename", "add", "remove", "move", "delete").forEach { assertEquals("pl", action(it).getString("id")) }
        listOf("add", "remove").forEach { assertEquals("song", action(it).getJSONArray("trackIds").getString(0)) }
        assertEquals(2, action("move").getInt("from"))
        assertEquals(0, action("move").getInt("to"))
    }

    @Test fun signInForwardsPromptAndOnlyScopedWebCookiesThenRestoresAndSignsOut() {
        val requestSeen = CompletableDeferred<PluginWebSignIn>()
        val release = CompletableDeferred<String>()
        fixture(
            """
            const options = {url:'https://api.example.test/signin',cookiesOf:'https://api.example.test/account',until:['session'],html:'<p>Fixture sign-in</p>'};
            export function outsideSignIn() { return host.webSignIn(options); }
            export async function signIn() {
                host.signInPrompt({qr:'https://api.example.test/scan',text:'Scan fixture'});
                if (host.capabilities?.webSignInHTML !== true) throw Error('missing document sign-in');
                const {cookie} = await host.webSignIn(options);
                if (cookie !== 'session=fixture') throw Error('wrong session');
                host.storage.set('profile',{id:'me',name:'Fixture profile',page:'personal'});
                return account();
            }
            export function account() { return host.storage.get('profile'); }
            export function signOut() { host.storage.remove('profile'); }
            """.trimIndent(),
            webSignIn = { request -> requestSeen.complete(request); release.await() },
        ) { runtime, _, _ ->
            val source = PluginSource(manifest, runtime)
            assertTrue(source.supportsSignIn())
            assertTrue(source.supportsSignOut())
            rejected { runtime.invoke("outsideSignIn") }
            assertFalse(requestSeen.isCompleted)
            val prompt = CompletableDeferred<String>()
            val login = async { source.signIn { prompt.complete(it.message) } }
            assertEquals("Scan fixture", prompt.await())
            assertEquals(setOf("session"), requestSeen.await().until)
            assertEquals("<p>Fixture sign-in</p>", requestSeen.await().html)
            source.configure(mapOf("quality" to "low"), "en")
            release.complete("session=fixture")
            assertEquals("Fixture profile", login.await().name)
            assertEquals("personal", source.account()?.page)
            source.signOut()
            assertEquals(null, source.account())
        }
    }

    @Test fun androidCallbackCompletesOnlyTheActiveQuickJsSignIn() {
        val broker = (app.applicationContext as MBApplication).pluginOAuth
        val exchangeSeen = CompletableDeferred<PluginFetchRequest>()
        fixture("""
            const options = {authorizationEndpoint:'https://api.example.test/authorize',tokenEndpoint:'https://api.example.test/token',
                clientID:'fixture',redirectURI:'$pluginOAuthRedirect',scopes:['profile']};
            export function outsideSignIn() { return host.oauthSignIn(options); }
            export async function signIn() { return await host.oauthSignIn(options); }
        """.trimIndent(), oauthSignIn = broker::authorize, transport = PluginTransport { request, _ ->
            check(exchangeSeen.complete(request))
            PluginFetchResponse(200, request.url, emptyMap(), emptyList(),
                """{"access_token":"fixture-access","token_type":"Bearer"}""".encodeToByteArray())
        }) { runtime, _, _ ->
            rejected { runtime.invoke("outsideSignIn") }
            assertFalse(exchangeSeen.isCompleted)
            val signIn = async { runtime.signIn {} }
            val page = broker.requests.filterNotNull().first()
            val browser = checkNotNull(broker.begin(page.id)).toASCIIString().toHttpUrl()
            val state = checkNotNull(browser.queryParameter("state"))
            val callback = "$pluginOAuthRedirect?code=fixture-code&state=$state"
            app.startActivity(Intent(Intent.ACTION_VIEW, callback.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val token = signIn.await() as JSONObject
            assertEquals("fixture-access", token.getString("access_token"))
            val exchange = exchangeSeen.await()
            assertEquals("https://api.example.test/token", exchange.url.toASCIIString())
            assertEquals(PluginRedirectMode.Error, exchange.redirectMode)
            assertTrue(exchange.body.orEmpty().split('&').contains("code=fixture-code"))
            assertFalse(broker.callback(uri(callback)))
        }
    }

    @Test fun signInRecordsBoundedDiagnosticsWithoutSavingRequestOrAccountData() {
        var timeout = false
        fixture(
            """
            export async function signIn() {
                for (let attempt = 0; attempt < 34; attempt++) {
                    await fetch('https://api.example.test/sensitive-route/' + attempt + '?t=fixture-query', {
                        method: 'POST', headers: {Authorization: 'Bearer fixture-header'}, body: 'fixture-body'
                    });
                }
                return {id:'signed-in'};
            }
            export async function home() { return (await fetch('https://api.example.test/home')).status; }
            """.trimIndent(),
            transport = PluginTransport { request, _ ->
                if (timeout) throw PluginException("Plugin network request failed", pluginNetworkCode,
                    SocketTimeoutException("fixture-exception-message"))
                PluginFetchResponse(403, request.url, mapOf("X-Fixture" to "fixture-response-header"),
                    listOf("session=fixture-cookie"),
                    """{"code":-1,"status_info":{"code":"42"},"data":{"error_code":2147483648,"account":"fixture-account"}}"""
                        .encodeToByteArray())
            },
        ) { runtime, _, data ->
            val reportFile = File(data, "sign-in-diagnostics.json")
            assertEquals("signed-in", (runtime.signIn {} as JSONObject).getString("id"))
            val contents = reportFile.readText()
            val report = JSONObject(contents)
            assertTrue(report.getBoolean("completed"))
            val entries = report.getJSONArray("entries")
            assertEquals(32, entries.length())
            val entry = entries.getJSONObject(0)
            assertEquals("api.example.test", entry.getString("host"))
            fun hash(path: String) = MessageDigest.getInstance("SHA-256").digest(path.encodeToByteArray()).toHexString()
            assertEquals(hash("/sensitive-route/2"), entry.getString("pathHash"))
            assertEquals(hash("/sensitive-route/33"), entries.getJSONObject(31).getString("pathHash"))
            assertEquals(403, entry.getInt("status"))
            assertEquals("[-1,42]", entry.getJSONArray("serviceCodes").toString())
            assertFalse(contents.contains("fixture-"))
            assertFalse(contents.contains("sensitive-route"))
            assertEquals(403, runtime.invoke("home"))
            assertEquals(contents, reportFile.readText())

            timeout = true
            try {
                runtime.signIn {}
                fail("The login should fail before receiving a response")
            } catch (error: PlaybackIssueException) { assertEquals(PlaybackIssue.Network, error.issue) }
            val failedContents = reportFile.readText()
            assertFalse(failedContents.contains("fixture-"))
            val failure = JSONObject(failedContents)
            assertFalse(failure.getBoolean("completed"))
            val failures = failure.getJSONArray("entries")
            assertEquals(1, failures.length())
            assertEquals("SocketTimeoutException", failures.getJSONObject(0).getString("failureType"))
            assertFalse(failures.getJSONObject(0).has("status"))
        }
    }

    @Test fun canceledSignInKeepsOnlyCompletedRequestDiagnostics() {
        val polling = CompletableDeferred<Unit>()
        fixture(
            """
            export async function signIn() {
                await fetch('https://api.example.test/start');
                await fetch('https://api.example.test/poll');
            }
            """.trimIndent(),
            transport = PluginTransport { request, _ ->
                if (request.url.path == "/poll") {
                    polling.complete(Unit)
                    awaitCancellation()
                }
                PluginFetchResponse(202, request.url, emptyMap(), emptyList(), "not JSON".encodeToByteArray())
            },
        ) { runtime, _, data ->
            val attempt = async { runtime.signIn {} }
            polling.await()
            attempt.cancelAndJoin()
            val report = JSONObject(File(data, "sign-in-diagnostics.json").readText())
            assertFalse(report.getBoolean("completed"))
            val entries = report.getJSONArray("entries")
            assertEquals(1, entries.length())
            assertEquals(202, entries.getJSONObject(0).getInt("status"))
            assertFalse(entries.getJSONObject(0).has("serviceCodes"))
        }
    }

    private fun fixture(
        script: String,
        plugin: PluginManifest = manifest,
        transport: PluginTransport = PluginTransport { _, _ -> throw PluginException("Unexpected request") },
        webSignIn: suspend (PluginWebSignIn) -> String = { throw PluginException("Unexpected web sign-in") },
        openURL: suspend (PluginWebLink) -> Unit = { throw PluginException("Unexpected web link") },
        oauthSignIn: suspend (PluginOAuthRequest) -> PluginFetchRequest = { throw PluginException("Unexpected OAuth sign-in") },
        run: suspend kotlinx.coroutines.CoroutineScope.(PluginRuntime, File, File) -> Unit,
    ) = runBlocking {
        val parent = File(app.cacheDir, "plugin-test-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(parent, "code").apply { mkdirs() }
        val data = File(parent, "data")
        File(root, "index.js").writeText(script)
        val runtime = PluginRuntime(app.assets, root, data, plugin, mapOf("quality" to "high"),
            transport = transport, webSignIn = webSignIn, openURL = openURL, oauthSignIn = oauthSignIn)
        try { withTimeout(10_000) { run(runtime, root, data) } }
        finally {
            runtime.close()
            val link = File(root, "link.js")
            Files.deleteIfExists(link.toPath())
            parent.deleteRecursively()
        }
    }

    private suspend fun rejected(action: suspend () -> Any) {
        try {
            action()
            fail("Plugin operation should have failed")
        } catch (error: PluginException) { assertNotNull(error.message) }
    }
}
