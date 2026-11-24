import Foundation

/// 从旧插件数据只读迁移，目标已有键优先，不删除历史文件。
enum LegacyPluginStorage {
    static func migrate(_ plugin: InstalledPlugin) throws {
        let root = plugin.dataDirectory.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let old = root.appendingPathComponent("plugin-data/" + plugin.id + "/session.json")
        guard FileManager.default.fileExists(atPath: old.path) else { return }
        let file = plugin.dataDirectory.appendingPathComponent("storage.json")
        var values = FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode([String: PluginJSON].self, from: Data(contentsOf: file)) : [:]
        guard values["session"] == nil else { return }
        guard (try old.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 16 * 1024 * 1024 else { throw PluginFailure.invalid("Legacy storage exceeds limit") }
        values["session"] = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: old))
        try FileManager.default.createDirectory(at: plugin.dataDirectory, withIntermediateDirectories: true)
        #if os(iOS)
        try JSONEncoder().encode(values).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(values).write(to: file, options: .atomic)
        #endif
    }
}
