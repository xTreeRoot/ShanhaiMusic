import Foundation
@testable import MBFoundation
import Testing

private func withPlugin(_ source: String, extra: [String: String] = [:], isolation: isolated (any Actor)? = #isolation, perform: (PluginRuntime, URL, PluginManifest) async throws -> Void) async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try source.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    for (name, text) in extra { try text.write(to: root.appendingPathComponent(name), atomically: true, encoding: .utf8) }
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","name":"Fixture","version":"1.0.0","permissions":{"network":["api.example.test"]}}"#.utf8))
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, settings: ["quality": "high"])
    try await perform(runtime, root, manifest)
}

@Test @MainActor func pluginVideoSearchUsesRawOffsetsAndKeepsTrackSearchIndependent() async throws {
    try await withPlugin("""
    export function search({query,kind,offset}) {
      if (kind !== 'tracks' || offset !== 0) throw Error('unexpected track request');
      return {tracks:[{id:'music',name:query}],more:false};
    }
    export function searchVideos({query,offset}) {
      const video = {id:'first',name:query};
      if (offset === 0) return {tracks:[{},video,video],more:true};
      if (offset === 3) return {tracks:[{},video],more:true};
      if (offset === 5) return {tracks:[{id:'next',name:query}],more:false};
      throw Error('unexpected video offset ' + offset);
    }
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let music = SearchModel(source: source)
        let videos = SearchModel(videoSource: source)
        try await #require(source.supports("searchVideos"))
        await videos.search(" video ")
        #expect(videos.tracks.map(\.sourceTrackID) == ["first"])
        #expect(videos.hasMore)
        await music.search("music")
        await videos.loadMore()
        #expect(videos.tracks.map(\.sourceTrackID) == ["first"])
        #expect(videos.hasMore)
        await videos.loadMore()
        #expect(videos.tracks.map(\.sourceTrackID) == ["first", "next"])
        #expect(!videos.hasMore)
        #expect(videos.query == "video")
        #expect(music.query == "music")
        #expect(music.tracks.map(\.sourceTrackID) == ["music"])
        await videos.search("new")
        #expect(videos.tracks.map(\.title) == ["new"])
    }
}

@Test func pluginRunsRealESModulesAsyncTimersStorageAndCrypto() async throws {
    try await withPlugin("""
    import { name } from './helper.js';
    export async function search({query, offset}) {
      await new Promise(resolve => setTimeout(resolve, 1));
      host.storage.set('previous', {query, offset});
      return {name, quality:host.setting('quality'), stored:host.storage.get('previous'), digest:host.crypto.md5('abc'), encrypted:host.crypto.aesEcbEncrypt('0123456789abcdef','abc')};
    }
    export function previous() { return host.storage.get('previous'); }
    """, extra: ["helper.js": "export const name = 'module';"]) { runtime, root, manifest in
        let supports = try await runtime.supports("search")
        #expect(supports)
        #expect(try await !runtime.supports("video"))
        let value = try await runtime.invoke("search", arguments: .object(["query": .string("hello"), "offset": .number(25)]))
        #expect(value["name"] == .string("module"))
        #expect(value["quality"] == .string("high"))
        #expect(value["stored"]["query"] == .string("hello"))
        #expect(value["digest"] == .string("900150983cd24fb0d6963f7d28e17f72"))
        #expect(value["encrypted"] == .string("1d25821c3e311eea2d4dd8633a25c1b5"))
        let reopened = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest)
        #expect(try await reopened.invoke("previous")["offset"] == .number(25))
    }
}

@Test func pluginFetchKeepsBodyHeadersAndRejectsUnpermittedHost() async throws {
    try await withPlugin("""
    export async function search() {
      const params = new URLSearchParams({q:'你好 + !', page:'2'});
      const url = new URL('/find', 'https://api.example.test/root');
      const response = await fetch(url, {method:'POST', headers:{'X-Fixture':'yes'}, body:params});
      return {data:await response.json(), header:response.headers.get('X-Answer'), status:response.status};
    }
    export async function forbidden() { return await fetch('https://other.example.test/'); }
    """) { _, root, manifest in
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, transport: { request, permissions in
            #expect(request.url?.absoluteString == "https://api.example.test/find")
            #expect(request.httpMethod == "POST")
            #expect(request.value(forHTTPHeaderField: "X-Fixture") == "yes")
            #expect(String(data: request.httpBody ?? Data(), encoding: .utf8) == "q=%E4%BD%A0%E5%A5%BD+%2B+%21&page=2")
            #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/x-www-form-urlencoded;charset=UTF-8")
            return (Data(#"{"ok":true}"#.utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["X-Answer": "done"])!)
        })
        let result = try await runtime.invoke("search")
        #expect(result["data"]["ok"] == .bool(true))
        #expect(result["header"] == .string("done"))
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("forbidden") }
    }
}

@Test func pluginInterruptsInfiniteLoopAndRecoversOnNextCall() async throws {
    try await withPlugin("export function loop(){while(true){}} export function ok(){return 42;}") { runtime, _, _ in
        let started = ContinuousClock.now
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("loop") }
        #expect(ContinuousClock.now - started < .seconds(2))
        let recovered = try await runtime.invoke("ok")
        #expect(recovered == .number(42))
    }
}

@Test func pluginParsesLargePublicResponsesWithoutRelaxingExportLoopProtection() async throws {
    try await withPlugin("""
    export async function parse() {
      const value = await (await fetch('https://api.example.test/large')).json();
      return value.rows.filter(row => row.title.includes('fixture')).length;
    }
    export function loop() { while (true) {} }
    export function ok() { return 42; }
    """) { _, root, manifest in
        let body = try JSONSerialization.data(withJSONObject: ["rows": Array(repeating: ["title": String(repeating: "fixture", count: 20)], count: 7_000)])
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, transport: { request, _ in
            (body, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        })
        #expect(try await runtime.invoke("parse") == .number(7_000))
        let started = ContinuousClock.now
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("loop") }
        #expect(ContinuousClock.now - started < .seconds(2))
        #expect(try await runtime.invoke("ok") == .number(42))
    }
}

@Test func pluginCancellationStopsPendingTimerAndDoesNotBlockNextInvocation() async throws {
    try await withPlugin("export async function wait(){await new Promise(resolve=>setTimeout(resolve,60000));return 1;} export function ok(){return 2;}") { runtime, _, _ in
        let operation = Task { try await runtime.invoke("wait", timeout: .seconds(90)) }
        try await Task.sleep(for: .milliseconds(100))
        operation.cancel()
        await #expect(throws: CancellationError.self) { try await operation.value }
        #expect(try await runtime.invoke("ok") == .number(2))
    }
}

@Test func pluginRejectsDirectoryEscapeAndNewerHostRequirement() async throws {
    try await withPlugin("import {bad} from '../outside.js'; export function search(){return bad;}") { runtime, _, _ in
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("search") }
    }
    for json in [#"{"id":"../escape"}"#, #"{"id":"fixture","minAppVersion":"99.0.0"}"#, #"{"id":"fixture","permissions":{"process":["ffmpeg"]}}"#, #"{"id":"fixture","requestHeaders":[{"hosts":["other.test"],"headers":{"Cookie":"secret"}}]}"#] {
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(json.utf8))
        #expect(throws: PluginFailure.self) { try manifest.validate() }
    }
}

@Test func desktopBilibiliPluginRunsUnmodifiedInIOSRuntimeWithSignedSearchContract() async throws {
    let directory = try #require(Bundle.module.url(forResource: "Fixtures/BilibiliPlugin", withExtension: nil))
    let data = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: data) }
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: directory.appendingPathComponent("plugin.json")))
    let runtime = try PluginRuntime(directory: directory, dataDirectory: data, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        let body: String
        if url.path == "/x/web-interface/nav" {
            body = #"{"code":-101,"data":{"wbi_img":{"img_url":"https://i.example/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"}}}"#
        } else if url.path == "/x/web-interface/wbi/search/type" {
            let values = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            #expect(values.first { $0.name == "keyword" }?.value == "中文 music")
            #expect(values.first { $0.name == "w_rid" }?.value?.count == 32)
            body = #"{"code":0,"data":{"numPages":1,"result":[{"bvid":"BV123","title":"<em>Test</em>","author":"UP","pic":"//i0.hdslb.com/image.jpg","duration":"3:07"}]}}"#
        } else { throw PluginFailure.invalid("Unexpected fixture request") }
        return (Data(body.utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    })
    let source = PluginSource(manifest: manifest, runtime: runtime)
    let page = try await source.search(query: "中文 music", page: 1)
    #expect(page.tracks.count == 1)
    #expect(page.tracks.first?.sourceID == "bilibili")
    #expect(page.tracks.first?.sourceTrackID == "BV123")
    #expect(page.tracks.first?.durationMilliseconds == 187_000)
    #expect(page.tracks.first?.artworkHeaders["Referer"] == "https://www.bilibili.com/")
}

@Test func pluginBackgroundTimersSurviveFunctionReturnAndURLMutationsStaySynchronized() async throws {
    try await withPlugin("""
    let count = 0, timer, wake;
    export function start() { timer=setInterval(()=>{count++; if(wake) wake(true)},5); return 1; }
    export function waitTick() { return count > 0 ? true : new Promise(resolve=>wake=resolve); }
    export function read() { clearInterval(timer); const u=new URL('https://api.example.test/a');u.searchParams.set('q','hello'); const href=u.href;u.search='x=2';return {count,href,search:u.searchParams.get('x')}; }
    """) { runtime, _, _ in
        #expect(try await runtime.invoke("start") == .number(1))
        #expect(try await runtime.invoke("waitTick") == .bool(true))
        let value = try await runtime.invoke("read")
        #expect((value["count"].integer ?? 0) > 0)
        #expect(value["href"].string == "https://api.example.test/a?q=hello")
        #expect(value["search"].string == "2")
    }
    #expect(PluginRuntime.setCookies("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT, b=2; Path=/") == ["a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT", "b=2; Path=/"])
}

@Test func pluginCatalogPreservesEntitiesDetailsAccountPageAndOptionalCapabilities() async throws {
    try await withPlugin("""
    const track={id:'t',name:'Track',artists:[{id:'a',name:'Artist'}],album:{},duration:1000};
    export function home(){return {title:'Home',description:'Details',sections:[{title:'Catalog',albums:[{id:'al',name:'Album'}],artists:[{id:'ar',name:'Artist'}],playlists:[{id:'pl',name:'List',mine:true}],shows:[{id:'sh',name:'Show'}],stations:[{id:'st',name:'Station'}]}]};}
    export function album(){return {album:{id:'al',name:'Album'},tracks:[track]};}
    export function playlists(){return [{id:'pl',name:'List',mine:true}]}
    export function account(){return {id:'me',name:'Me',page:'personal'}}
    export function comments(){return {comments:[],more:false}}
    export function search(){return {albums:[{id:'al',name:'Album'}],more:false}}
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let home = try await source.home()
        #expect(home.description == "Details")
        #expect(home.sections.first?.entities.map(\.kind) == [.album, .artist, .playlist, .show, .station])
        let album = try #require(home.sections.first?.entities.first)
        #expect(try await source.entity(album).0.sections.first?.tracks.first?.sourceID == "fixture")
        #expect(try await source.collection("playlists").0.sections.first?.entities.first?.sourceID == "pl")
        #expect(try await source.account()?.page == "personal")
        let capabilities = await source.capabilities()
        #expect(capabilities.comments && !capabilities.likes && !capabilities.posting)
        #expect(try await source.searchCatalog("q", kind: "albums", offset: 0).2 == 1)
    }
}

