import Foundation
@testable import MBFoundation
import Testing

private let preferenceWriter = "AAECAwQFBgcICQoLDA0ODw"
private let preferenceRecovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

@Test func pluginPreferencesMergeIndependentEditsResetAndRetainUnknownPlugins() throws {
    let local = try PluginPreferences().withName(id: "Org.plugin-a", name: "我的音乐", writer: preferenceWriter)
    let remote = try PluginPreferences().withOrder(["org.plugin-b", "Org.plugin-a"], writer: "EBESExQVFhcYGRobHB0eHw")
        .withEnabled(id: "org.plugin-b", enabled: false, writer: "EBESExQVFhcYGRobHB0eHw")
    let merged = try local.merged(with: remote)
    #expect(try merged == remote.merged(with: local))
    #expect(merged.names["Org.plugin-a"]?.value == "我的音乐")
    #expect(merged.enabled["org.plugin-b"]?.value == false)
    #expect(merged.order?.value.first == "org.plugin-b")
    let reset = try merged.withName(id: "Org.plugin-a", name: "", writer: preferenceWriter).merged(with: local)
    #expect(reset.names["Org.plugin-a"]?.value == "")
    #expect(reset.maxObservedClock == 3)
    #expect(try JSONDecoder().decode(PluginPreferences.self, from: JSONEncoder().encode(reset)) == reset)
    #expect(PluginPreferences.newWriter() != PluginPreferences.newWriter())
    var invalid = local
    invalid.names["Org.plugin-a"]?.value = "conflicting same stamp"
    #expect(throws: (any Error).self) { try local.merged(with: invalid) }
    #expect(throws: (any Error).self) { try local.withOrder(["a", "a"], writer: preferenceWriter) }
    #expect(throws: (any Error).self) { try local.withName(id: "bad..id", name: "Bad", writer: preferenceWriter) }
    invalid = local; invalid.names["Org.plugin-a"]?.clock = PluginPreferences.maxClock + 1
    #expect(throws: (any Error).self) { try invalid.validated() }
}

private actor PreferencesNodeFixture {
    let node = PlatformNode(origin: URL(string: "https://fixture.test")!, grant: .init(device: preferenceWriter, space: "test-space", token: preferenceRecovery,
        recovery: preferenceRecovery, key: preferenceRecovery, expires: .max))
    private var document: PluginPreferences?
    private var conflict = false
    private var tombstone = false
    private(set) var writes = 0
    func configure(_ value: PluginPreferences?, conflict: Bool = false, tombstone: Bool = false) { document = value; self.conflict = conflict; self.tombstone = tombstone }
    func current() -> PluginPreferences? { document }
    func reply(_ request: URLRequest) throws -> (Data, URLResponse) {
        struct Payload: Codable { let value: PluginPreferences }
        #expect(request.url?.path == "/v1/data/app/plugins")
        let tag = "\"AAECAwQFBgcICQoLDA0ODw\""
        var status = 200; var data = Data()
        if request.httpMethod == "GET" {
            if tombstone { status = 410; data = Data(#"{"error":"deleted"}"#.utf8) }
            else if let document {
                let envelope = try PlatformDataCodec.seal(JSONEncoder().encode(Payload(value: document)), recovery: preferenceRecovery, space: "test-space", platform: "app", key: "plugins")
                data = try JSONEncoder().encode(envelope)
            } else { status = 404; data = Data(#"{"error":"not_found"}"#.utf8) }
        } else {
            writes += 1
            if conflict {
                conflict = false; document = try (document ?? PluginPreferences()).withName(id: "remote-edit", name: "远端并发名称", writer: "EBESExQVFhcYGRobHB0eHw")
                status = 412
            } else {
                #expect(request.value(forHTTPHeaderField: document == nil ? "If-None-Match" : "If-Match") == (document == nil ? "*" : tag))
                status = document == nil ? 201 : 204
                let envelope = try JSONDecoder().decode(PlatformDataEnvelope.self, from: #require(request.httpBody))
                let plain = try PlatformDataCodec.open(envelope, recovery: preferenceRecovery, space: "test-space", platform: "app", key: "plugins")
                document = try JSONDecoder().decode(Payload.self, from: plain).value.validated()
            }
        }
        let url = try #require(request.url)
        let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: ["ETag": tag, "Content-Type": "application/json"]))
        return (data, response)
    }
}

@Test @MainActor func pluginPreferencesSyncRestoresMissingLocalAndRetriesMergedConflict() async throws {
    let fixture = PreferencesNodeFixture()
    let client = PlatformDataClient { request, _ in try await fixture.reply(request) }
    let node = fixture.node
    var local = PluginPreferences()
    func sync() async throws {
        try await client.syncPluginPreferences(node, load: { local }, merge: { remote in
            local = try local.merged(with: remote); return local
        })
    }
    try await sync()
    #expect(await fixture.writes == 0)
    let remote = try PluginPreferences().withName(id: "unknown-plugin", name: "保留名称", writer: preferenceWriter)
    await fixture.configure(remote)
    try await sync()
    #expect(local == remote)
    #expect(await fixture.writes == 0)
    local = try local.withOrder(["unknown-plugin", "other"], writer: preferenceWriter)
    await fixture.configure(remote, conflict: true)
    try await sync()
    #expect(local.order?.value == ["unknown-plugin", "other"])
    #expect(local.names["remote-edit"]?.value == "远端并发名称")
    #expect(await fixture.current() == local)
    #expect(await fixture.writes == 2)
    await fixture.configure(nil, tombstone: true)
    await #expect(throws: (any Error).self) { try await sync() }
    #expect(await fixture.writes == 2)
    var commits = 0
    await #expect(throws: CancellationError.self) {
        try await client.syncPluginPreferences(node, load: { local }, merge: { value in commits += 1; return value }, stillCurrent: { false })
    }
    #expect(commits == 0)
}
