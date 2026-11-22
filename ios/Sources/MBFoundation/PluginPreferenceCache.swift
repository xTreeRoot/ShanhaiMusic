import Foundation

/// 插件代码目录之外的两份原子缓存；合并版本元数据，损坏单份时从另一份恢复。
typealias PluginPreferenceCache = PreferenceCache<PluginPreferences>

final class PreferenceCache<Document: PortablePreferences> {
    private struct Snapshot: Codable {
        let writer: String
        var document: Document
        func validated() throws -> Self {
            _ = try document.validated()
            _ = try PluginPreferences().withOrder([], writer: writer)
            return self
        }
    }
    private let primary: URL
    private let recovery: URL
    init(root: URL) {
        primary = root.appendingPathComponent("preferences.json")
        recovery = root.appendingPathComponent("recovery/preferences.json")
    }
    func load(migrate: (String) throws -> Document) throws -> Document {
        try snapshot(migrate: migrate).document
    }
    func update(migrate: (String) throws -> Document, change: (Document, String) throws -> Document) throws -> Document {
        var current = try snapshot(migrate: migrate)
        let next = try change(current.document, current.writer).validated()
        if next != current.document { current.document = next; try save(current) }
        return next
    }
    private func snapshot(migrate: (String) throws -> Document) throws -> Snapshot {
        var snapshots: [Snapshot] = []
        var failure: (any Error)?
        for file in [primary, recovery] where FileManager.default.fileExists(atPath: file.path) {
            do {
                let bytes = try Data(contentsOf: file)
                guard bytes.count <= 1 << 20 else { throw PlatformDataFailure.invalid }
                snapshots.append(try JSONDecoder().decode(Snapshot.self, from: bytes).validated())
            } catch { failure = error }
        }
        if let first = snapshots.first {
            var result = first
            for item in snapshots.dropFirst() { result.document = try result.document.merged(with: item.document) }
            if snapshots.count != 2 || snapshots.contains(where: { $0.writer != result.writer || $0.document != result.document }) { try save(result) }
            return result
        }
        if let failure { throw failure }
        let writer = PluginPreferences.newWriter()
        let result = Snapshot(writer: writer, document: try migrate(writer).validated())
        try save(result)
        return result
    }
    private func save(_ snapshot: Snapshot) throws {
        let bytes = try JSONEncoder().encode(snapshot.validated())
        for file in [primary, recovery] {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try bytes.write(to: file, options: .atomic)
        }
    }
}