@Test func pluginWebSignInUsesDeclaredSecureTargetsAndCancellationCompletesBroker() async throws {
    try await withPlugin("""
    export async function signIn(){return await host.webSignIn({url:'https://api.example.test/login',cookiesOf:'https://api.example.test/',until:['session']})}
    export async function forbidden(){return await host.webSignIn({url:'https://evil.test/login',cookiesOf:'https://api.example.test/',until:['session']})}
    """) { _, root, manifest in
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, webSignIn: { request in
            #expect(request.until == ["session"])
            return .object(["cookie": .string("session=fixture")])
        })
        #expect(try await runtime.invoke("signIn")["cookie"] == .string("session=fixture"))
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("forbidden") }
    }
}

@Test @MainActor func pluginWebSignInBrokerRejectsLateCompletionAfterCancel() async throws {
    let service = PluginUIService()
    let request = PluginWebSignIn(title: "Fixture", url: URL(string: "https://example.test")!, cookiesOf: URL(string: "https://example.test")!, until: ["session"], userAgent: nil)
    let task = Task { try await service.show(request) }
    while service.signIn == nil { await Task.yield() }
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
    service.finish(request.id, cookie: "late=1")
    #expect(service.signIn == nil)
}

@Test func pluginOpenURLAcceptsWebPagesAndRejectsLocalFiles() async throws {
    try await withPlugin("export async function page(){await host.openURL('https://example.test/help')} export async function local(){await host.openURL('file:///etc/passwd')}") { _, root, manifest in
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, openURL: { url, title in
            #expect(url.absoluteString == "https://example.test/help")
            #expect(title == "Fixture")
        })
        _ = try await runtime.invoke("page")
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("local") }
    }
}

