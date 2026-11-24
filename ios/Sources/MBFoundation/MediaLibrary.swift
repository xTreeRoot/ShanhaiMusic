import CryptoKit
import Foundation

public enum SavedMediaKind: String, Codable, Sendable { case download, imported, cache }

public struct SavedMedia: Identifiable, Codable, Equatable, Sendable {
    public let track: Track
    public let kind: SavedMediaKind
    public let fileName: String
    public let bytes: Int64
    public var accessed: Date
    public var id: String { track.id + ":" + kind.rawValue }
}

/// 沙盒文件库：仅存曲目元数据和完整文件，不保存临时媒体地址、Cookie 或外部安全作用域路径。
public actor MediaLibrary {
    public static let shared = MediaLibrary()
    private let root: URL
    private var entries: [SavedMedia] = []
    private var loaded = false
    private var pendingArtwork: Set<String> = []
    private var cacheLimit: Int64 = 2_048 * 1_024 * 1_024
    public init(directory: URL? = nil) {
        root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile/media", isDirectory: true)
    }
    public func list() throws -> [SavedMedia] {
        try load()
        return entries.filter { $0.kind != .cache }.sorted { $0.accessed > $1.accessed }
    }
    func deviceBackupFiles() throws -> [(SavedMedia, URL)] {
        try list().map { item in (item, root.appendingPathComponent(item.fileName)) }
    }
    public func cacheBytes() throws -> Int64 { try load(); return entries.filter { $0.kind == .cache }.reduce(0) { $0 + $1.bytes } }
    public func cacheEnabled() -> Bool { cacheLimit > 0 }
    public func setCacheLimit(megabytes: Int) throws {
        try load(); cacheLimit = Int64(max(0, min(50_000, megabytes))) * 1_024 * 1_024
        try prune()
    }
    public func cache(file: URL, track: Track) throws {
        try load()
        guard cacheLimit > 0 else { return }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, Int64(size) <= cacheLimit else { return }
        try save(file: file, track: track, kind: .cache)
    }
    public func saveDownload(file: URL, track: Track) throws { try load(); try save(file: file, track: track, kind: track.localFileID == nil ? .download : .imported) }
    public func updateTrack(_ track: Track) throws {
        try load()
        guard let index = entries.firstIndex(where: { $0.track.id == track.id && $0.kind != .cache }) else { throw CocoaError(.fileNoSuchFile) }
        let item = entries[index]
        entries[index] = SavedMedia(track: track, kind: item.kind, fileName: item.fileName, bytes: item.bytes, accessed: item.accessed)
        do { try persist() } catch { entries[index] = item; throw error }
    }
    public func saveArtwork(_ data: Data) throws -> URL {
        try load()
        guard data.count <= 20 * 1_024 * 1_024 else { throw NetworkFailure.response }
        let directory = root.appendingPathComponent("covers", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let file = directory.appendingPathComponent(name)
        #if os(iOS)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: file, options: .atomic)
        #endif
        pendingArtwork.insert(name)
        return file
    }
    /// 播放持有独立临时副本；清缓存、删除下载或 LRU 淘汰不会删除正在播放的文件。
    public func playbackFile(for track: Track) throws -> URL? {
        try load()
        guard let index = entries.firstIndex(where: { $0.track.id == track.id && $0.kind != .cache }) ?? entries.firstIndex(where: { $0.track.id == track.id }) else { return nil }
        let original = root.appendingPathComponent(entries[index].fileName)
        guard FileManager.default.fileExists(atPath: original.path) else {
            try commit(entries.enumerated().filter { $0.offset != index }.map(\.element)); return nil
        }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("mb-audio-\(UUID()).\(original.pathExtension)")
        try FileManager.default.copyItem(at: original, to: copy)
        let previous = entries[index].accessed
        entries[index].accessed = Date()
        do { try persist() }
        catch { entries[index].accessed = previous; try? FileManager.default.removeItem(at: copy); throw error }
        return copy
    }
    public func export(_ item: SavedMedia) throws -> URL {
        try load()
        guard let current = entries.first(where: { $0.id == item.id }) else { throw CocoaError(.fileNoSuchFile) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MB-Export", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let original = root.appendingPathComponent(current.fileName)
        let name = current.track.title.components(separatedBy: CharacterSet(charactersIn: "/\\:\n\r")).joined(separator: "_")
        let target = directory.appendingPathComponent(String(name.prefix(100)) + "-" + String(UUID().uuidString.prefix(6))).appendingPathExtension(original.pathExtension)
        try FileManager.default.copyItem(at: original, to: target)
        return target
    }
    public func remove(_ item: SavedMedia) throws {
        try load()
        guard entries.contains(where: { $0.id == item.id }) else { return }
        try commit(entries.filter { $0.id != item.id })
    }
    public func clearCache() throws {
        try load(); try commit(entries.filter { $0.kind != .cache })
    }
    private func save(file: URL, track: Track, kind: SavedMediaKind) throws {
        try Task.checkCancellation()
        let ext = file.pathExtension.lowercased()
        guard ["m4a", "aac", "mp3", "mp4", "flac", "wav", "aiff", "aif", "caf", "ogg"].contains(ext) else { throw NetworkFailure.response }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        // 每次写新文件；索引提交失败时旧音频仍在，提交后才清理旧文件。
        let name = UUID().uuidString + "." + ext, target = root.appendingPathComponent(name)
        do {
            try FileManager.default.copyItem(at: file, to: target)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            var protected = target; try protected.setResourceValues(values)
            #if os(iOS)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
            #endif
            try Task.checkCancellation()
            let size = try target.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            var next = entries.filter { !($0.track.id == track.id && $0.kind == kind) }
            next.append(SavedMedia(track: track, kind: kind, fileName: name, bytes: Int64(size), accessed: Date()))
            try commit(pruned(next))
        } catch { try? FileManager.default.removeItem(at: target); throw error }
    }
    private func prune() throws { try commit(pruned(entries)) }
    private func pruned(_ values: [SavedMedia]) -> [SavedMedia] {
        var next = values, bytes = values.filter { $0.kind == .cache }.reduce(Int64(0)) { $0 + $1.bytes }
        for item in values.filter({ $0.kind == .cache }).sorted(by: { $0.accessed < $1.accessed }) where bytes > cacheLimit {
            next.removeAll { $0.id == item.id }; bytes -= item.bytes
        }
        return next
    }
    private func commit(_ values: [SavedMedia]) throws {
        let previous = entries; entries = values
        do { try persist() } catch { entries = previous; throw error }
        let kept = Set(values.map(\.fileName))
        for item in previous where !kept.contains(item.fileName) { try? removeFile(item.fileName) }
        pendingArtwork.subtract(values.compactMap { $0.track.artworkURL?.lastPathComponent })
        cleanArtwork()
    }
    private func cleanArtwork() {
        let kept = Set(entries.compactMap { $0.track.artworkURL?.isFileURL == true ? $0.track.artworkURL?.lastPathComponent : nil }).union(pendingArtwork)
        let covers = root.appendingPathComponent("covers")
        if let files = try? FileManager.default.contentsOfDirectory(at: covers, includingPropertiesForKeys: nil) {
            for file in files where !kept.contains(file.lastPathComponent) { try? FileManager.default.removeItem(at: file) }
        }
    }
    private func removeFile(_ name: String) throws {
        let file = root.appendingPathComponent(name)
        do { try FileManager.default.removeItem(at: file) }
        catch CocoaError.fileNoSuchFile { /* 已丢失的文件也从索引移除。 */ }
    }
    private func load() throws {
        guard !loaded else { return }
        let index = root.appendingPathComponent("index.json")
        if FileManager.default.fileExists(atPath: index.path) {
            entries = try JSONDecoder().decode([SavedMedia].self, from: Data(contentsOf: index))
            entries = entries.map { item in
                var track = item.track
                if let cover = track.artworkURL, cover.isFileURL, cover.deletingLastPathComponent().lastPathComponent == "covers" {
                    track.artworkURL = root.appendingPathComponent("covers/" + cover.lastPathComponent)
                }
                return SavedMedia(track: track, kind: item.kind, fileName: item.fileName, bytes: item.bytes, accessed: item.accessed)
            }
            entries = entries.filter { !$0.fileName.contains("/") && !$0.fileName.contains("..") && FileManager.default.fileExists(atPath: root.appendingPathComponent($0.fileName).path) }
        }
        loaded = true
        let kept = Set(entries.map(\.fileName)).union(["index.json", "covers"])
        if let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            for file in files where !kept.contains(file.lastPathComponent) { try? FileManager.default.removeItem(at: file) }
        }
        cleanArtwork()
    }
    private func persist() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var target = root; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try target.setResourceValues(values)
        let file = root.appendingPathComponent("index.json")
        #if os(iOS)
        try JSONEncoder().encode(entries).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(entries).write(to: file, options: .atomic)
        #endif
    }
}
