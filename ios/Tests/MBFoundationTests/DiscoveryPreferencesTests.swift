import Foundation
import Testing
@testable import MBFoundation

struct DiscoveryPreferencesTests {
    private let sections = [DiscoveryPreferences.Section(id: "one", defaultEnabled: true), .init(id: "two", defaultEnabled: true)]
    @Test func draftPreservesRemoteEditsAndNewSectionsRemainOff() throws {
        let empty = DiscoveryPreferences(), writer = PluginPreferences.newWriter()
        let before = empty.resolve(plugin: "fixture", sections: sections)
        let initial = try empty.edited(plugin: "fixture", before: before, draft: before, writer: writer)
        var changed = before; changed.enabled["two"] = false
        let remote = try initial.edited(plugin: "fixture", before: before, draft: changed, writer: PluginPreferences.newWriter())
        var moved = before; moved.order.reverse()
        let merged = try remote.edited(plugin: "fixture", before: before, draft: moved, writer: writer)
        let resolved = merged.resolve(plugin: "fixture", sections: sections + [.init(id: "new", defaultEnabled: true)])
        #expect(resolved.order == ["two", "one", "new"])
        #expect(resolved.enabled["two"] == false)
        #expect(resolved.enabled["new"] == false)
        let reopened = merged.resolve(plugin: "fixture", sections: Array(sections.prefix(1)))
        #expect(reopened.order == ["one"])
        #expect(merged.plugins["fixture"]?.enabled["two"] != nil)
    }
    @Test func cacheRecoversOneCorruptCopyAndRejectsTwoCorruptCopies() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = DiscoveryPreferenceCache(root: root), initial = DiscoveryPreferences().resolve(plugin: "fixture", sections: sections)
        let document = try cache.update(migrate: { _ in DiscoveryPreferences() }) { try $0.edited(plugin: "fixture", before: initial, draft: initial, writer: $1) }
        try Data("broken".utf8).write(to: root.appendingPathComponent("preferences.json"))
        #expect(try cache.load(migrate: { _ in DiscoveryPreferences() }) == document)
        try Data("broken".utf8).write(to: root.appendingPathComponent("preferences.json"))
        try Data("broken".utf8).write(to: root.appendingPathComponent("recovery/preferences.json"))
        #expect(throws: (any Error).self) { try cache.load(migrate: { _ in DiscoveryPreferences() }) }
    }
    @Test func allOffDoesNotRestoreDefaultsAndDevicesConverge() throws {
        let empty = DiscoveryPreferences(), before = empty.resolve(plugin: "fixture", sections: sections)
        var disabled = before; disabled.enabled = ["one": false, "two": false]
        let a = try empty.edited(plugin: "fixture", before: before, draft: disabled, writer: PluginPreferences.newWriter())
        let b = try empty.edited(plugin: "fixture", before: before, draft: before, writer: PluginPreferences.newWriter())
        #expect(try a.merged(with: b) == b.merged(with: a))
        #expect(a.resolve(plugin: "fixture", sections: sections).enabled.values.allSatisfy { !$0 })
        #expect(try JSONDecoder().decode(DiscoveryPreferences.self, from: JSONEncoder().encode(a)).validated() == a)
    }
}

@Test @MainActor func discoveryNodeRestoresMissingLocalFromIndependentEncryptedRecord() async throws {
    let recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    let empty = DiscoveryPreferences(), sections = [DiscoveryPreferences.Section(id: "one", defaultEnabled: true)]
    let before = empty.resolve(plugin: "fixture", sections: sections)
    var draft = before; draft.enabled["one"] = false
    let remote = try empty.edited(plugin: "fixture", before: before, draft: draft, writer: PluginPreferences.newWriter())
    struct Payload: Codable { let value: DiscoveryPreferences }
    let envelope = try PlatformDataCodec.seal(JSONEncoder().encode(Payload(value: remote)), recovery: recovery, space: "test-space", platform: "app", key: "discovery")
    let bytes = try JSONEncoder().encode(envelope)
    let node = PlatformNode(origin: URL(string: "https://fixture.test")!, grant: .init(device: "AAECAwQFBgcICQoLDA0ODw", space: "test-space", token: recovery, recovery: recovery, key: recovery, expires: .max))
    let client = PlatformDataClient { request, _ in
        #expect(request.httpMethod == "GET")
        #expect(request.url?.path == "/v1/data/app/discovery")
        let response = try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["ETag": "\"AAECAwQFBgcICQoLDA0ODw\""]))
        return (bytes, response)
    }
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root)
    try await client.syncDiscoveryPreferences(node, load: { try await installer.discoveryPreferences() }, merge: { try await installer.mergeDiscoveryPreferences($0) })
    #expect(try await installer.discoveryPreferences() == remote)
    let reopened = PluginInstaller(directory: root)
    #expect(try await reopened.discoveryPreferences().resolve(plugin: "fixture", sections: sections).enabled["one"] == false)
}