@Test func pluginOAuthBridgeChecksPermissionsAndCancelsPendingAuthorization() async throws {
    try await withPlugin("""
    const options = {authorizationEndpoint:'https://api.example.test/authorize', tokenEndpoint:'https://api.example.test/token', clientID:'public-client', redirectURI:'mb-fixture:/oauthredirect', scopes:['library']};
    export async function signIn(){return await host.oauthSignIn(options)}
    export async function forbidden(){return await host.oauthSignIn({...options,tokenEndpoint:'https://other.test/token'})}
    export function ok(){return 1}
    """) { _, root, manifest in
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, oauthSignIn: { request in
            #expect(request.clientID == "public-client" && request.scopes == ["library"])
            return .object(["access_token": .string("synthetic-token")])
        })
        #expect(try await runtime.invoke("signIn")["access_token"] == .string("synthetic-token"))
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("forbidden") }
        let pending = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("pending"), manifest: manifest, oauthSignIn: { _ in
            try await Task.sleep(for: .seconds(60))
            return .null
        })
        let task = Task { try await pending.invoke("signIn", timeout: .seconds(90)) }
        try await Task.sleep(for: .milliseconds(100))
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(try await pending.invoke("ok") == .number(1))
    }
}

@Test func signInDiagnosticsKeepFailureCodesWithoutRequestOrAccountSecrets() async throws {
    try await withPlugin("""
    export async function signIn() {
      await fetch('https://api.example.test/login/private-key?token=private-token', {headers:{Cookie:'session=private-cookie'}});
      return {id:'fixture',name:'Fixture'};
    }
    """) { _, root, manifest in
        let directory = root.appendingPathComponent("data")
        let runtime = try PluginRuntime(directory: root, dataDirectory: directory, manifest: manifest, transport: { _, _ in
            throw URLError(.networkConnectionLost)
        })
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("signIn") }
        let text = try String(contentsOf: directory.appendingPathComponent("sign-in-diagnostics.json"), encoding: .utf8)
        let report = try JSONDecoder().decode(PluginJSON.self, from: Data(text.utf8))
        #expect(report["completed"] == .bool(false))
        #expect(report["entries"].array.first?["errorCode"].integer == URLError.networkConnectionLost.rawValue)
        #expect(report["entries"].array.first?["pathHash"].string == PluginSignInDiagnostics.pathHash("/login/private-key"))
        for secret in ["private-key", "private-token", "private-cookie", "Cookie", "/login"] { #expect(!text.contains(secret)) }
    }
}

