import CryptoKit
import Foundation
import ImageIO

/// 有容量上限的本地响应缓存。文件名仅含摘要，凭据和请求头不写入索引。
public actor ResponseCache {
    private struct Entry: Codable { let saved: Date; let data: Data }
    private struct Pending { let id: UUID; let task: Task<Data, any Error> }
    private let directory: URL?
    private let maximumBytes: Int
    private var memory: [String: Entry] = [:]
    private var pending: [String: Pending] = [:]
    private var generation = 0

    public init(directory: URL? = nil, maximumBytes: Int = 32 << 20) {
        self.directory = directory; self.maximumBytes = maximumBytes
    }
    public nonisolated static func directory(_ name: String) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile", isDirectory: true).appendingPathComponent(name, isDirectory: true)
    }
    public nonisolated static func key(_ parts: [String]) -> String {
        let data = (try? JSONEncoder().encode(parts)) ?? Data()
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// 新鲜缓存不联网；过期时刷新失败可继续显示旧数据，取消和失效操作不回退。
    public func data(key: String, lifetime: TimeInterval, load: @escaping @Sendable () async throws -> Data) async throws -> Data {
        try Task.checkCancellation()
        let key = Self.key([key])
        let cached = read(key)
        if let cached, Date().timeIntervalSince(cached.saved) < lifetime { return cached.data }
        let current = generation
        let work: Pending
        if let existing = pending[key] { work = existing }
        else { work = Pending(id: UUID(), task: Task { try await load() }); pending[key] = work }
        defer { if pending[key]?.id == work.id { pending[key] = nil } }
        do {
            let data = try await work.task.value
            try Task.checkCancellation()
            guard current == generation else { throw CancellationError() }
            if !data.isEmpty && data.count <= maximumBytes { write(Entry(saved: Date(), data: data), key: key) }
            return data
        } catch {
            try Task.checkCancellation()
            guard current == generation, !(error is CancellationError) else { throw CancellationError() }
            if let cached { return cached.data }
            throw error
        }
    }

    public func invalidate() {
        generation += 1
        pending.values.forEach { $0.task.cancel() }; pending.removeAll(); memory.removeAll()
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }
    private func read(_ key: String) -> Entry? {
        guard let directory else { return memory[key] }
        let file = directory.appendingPathComponent(key)
        guard let data = try? Data(contentsOf: file), let entry = try? JSONDecoder().decode(Entry.self, from: data) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return entry
    }
    private func write(_ entry: Entry, key: String) {
        guard let directory else {
            memory[key] = entry
            if memory.values.reduce(0, { $0 + $1.data.count }) > maximumBytes { memory = [key: entry] }
            return
        }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(entry).write(to: directory.appendingPathComponent(key), options: .atomic)
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
            let entries = files.compactMap { file -> (URL, Int, Date)? in
                guard let values = try? file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }
                return (file, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
            }.sorted { $0.2 < $1.2 }
            var bytes = entries.reduce(0) { $0 + $1.1 }
            for (file, size, _) in entries where bytes > maximumBytes { try? FileManager.default.removeItem(at: file); bytes -= size }
        } catch { /* 缓存不可写时仍返回本次已成功加载的内容。 */ }
    }
}

/// 所有平台的歌曲、实体封面及头像共用此入口，保留既有网络代理和请求头策略。
public actor ArtworkCache {
    public static let shared = ArtworkCache()
    public typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private let cache: ResponseCache
    private let transport: Transport
    public init(directory: URL = ResponseCache.directory("artwork"),
                transport: @escaping Transport = { try await NetworkClient.shared.data(for: $0, maximumBytes: 16 << 20) }) {
        cache = ResponseCache(directory: directory, maximumBytes: 256 << 20); self.transport = transport
    }
    /// 对已加载的个人列表预取封面，最多四个并发；滚出视野后仍可命中磁盘缓存。
    public func prefetch(_ tracks: [Track]) async {
        var seen: Set<String> = []
        let items = tracks.filter { track in
            guard let url = track.artworkURL, ["https", "http"].contains(url.scheme) else { return false }
            let headers = track.artworkHeaders.sorted { $0.key < $1.key }.flatMap { [$0.key, $0.value] }
            return seen.insert(ResponseCache.key([track.sourceID, url.absoluteString] + headers)).inserted
        }
        await withTaskGroup(of: Void.self) { group in
            for (index, track) in items.enumerated() {
                if index >= 4 { await group.next() }
                guard !Task.isCancelled else { group.cancelAll(); break }
                group.addTask { [self] in
                    guard !Task.isCancelled, let url = track.artworkURL else { return }
                    _ = try? await data(url: url, sourceID: track.sourceID, headers: track.artworkHeaders)
                }
            }
        }
    }
    public func data(url: URL, sourceID: String, headers: [String: String]) async throws -> Data {
        let headerParts = headers.sorted { $0.key.lowercased() < $1.key.lowercased() }.flatMap { [$0.key.lowercased(), $0.value] }
        let key = ResponseCache.key([sourceID, url.absoluteString] + headerParts)
        let transport = transport
        return try await cache.data(key: key, lifetime: 30 * 86400) {
            var request = URLRequest(url: url); request.allHTTPHeaderFields = headers
            let (data, response) = try await transport(request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let image = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(image) > 0 else { throw NetworkFailure.response }
            return data
        }
    }
}
