import Foundation
@testable import MBFoundation
import Testing

@Test func proxyAllowlistRespectsDomainBoundariesAndEmptyDirectPolicy() throws {
    let policy = try ProxyPolicy(" MUSIC.YouTube.com.\n*.ytimg.com\nmusic.youtube.com ")
    #expect(policy.hosts == ["music.youtube.com", "*.ytimg.com"])
    for host in ["music.youtube.com", "i.ytimg.com"] {
        #expect(policy.usesSystemProxy(for: URL(string: "https://\(host)/")!))
    }
    for host in ["youtube.com", "ytimg.com", "ytimg.com.evil.test", "notytimg.com", "api.bilibili.com", "127.0.0.1", "localhost"] {
        #expect(!policy.usesSystemProxy(for: URL(string: "https://\(host)/")!))
    }
    #expect(try !ProxyPolicy("").usesSystemProxy(for: URL(string: "https://music.youtube.com")!))
}

@Test(arguments: ["*", "https://youtube.com", "user:pass@youtube.com", "youtube.com:443", "foo.*.com", "-bad.com", "youtube..com"])
func proxyRejectsInvalidDomains(_ value: String) {
    #expect(throws: (any Error).self) { try ProxyPolicy(value) }
}

@Test func redirectsDiscardHeadersAcrossHostsAndRejectDowngrade() throws {
    let url = URL(string: "https://api.bilibili.com/one")!
    var request = URLRequest(url: url)
    request.allHTTPHeaderFields = ["Cookie": "test-session", "Authorization": "test-token", "Referer": "https://www.bilibili.com/"]
    let cross = HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: ["Location": "https://other.example/two"])!
    #expect(try NetworkClient.redirect(request, response: cross)?.allHTTPHeaderFields?.isEmpty != false)
    let same = HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: ["Location": "/two"])!
    #expect(try NetworkClient.redirect(request, response: same)?.value(forHTTPHeaderField: "Cookie") == "test-session")
    let insecure = HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: ["Location": "http://api.bilibili.com/two"])!
    #expect(throws: (any Error).self) { try NetworkClient.redirect(request, response: insecure) }
}

@Test func sessionAndProxySettingsSurviveSandboxStoreRecreation() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AppPersistence(directory: directory)
    let track = Track(sourceID: "bilibili", sourceTrackID: "BV123", title: "Test", artist: "UP", durationMilliseconds: 90_000)
    try await store.saveSession(PlaybackSession(queue: [track], index: 0, positionMilliseconds: 42_000))
    try await store.saveProxyHosts(["*.youtube.com"])
    let reloaded = AppPersistence(directory: directory)
    let session = try await reloaded.loadSession()
    #expect(session?.queue == [track])
    #expect(session?.positionMilliseconds == 42_000)
    #expect(try await reloaded.loadProxyHosts() == ["*.youtube.com"])
    let json = try String(contentsOf: directory.appendingPathComponent("playback.json"), encoding: .utf8)
    #expect(!json.contains("audioURL"))
}

@Test func playbackDiagnosticsKeepRecentEventsAcrossStoreRecreation() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AppPersistence(directory: directory)
    let start = Date(timeIntervalSince1970: 1_700_006_397)
    try await store.writePlaybackDiagnostic(.sceneBackground, at: start.addingTimeInterval(128))
    for offset in 0..<128 {
        try await store.writePlaybackDiagnostic(.audioPlaying, at: start.addingTimeInterval(Double(offset)))
    }
    let reopened = AppPersistence(directory: directory)
    try await reopened.writePlaybackDiagnostic(.launch, at: start.addingTimeInterval(129))
    let contents = try Data(contentsOf: directory.appendingPathComponent("playback-diagnostics.json"))
    let events = try JSONDecoder().decode([AppPersistence.PlaybackDiagnostic].self, from: contents)
    #expect(events.count == 128)
    #expect(events.first?.timestamp == "2023-11-14T23:59:59.000Z")
    #expect(events.suffix(2).map(\.event) == [.sceneBackground, .launch])
}

@Test func postRedirectsKeepSameOriginBodyButNeverForwardCSRFToAnotherHost() throws {
    let url = URL(string: "https://api.bilibili.com/write")!
    var request = URLRequest(url: url)
    request.httpMethod = "POST"; request.httpBody = Data("csrf=fixture&message=a%2Bb".utf8)
    let same = HTTPURLResponse(url: url, statusCode: 307, httpVersion: nil, headerFields: ["Location": "/next"])!
    let next = try #require(try NetworkClient.redirect(request, response: same))
    #expect(next.httpMethod == "POST")
    #expect(next.httpBody == request.httpBody)
    let cross = HTTPURLResponse(url: url, statusCode: 307, httpVersion: nil, headerFields: ["Location": "https://other.example/next"])!
    #expect(throws: (any Error).self) { try NetworkClient.redirect(request, response: cross) }
    let get = HTTPURLResponse(url: url, statusCode: 303, httpVersion: nil, headerFields: ["Location": "/next"])!
    #expect(try NetworkClient.redirect(request, response: get)?.httpMethod == "GET")
    #expect(try NetworkClient.redirect(request, response: get)?.httpBody == nil)
}

@Test func existingOAuthProxyPolicyAddsOfficialAccountDomainOnceAndRespectsLaterEdits() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    try Data(#"{"hosts":["oauth2.googleapis.com","*.youtube.com"]}"#.utf8).write(to: directory.appendingPathComponent("proxy-hosts.json"))
    let store = AppPersistence(directory: directory)
    #expect(try await store.loadProxyHosts() == ["oauth2.googleapis.com", "*.youtube.com", "youtube.googleapis.com"])
    try await store.saveProxyHosts(["oauth2.googleapis.com"])
    #expect(try await store.loadProxyHosts() == ["oauth2.googleapis.com"])
    try await store.saveProxyHosts([])
    #expect(try await store.loadProxyHosts() == [])
}
