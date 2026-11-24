import Foundation
@testable import MBFoundation
import Testing

@Test func legacyPluginSessionMergesOnceWithoutDeletingOrOverwritingCurrentKeys() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let old = root.appendingPathComponent("plugin-data/fixture/session.json")
    let directory = root.appendingPathComponent("plugins/data/fixture")
    try FileManager.default.createDirectory(at: old.deletingLastPathComponent(), withIntermediateDirectories: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try Data(#"{"cookie":"fixture-private","active":false}"#.utf8).write(to: old)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture"}"#.utf8))
    let plugin = InstalledPlugin(manifest: manifest, directory: root.appendingPathComponent("plugins/installed/fixture"), dataDirectory: directory)
    let file = directory.appendingPathComponent("storage.json")
    try Data(#"{"other":"preserve"}"#.utf8).write(to: file)
    try LegacyPluginStorage.migrate(plugin)
    let first = try JSONDecoder().decode([String: PluginJSON].self, from: Data(contentsOf: file))
    #expect(first["other"] == .string("preserve"))
    #expect(first["session"]?["active"] == .bool(false))
    let bytes = try Data(contentsOf: file)
    try Data(#"{"cookie":"late-old-value"}"#.utf8).write(to: old)
    try LegacyPluginStorage.migrate(plugin)
    #expect(try Data(contentsOf: file) == bytes)
    #expect(FileManager.default.fileExists(atPath: old.path))
}