@Test func signInDiagnosticsKeepOnlyBoundedServiceCodesFromResponse() async throws {
    try await withPlugin("""
    export async function signIn() {
      await fetch('https://api.example.test/login'); return true;
    }
    """) { _, root, manifest in
        let directory = root.appendingPathComponent("data")
        let runtime = try PluginRuntime(directory: root, dataDirectory: directory, manifest: manifest, transport: { request, _ in
            let text = #"{"code":0,"status_code":"2046","error_code":9876543211234567,"data":{"error_code":1105,"cookie":"response-secret"},"message":"private-message","account":"private-account"}"#
            return (Data(text.utf8), try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: [:])))
        })
        #expect(try await runtime.invoke("signIn") == .bool(true))
        let text = try String(contentsOf: directory.appendingPathComponent("sign-in-diagnostics.json"), encoding: .utf8)
        let report = try JSONDecoder().decode(PluginJSON.self, from: Data(text.utf8))
        #expect(report["entries"].array.first?["serviceCodes"].array.compactMap(\.integer) == [0, 1105, 2046])
        for secret in ["response-secret", "private-message", "private-account", "9876543211234567"] { #expect(!text.contains(secret)) }
    }
}

@Test func pluginMediaProblemsUseCodesAndNeverGuessFromMessages() async throws {
    try await withPlugin("""
    export function search() { return {tracks:[{id:'one',name:'Track',unavailableReason:'vip'}], more:false}; }
    export async function stream({id}) {
      switch (id) {
        case 'one': await new Promise(resolve => setTimeout(resolve, 0)); throw Object.assign(new Error('blocked'), {code:'vip'});
        case 'two': return {url:''};
        case 'three': throw new Error('VIP only');
        default: return {url:'bad-url'};
      }
    }
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let tracks = try await source.search(query: "Track", page: 1).tracks
        #expect(tracks.first?.declaredProblem == .vip)
        for id in ["one", "two", "three", "four"] {
            do {
                _ = try await source.audio(trackID: id)
                Issue.record("Expected resolution to fail for \(id)")
            } catch PluginFailure.playback(let problem, _) {
                #expect(id == "one" && problem == .vip)
            } catch let problem as TrackProblem {
                #expect(id == "two" && problem == .noSource)
            } catch PluginFailure.execution {
                #expect(id == "three" || id == "four")
            } catch {
                Issue.record("Unexpected media error \(error)")
            }
        }
    }
}


@Test func pluginInterruptsInfiniteModuleInitialization() async throws {
    try await withPlugin("while(true){} export function ok(){return 42;}") { runtime, _, _ in
        let started = ContinuousClock.now
        await #expect(throws: PluginFailure.self) { try await runtime.supports("ok") }
        #expect(ContinuousClock.now - started < .seconds(2))
    }
}

private actor PluginRedirectFixture {
    private(set) var paths: [String] = []
    func response(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        #expect(url.host == "api.example.test")
        paths.append(url.path)
        if url.path == "/done" {
            #expect(request.httpMethod == "GET")
            #expect(request.httpBody == nil)
            #expect(request.value(forHTTPHeaderField: "Cookie") == "pending=synthetic")
            return (Data("done".utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
        }
        let location = url.path == "/outside" ? "https://outside.example.test/" : url.path == "/loop" ? "/loop" : "/done"
        return (Data("pending".utf8), try #require(HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil,
            headerFields: ["Location": location, "Set-Cookie": "session=synthetic; Path=/; HttpOnly"])))
    }
}

@Test func pluginFetchRedirectModesPreserveManualSessionAndBoundFollowing() async throws {
    try await withPlugin("""
    export async function read({redirect='follow',path='/start'}) {
      const response=await fetch('https://api.example.test'+path,{method:'POST',body:'ticket=synthetic',headers:{Cookie:'pending=synthetic'},redirect});
      return {status:response.status,body:await response.text(),url:response.url,cookies:response.headers.getSetCookie(),location:response.headers.get('location')};
    }
    """) { _, root, manifest in
        let fixture = PluginRedirectFixture()
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest,
                                        transport: { request, _ in try await fixture.response(request) })
        let manual = try await runtime.invoke("read", arguments: .object(["redirect": .string("manual")]))
        #expect(manual["status"] == .number(302))
        #expect(manual["body"] == .string("pending"))
        #expect(manual["location"] == .string("/done"))
        #expect(manual["cookies"].array == [.string("session=synthetic; Path=/; HttpOnly")])
        #expect(await fixture.paths == ["/start"])
        let followed = try await runtime.invoke("read")
        #expect(followed["status"] == .number(200))
        #expect(followed["url"] == .string("https://api.example.test/done"))
        #expect(followed["body"] == .string("done"))
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("read", arguments: .object(["redirect": .string("error")])) }
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("read", arguments: .object(["path": .string("/outside")])) }
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("read", arguments: .object(["redirect": .string("unknown")])) }
        await #expect(throws: PluginFailure.self) { try await runtime.invoke("read", arguments: .object(["path": .string("/loop")])) }
        #expect(await fixture.paths == ["/start", "/start", "/done", "/start", "/outside"] + Array(repeating: "/loop", count: 10))
    }
}

@Test func pluginWebSignInDocumentUsesPermittedOriginAndRejectsInvalidDocuments() async throws {
    try await withPlugin("""
    export const supported=()=>host.capabilities.webSignInHTML;
    const options={url:'https://api.example.test/passport/',cookiesOf:'https://api.example.test/',until:['session'],html:'<!doctype html><meta charset="utf-8"><p>登录</p>'};
    export const signIn=()=>host.webSignIn(options);
    export const wrongPort=()=>host.webSignIn({...options,cookiesOf:'https://api.example.test:8443/'});
    export const oversized=()=>host.webSignIn({...options,html:'x'.repeat(262145)});
    export const wrongType=()=>host.webSignIn({...options,html:42});
    """) { _, root, manifest in
        let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, webSignIn: { request in
            #expect(request.url.absoluteString == "https://api.example.test/passport/")
            #expect(request.html?.contains("登录") == true)
            return .object(["cookie": .string("session=fixture")])
        })
        #expect(try await runtime.invoke("supported") == .bool(true))
        #expect(try await runtime.invoke("signIn")["cookie"] == .string("session=fixture"))
        for method in ["wrongPort", "oversized", "wrongType"] {
            await #expect(throws: PluginFailure.self) { try await runtime.invoke(method) }
        }
    }
}

@Test @MainActor func pluginWebSignInBrokerRejectsLateCookiesAfterPageFailure() async throws {
    let service = PluginUIService()
    let request = PluginWebSignIn(title: "Fixture", url: URL(string: "https://example.test")!, cookiesOf: URL(string: "https://example.test")!, until: ["session"], userAgent: nil, html: "<p>Fixture</p>")
    let task = Task { try await service.show(request) }
    while service.signIn == nil { await Task.yield() }
    service.fail(request.id, message: "平台拒绝（7）")
    service.finish(request.id, cookie: "session=late")
    await #expect(throws: PluginFailure.self) { try await task.value }
    #expect(service.signIn == nil)
}

@Test func personalCatalogCachesPagesAcrossRecreationAndSeparatesAccountsAndMutations() async throws {
    try await withPlugin("""
    let who = 'a', calls = 0;
    export function account() { return {id:who,name:who}; }
    export function signOut() { who = 'guest'; }
    export function signIn() { who = 'b'; return account(); }
    export function liked({offset}) { calls++; return {tracks:[{id:who+offset,name:who+calls}],more:offset===0}; }
    export function like() {}
    export function callsCount() { return calls; }
    """) { runtime, _, manifest in
        let first = PluginSource(manifest: manifest, runtime: runtime)
        let (page, more, count) = try await first.collection("liked")
        #expect(page.sections.first?.tracks.first?.title == "a1")
        #expect(more && count == 1)
        let reopened = PluginSource(manifest: manifest, runtime: runtime)
        let again = try await reopened.collection("liked")
        #expect(again.0.sections.first?.tracks.first?.title == "a1")
        _ = try await reopened.collection("liked", offset: 1)
        #expect(try await runtime.invoke("callsCount") == .number(2))
        try await reopened.signOut()
        _ = try await reopened.collection("liked")
        _ = try await reopened.signIn { _ in }
        let other = try await reopened.collection("liked")
        #expect(other.0.sections.first?.tracks.first?.sourceTrackID == "b0")
        #expect(try await runtime.invoke("callsCount") == .number(4))
        try await reopened.like(trackID: "b0", enabled: true)
        _ = try await reopened.collection("liked")
        #expect(try await runtime.invoke("callsCount") == .number(5))
        await reopened.invalidateCatalog()
        _ = try await reopened.collection("liked")
        #expect(try await runtime.invoke("callsCount") == .number(6))
    }
}

@Test func discoveryRefreshBypassesCacheAndWaitsForIndependentNavigationPreviews() async throws {
    try await withPlugin("""
    let homes = 0, browses = 0, likes = 0;
    export function home() { return {sections:[{title:'Home '+(++homes)}]}; }
    export function browse() { return {sections:[{tracks:[{id:'nav',name:'Navigation '+(++browses)}]}]}; }
    export function liked() { return {tracks:[{id:'liked',name:'Liked '+(++likes)}]}; }
    """) { runtime, _, _ in
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","name":"Fixture","version":"1.0.0","navigation":[{"builtin":"home"},{"page":"category","title":"Category"},{"builtin":"liked","title":"Liked"}]}"#.utf8))
        let source = PluginSource(manifest: manifest, runtime: runtime)
        _ = try await source.home(); _ = try await source.browse("category"); _ = try await source.collection("liked")
        #expect(try await source.home().sections.first?.title == "Home 1")
        let snapshot = try await source.refreshDiscovery()
        #expect(snapshot.home.sections.first?.title == "Home 2")
        #expect(snapshot.previews["page:category"]?.content?.sections.first?.tracks.first?.title == "Navigation 2")
        #expect(snapshot.previews["collection:liked"]?.content?.sections.first?.tracks.first?.title == "Liked 2")
        #expect(snapshot.previews.values.allSatisfy { $0.error == nil })
        #expect(try await source.browse("category").sections.first?.tracks.first?.title == "Navigation 2")
    }
}

@Test func pluginVideoQualityPreservesRealChoicesAndPassesPreferenceInsteadOfAudioQuality() async throws {
    try await withPlugin("""
    export function video({quality}) { return {url:'https://api.example.test/video.mp4',type:'mp4',videoQuality:quality==='1080p'?'720p':quality,
        videoQualities:[{id:'1080p',label:'1080p60'},{id:'720p',label:'720p'},{id:'720p',label:'Duplicate'},{id:'bad key',label:'invalid'}]}; }
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let track = Track(sourceID: manifest.id, sourceTrackID: "one", title: "One", artist: "")
        let media = try await source.video(track: track, qualityID: "1080p")
        #expect(media.videoQuality == "720p")
        #expect(media.videoQualities.map(\.id) == ["1080p", "720p"])
        #expect(try await source.video(track: track, qualityID: "720p").videoQuality == "720p")
    }
}

