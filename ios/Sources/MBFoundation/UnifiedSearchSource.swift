import Foundation

public struct SearchProvider: Sendable {
    public let id: String
    public let name: String
    public let type: String
    public let initialCursor: Int
    let fetch: @Sendable (String, Int) async throws -> (TrackPage, Int)
    public init(id: String, name: String, type: String = "music", source: any MusicSource) {
        self.id = id; self.name = name; self.type = type; initialCursor = 1
        fetch = { query, page in (try await source.search(query: query, page: page), page + 1) }
    }
    public init(videoSource: PluginSource, name: String) {
        id = videoSource.manifest.id + ":video"; self.name = name; type = "video"; initialCursor = 0
        fetch = { try await videoSource.searchVideos(query: $0, offset: $1) }
    }
}

/// 同时查询各来源，独立维护游标；一个来源失败不会丢弃其他来源结果。
public actor UnifiedSearchSource: MusicSource {
    public nonisolated let displayName = "全平台搜索"
    private let providers: [SearchProvider]
    private var cursors: [String: [Int: [String: Int]]] = [:]
    public init(providers: [SearchProvider]) { self.providers = providers }
    public func search(query: String, page: Int) async throws -> TrackPage {
        let current = page == 1 ? Dictionary(uniqueKeysWithValues: providers.map { ($0.id, $0.initialCursor) }) : cursors[query]?[page] ?? [:]
        let batches = await withTaskGroup(of: (Int, TrackPage?, Int, String?).self) { group in
            for (index, provider) in providers.enumerated() {
                guard let cursor = current[provider.id] else { continue }
                group.addTask {
                    do {
                        let (result, next) = try await provider.fetch(query, cursor)
                        var typed = result
                        typed = TrackPage(tracks: result.tracks.map { track in
                            var track = track; track.resourceType = track.resourceType ?? provider.type; return track
                        }, hasMore: result.hasMore && next > cursor)
                        return (index, typed, next, nil)
                    } catch { return (index, nil, cursor, "\(provider.name)：\(error.localizedDescription)") }
                }
            }
            var results: [(Int, TrackPage?, Int, String?)] = []
            for await result in group { results.append(result) }
            return results.sorted { $0.0 < $1.0 }
        }
        try Task.checkCancellation()
        var tracks: [Track] = [], warnings: [String] = [], remaining: [String: Int] = [:]
        for (index, result, next, error) in batches {
            if let result { tracks += result.tracks; if result.hasMore { remaining[providers[index].id] = next } }
            if let error { warnings.append(error) }
        }
        if batches.allSatisfy({ $0.1 == nil }), let error = warnings.first { throw PluginFailure.execution(error) }
        cursors[query, default: [:]][page + 1] = remaining
        var seen: Set<String> = []
        var result = TrackPage(tracks: tracks.filter { seen.insert($0.id).inserted }, hasMore: !remaining.isEmpty)
        result.warnings = warnings; return result
    }
}
