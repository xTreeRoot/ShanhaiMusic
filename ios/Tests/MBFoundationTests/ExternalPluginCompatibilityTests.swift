import Foundation
@testable import MBFoundation
import Testing

/// 验证 fork 的公开源码在 iOS QuickJS 中运行，无需桌面进程或个人账号。
@Test func youtubeIOSPluginResolvesAACWithoutDesktopProcesses() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/YouTubeIOSPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        let response: PluginJSON
        if url.path.hasSuffix("/visitor_id") {
            response = .object(["responseContext": .object(["visitorData": .string("fixture-visitor")])])
        } else {
            #expect(url.path.hasSuffix("/player"))
            let body = try JSONDecoder().decode(PluginJSON.self, from: #require(request.httpBody))
            #expect(body["context"]["client"]["clientName"] == .string("VISIONOS"))
            response = .object([
                "playabilityStatus": .object(["status": .string("OK")]),
                "streamingData": .object(["adaptiveFormats": .array([
                    .object(["mimeType": .string("audio/mp4; codecs=\"mp4a.40.2\""), "url": .string("https://r1.googlevideo.com/fixture.m4a"), "bitrate": .number(128000)])
                ])])
            ])
        }
        return (Data(try response.encoded().utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("dQw4w9WgXcQ"), "quality": .string("lossless")]))
    #expect(audio["type"] == .string("m4a"))
    #expect(audio["quality"] == .string("high"))
    #expect(audio["bitRate"] == .number(128000))
}

@Test func youtubeIOSPluginSystemOAuthValidatesBearerAndKeepsSessionDisabledAfterLogout() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/YouTubeIOSPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, oauthSignIn: { request in
        #expect(request.authorizationEndpoint.host == "accounts.google.com")
        #expect(request.scopes == ["https://www.googleapis.com/auth/youtube"])
        return .object(["access_token": .string("synthetic-access"), "refresh_token": .string("synthetic-refresh"),
                        "token_type": .string("Bearer"), "expires_in": .number(3600)])
    }, transport: { request, _ in
        #expect(request.url?.absoluteString == "https://youtube.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-access")
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        let text = #"{"items":[{"id":"UCfixture","snippet":{"title":"Fixture"}}]}"#
        return (Data(text.utf8), try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.invoke("signIn")["name"] == .string("Fixture"))
    _ = try await runtime.invoke("signOut")
    #expect(try await runtime.invoke("account") == .null)
    let stored = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: folder.appendingPathComponent("storage.json")))
    #expect(stored["oauthSession"]["active"] == .bool(false))
    #expect(stored["oauthSession"]["refresh"] == .string("synthetic-refresh"))
    #expect(try await runtime.invoke("signIn")["id"] == .string("UCfixture"))
}

@Test func sodaIOSPluginReturnsNativeAudioAndOffersAccountLogin() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        let model: PluginJSON = .object(["video_duration": .number(180), "video_list": .object([
            "high": .object(["main_url": .string("https://v1.douyinvod.com/fixture.m4a"), "video_meta": .object([
                "codec_type": .string("aac"), "vtype": .string("m4a"), "bitrate": .number(128000)
            ])])
        ])])
        let body: PluginJSON = .object(["track_player": .object(["video_model": .string(try model.encoded())]), "seo_track": .object(["track": .object(["duration": .number(180000)])])])
        return (Data(try body.encoded().utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.supports("signIn") == true)
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("123"), "quality": .string("lossless")]))
    #expect(audio["type"] == .string("m4a"))
    #expect(audio["quality"] == .string("high"))
}

@Test func sodaIOSPluginReadsVerifiedAccountAndPrivatePlaylistWithNativeQuickJS() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let saved = #"{"qishuiAccount":{"version":1,"id":"9012345678901234561","active":true,"cookies":[{"name":"sessionid","value":"synthetic-session","domain":"api.qishui.com","path":"/"}]}}"#
    try Data(saved.utf8).write(to: folder.appendingPathComponent("storage.json"))
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        #expect(url.host == "api.qishui.com")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-session")
        let text: String
        switch url.path {
        case "/luna/pc/me": text = #"{"my_info":{"id":9012345678901234561,"nickname":"汽水测试"}}"#
        case "/luna/pc/me/playlist": text = #"{"playlists":[{"id":9012345678901234562,"title":"私密歌单","type":2,"owner":{"id":9012345678901234561}}]}"#
        case "/luna/pc/playlist/detail": text = #"{"media_resources":[{"entity":{"track":{"id":9012345678901234563,"name":"Private track","vid":"fixture-vid","duration":123456}}}]}"#
        default: throw NetworkFailure.response
        }
        return (Data(text.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.invoke("account")["name"] == .string("汽水测试"))
    let page = try await runtime.invoke("browse", arguments: .object(["page": .string("my-library")]))
    #expect(page["sections"].array.first?["playlists"].array.first?["id"] == .string("account:9012345678901234562"))
    let list = try await runtime.invoke("playlist", arguments: .object(["id": .string("account:9012345678901234562")]))
    #expect(list["tracks"].array.first?["id"] == .string("9012345678901234563"))
    _ = try await runtime.invoke("signOut")
    #expect(try await runtime.invoke("account") == .null)
}

