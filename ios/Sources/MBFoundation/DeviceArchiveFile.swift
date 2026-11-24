import Foundation
import ZIPFoundation

struct DeviceArchiveEntry: Sendable { let name: String; let file: URL }

/// ZIP 操作只发生在后台 actor；提取目标必须是本次新建的隔离目录。
actor DeviceArchiveFile {
    static let maxBytes: UInt64 = 16 << 30
    static let maxEntries = 50_000
    static let manifestLimit = 32 << 20

    static func validPath(_ path: String) -> Bool {
        guard !path.isEmpty, path.utf8.count <= 1024, !path.hasPrefix("/"),
              !path.contains(where: { "\\:\0".contains($0) }) else { return false }
        return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    static func tree(_ input: URL, prefix: String) throws -> [DeviceArchiveEntry] {
        guard (try? input.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true else { throw PlatformDataFailure.invalid }
        let root = input.standardizedFileURL.resolvingSymlinksInPath()
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        guard try root.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw PlatformDataFailure.invalid }
        guard let files = FileManager.default.enumerator(atPath: root.path) else { throw CocoaError(.fileReadNoPermission) }
        var result: [DeviceArchiveEntry] = []
        // Use the enumerator's relative paths. URL enumeration can canonicalize
        // /var to /private/var independently of its input URL on macOS.
        for case let relative as String in files {
            let file = root.appendingPathComponent(relative)
            let properties = try file.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey])
            guard properties.isSymbolicLink != true else { throw PlatformDataFailure.invalid }
            if properties.isRegularFile == true {
                let path = prefix + "/" + relative
                guard validPath(path) else { throw PlatformDataFailure.invalid }
                result.append(DeviceArchiveEntry(name: path, file: file))
            }
        }
        return result
    }

    func write(_ manifest: DeviceArchiveManifest, entries: [DeviceArchiveEntry], to output: URL, scratch: URL) throws {
        _ = try manifest.validated()
        let metadata = try JSONEncoder().encode(manifest)
        guard metadata.count <= Self.manifestLimit, entries.count <= Self.maxEntries else { throw PlatformDataFailure.invalid }
        let file = scratch.appendingPathComponent("manifest.json")
        try metadata.write(to: file, options: .atomic)
        let archive = try Archive(url: output, accessMode: .create)
        try archive.addEntry(with: "manifest.json", fileURL: file, compressionMethod: .deflate)
        var names: Set<String> = ["manifest.json"], bytes: UInt64 = UInt64(metadata.count)
        for item in entries {
            try Task.checkCancellation()
            guard Self.validPath(item.name), names.insert(item.name.lowercased()).inserted else { throw PlatformDataFailure.invalid }
            let properties = try item.file.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
            guard properties.isSymbolicLink != true, properties.isRegularFile == true else { throw PlatformDataFailure.invalid }
            bytes += UInt64(properties.fileSize ?? 0)
            guard bytes <= Self.maxBytes else { throw PlatformDataFailure.invalid }
            try archive.addEntry(with: item.name, fileURL: item.file, compressionMethod: .deflate)
            let after = try item.file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            guard after.fileSize == properties.fileSize, after.contentModificationDate == properties.contentModificationDate else {
                throw PluginFailure.invalid("备份过程中本机文件发生变化，请重试")
            }
        }
    }

    func extract(_ input: URL, to stage: URL) throws -> DeviceArchiveManifest {
        guard !FileManager.default.fileExists(atPath: stage.path) else { throw PlatformDataFailure.invalid }
        let archive = try Archive(url: input, accessMode: .read)
        var names = Set<String>(), bytes: UInt64 = 0, count = 0
        for entry in archive {
            let path = entry.path.hasSuffix("/") ? String(entry.path.dropLast()) : entry.path
            guard Self.validPath(path), entry.type != .symlink, names.insert(path.lowercased()).inserted else { throw PlatformDataFailure.invalid }
            guard entry.uncompressedSize <= Self.maxBytes - bytes else { throw PlatformDataFailure.invalid }
            count += 1; bytes += entry.uncompressedSize
            guard count <= Self.maxEntries + 1, bytes <= Self.maxBytes else { throw PlatformDataFailure.invalid }
        }
        guard let metadata = archive["manifest.json"], metadata.uncompressedSize <= Self.manifestLimit else { throw PlatformDataFailure.invalid }
        var data = Data()
        _ = try archive.extract(metadata) { data.append($0) }
        let manifest = try JSONDecoder().decode(DeviceArchiveManifest.self, from: data).validated()
        for plugin in manifest.plugins {
            guard names.contains("plugins/\(plugin.id)/package.zip".lowercased()) else { throw PlatformDataFailure.invalid }
        }
        for track in manifest.library.tracks {
            guard track.asset.map({ names.contains($0.lowercased()) }) ?? true else { throw PlatformDataFailure.invalid }
        }
        try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: true)
        for entry in archive {
            try Task.checkCancellation()
            let target = stage.appendingPathComponent(entry.path)
            let expected = stage.path + "/"
            guard target.standardizedFileURL.path.hasPrefix(expected) else { throw PlatformDataFailure.invalid }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            let checksum = try archive.extract(entry, to: target)
            guard checksum == entry.checksum else { throw PlatformDataFailure.invalid }
        }
        return manifest
    }
}