@Test func personalNavigationIsNotPrefetchedOnDiscoveryAndRetainsBrowseAndCollectionRoutes() async throws {
    try await withPlugin("""
    let personalRequests=0;
    export function home(){return {sections:[]};}
    export function browse({page}){personalRequests++;return {sections:[{tracks:[{id:page,name:page}]}]};}
    export function liked(){personalRequests++;return {tracks:[{id:'liked',name:'Liked'}]};}
    export function count(){return personalRequests;}
    """) { runtime, _, _ in
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","navigation":[{"builtin":"home"},{"page":"subscriptions","placement":"personal"},{"page":"channel","placement":"personal"},{"builtin":"liked","placement":"personal"},{"page":"music","placement":"personal"}]}"#.utf8))
        let source = PluginSource(manifest: manifest, runtime: runtime)
        #expect(try await source.refreshDiscovery().previews.isEmpty)
        #expect(try await runtime.invoke("count") == .number(0))
        for entry in source.personalNavigation {
            let result: PluginBrowse
            if let page = entry.page { result = try await source.browse(page) }
            else { result = try await source.collection(entry.builtin!).0 }
            #expect(result.sections.first?.tracks.count == 1)
        }
        #expect(try await runtime.invoke("count") == .number(4))
    }
}

@Test func accountNetworkFailureDoesNotBlockPublicHomeOrCacheUnknownAccountData() async throws {
    try await withPlugin("""
    export function account() { throw Error('temporary account failure'); }
    export function home() { const calls = (host.storage.get('calls') || 0) + 1; host.storage.set('calls', calls); return {title:'Public',sections:[{tracks:[{id:'one',name:String(calls)}]}]}; }
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let first = try await source.home(), second = try await source.home()
        #expect(first.sections.first?.tracks.first?.title == "1")
        #expect(second.sections.first?.tracks.first?.title == "2")
    }
}

@Test func pagedBrowsePreservesPortraitAndResourceMetadata() async throws {
    try await withPlugin("""
    export function browse({page,offset}) { return {tabs:[{title:'All',page:'all'},{title:'Music',page:'music'}],selectedTab:page,sections:[{tracks:[{id:String(offset),name:'Portrait',video:{width:1080,height:1920,short:true}}]}],more:offset===0}; }
    export function searchResources({query,offset}) { return {tracks:[{id:String(offset),name:query,resourceType:'software',resourceUrl:'https://example.test/file'}],more:false}; }
    """) { runtime, _, manifest in
        let source = PluginSource(manifest: manifest, runtime: runtime)
        let (first, more, count) = try await source.browsePage("shorts")
        #expect(more && count == 1 && first.sections.first?.tracks.first?.videoHeight == 1920)
        #expect(first.tabs.map(\.page) == ["all", "music"] && first.selectedTab == "shorts")
        #expect(first.sections.first?.tracks.first?.shortVideo == true)
        let (next, again, _) = try await source.browsePage("shorts", offset: count)
        #expect(!again && next.sections.first?.tracks.first?.sourceTrackID == "1")
        let resource = try await source.searchResources(query: "Query", page: 2)
        #expect(resource.tracks.first?.sourceTrackID == "30" && resource.tracks.first?.resourceType == "software")
        #expect(resource.tracks.first?.resourceURL?.scheme == "https")
    }
}