private actor SodaAccountRestoreFixture {
    private(set) var requests = 0

    func response(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        #expect(url.path == "/luna/pc/me")
        requests += 1
        let response: String
        let headers: [String: String]
        switch requests {
        case 1:
            #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-saved-cookie")
            response = #"{"status_code":0,"my_info":{"id":"990000077","nickname":"synthetic-account-name"}}"#
            headers = ["Set-Cookie": "sessionid=synthetic-renewed-cookie; Path=/; Secure"]
        case 2:
            throw URLError(.networkConnectionLost)
        default:
            #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-renewed-cookie")
            response = #"{"status_code":1000016,"message":"synthetic-private-message"}"#
            headers = [:]
        }
        return (Data(response.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: headers)))
    }
}

@Test func sodaIOSAccountDiagnosticsDistinguishMissingCacheNetworkFailureAndServiceRejection() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let fixture = SodaAccountRestoreFixture()
    let transport: PluginRuntime.Transport = { request, _ in try await fixture.response(request) }
    let empty = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: transport)
    #expect(try await empty.invoke("account") == .null)

    let saved = #"{"qishuiAccount":{"version":1,"id":"990000077","active":true,"cookies":[{"name":"sessionid","value":"synthetic-saved-cookie","domain":"api.qishui.com","path":"/"}]}}"#
    try Data(saved.utf8).write(to: folder.appendingPathComponent("storage.json"))
    let restored = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: transport)
    #expect(try await restored.invoke("account")["id"] == .string("990000077"))
    await #expect(throws: PluginFailure.self) { try await restored.invoke("account") }
    #expect(try await restored.invoke("account") == .null)
    #expect(try await restored.invoke("account") == .null)
    _ = try await restored.invoke("signOut")
    try await restored.clearLoginCache()
    #expect(await fixture.requests == 3)

    let text = try String(contentsOf: folder.appendingPathComponent("account-diagnostics.json"), encoding: .utf8)
    let reports = try JSONDecoder().decode([PluginJSON].self, from: Data(text.utf8))
    #expect(reports.compactMap { $0["operation"].string } == ["account", "account", "account", "account", "account", "signOut", "clearLoginCache"])
    let missing = try #require(reports.first)
    #expect(missing["outcome"] == .string("noAccount"))
    #expect(missing["storage"]["misses"].integer == 1)
    #expect(missing["entries"].array.isEmpty)

    let verified = try #require(reports.first { $0["outcome"] == .string("accountReturned") })
    #expect(verified["storage"]["writes"].integer == 1)
    #expect(verified["entries"].array.first?["setCookieCount"].integer == 1)
    let connectionLost = try #require(reports.first { $0["outcome"] == .string("failed") })
    #expect(connectionLost["storage"]["writes"].integer == 0)
    #expect(connectionLost["entries"].array.first?["errorCode"].integer == URLError.networkConnectionLost.rawValue)

    let rejected = try #require(reports.first { $0["entries"].array.first?["serviceCodes"].array.first?.integer == 1000016 })
    #expect(rejected["outcome"] == .string("noAccount"))
    #expect(rejected["storage"]["hits"].integer == 1)
    #expect(rejected["storage"]["writes"].integer == 1)
    #expect(rejected["entries"].array.first?["status"].integer == 200)
    #expect(rejected["entries"].array.first?["cookieHeaderPresent"] == .bool(true))
    #expect(rejected["entries"].array.first?["pathHash"].string == PluginSignInDiagnostics.pathHash("/luna/pc/me"))
    #expect(reports.dropFirst().contains { $0["operation"] == .string("account") && $0["storage"]["hits"].integer == 1 && $0["entries"].array.isEmpty })
    for secret in ["qishuiAccount", "sessionid", "synthetic-", "990000077", "/luna/pc/me", "aid="] { #expect(!text.contains(secret)) }
}

private actor SodaBrowserLoginFixture {
    private(set) var devices = 0
    private(set) var profiles = 0
    private(set) var pages = 0
    func page(_ request: PluginWebSignIn) -> PluginJSON {
        pages += 1
        #expect(request.url.absoluteString == "https://api.qishui.com/passport/")
        #expect(request.cookiesOf.absoluteString == "https://api.qishui.com/")
        #expect(request.until == ["sessionid"])
        #expect(request.html?.contains("9012345678901234564") == true)
        #expect(request.html?.contains("9012345678901234565") == true)
        #expect((request.html?.utf8.count ?? 0) < 256 * 1_024)
        return .object(["cookie": .string("sessionid=synthetic-browser-session")])
    }
    func response(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        #expect(url.host == "api.qishui.com")
        let text: String
        switch url.path {
        case "/service/2/desktop/device_register":
            devices += 1
            #expect(request.httpMethod == "POST")
            let body = try JSONDecoder().decode(PluginJSON.self, from: #require(request.httpBody))
            #expect(body["header"]["app_version"] == .string("3.7.0"))
            #expect(body["header"]["pc_serial"] == .string(""))
            text = #"{"device_id_str":"9012345678901234564","install_id_str":"9012345678901234565"}"#
        case "/luna/pc/me":
            profiles += 1
            #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-browser-session")
            if profiles == 1 { throw URLError(.networkConnectionLost) }
            text = #"{"my_info":{"id":"77","nickname":"汽水回归账号"}}"#
        default: throw NetworkFailure.response
        }
        return (Data(text.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    }
}

@Test func sodaIOSBrowserLoginKeepsBrowserCookiesAndRecoversProfileDisconnectWithoutReopeningLogin() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let fixture = SodaBrowserLoginFixture()
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, webSignIn: { request in
        await fixture.page(request)
    }, transport: { request, _ in
        try await fixture.response(request)
    })
    #expect(try await runtime.invoke("signIn")["name"] == .string("汽水回归账号"))
    #expect(await fixture.devices == 1)
    #expect(await fixture.pages == 1)
    #expect(await fixture.profiles == 2)
}
