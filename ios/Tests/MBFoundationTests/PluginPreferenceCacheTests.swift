import Foundation
@testable import MBFoundation
import Testing

@Test func pluginPreferenceCacheRecoversAndDoesNotResurrectClearedName() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let cache = PluginPreferenceCache(root: root)
    let migrate: (String) throws -> PluginPreferences = { try PluginPreferences().withName(id: "one", name: "旧名字", writer: $0).withEnabled(id: "one", enabled: false, writer: $0) }
    let first = try cache.load(migrate: migrate)
    let reset = try cache.update(migrate: migrate) { try $0.withName(id: "one", name: "", writer: $1) }
    #expect(reset.names["one"]!.clock > first.names["one"]!.clock)
    for corrupt in [false, true] {
        let file = root.appendingPathComponent("preferences.json")
        if corrupt { try Data("broken".utf8).write(to: file) } else { try FileManager.default.removeItem(at: file) }
        let restored = try PluginPreferenceCache(root: root).load(migrate: migrate)
        #expect(restored == reset)
    }
    for path in ["preferences.json", "recovery/preferences.json"] { try Data("broken".utf8).write(to: root.appendingPathComponent(path)) }
    #expect(throws: (any Error).self) { try cache.load(migrate: migrate) }
}

@Test func installerMigratesNamesAndDisabledWithoutStampingDefaultEnabled() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try Data(#"{"names":{"one":"本地音乐"},"order":["two","one"]}"#.utf8).write(to: root.appendingPathComponent("presentation.json"))
    try Data(#"["one"]"#.utf8).write(to: root.appendingPathComponent("disabled.json"))
    let installer = PluginInstaller(directory: root)
    let migrated = try await installer.pluginPreferences()
    #expect(migrated.names["one"]?.value == "本地音乐")
    #expect(migrated.order?.value == ["two", "one"])
    #expect(migrated.enabled["one"]?.value == false && migrated.enabled["two"] == nil)
    let remote = try PluginPreferences().withName(id: "unknown", name: "远端保留", writer: PluginPreferences.newWriter())
    _ = try await installer.mergePluginPreferences(remote)
    try FileManager.default.removeItem(at: root.appendingPathComponent("preferences.json"))
    let reopened = PluginInstaller(directory: root)
    #expect(try await reopened.presentation().names["unknown"] == "远端保留")
    #expect(try await reopened.disabled() == ["one"])
}
