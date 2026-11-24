import Foundation
import Observation

/// 搜索状态只在主线程更新；取消或被替换的请求不能改写新结果。
@MainActor @Observable
public final class SearchModel {
    public private(set) var tracks: [Track] = []
    public private(set) var query = ""
    public private(set) var isLoading = false
    public private(set) var hasMore = false
    public private(set) var errorMessage: String?
    public private(set) var warnings: [String] = []

    @ObservationIgnored private let loadPage: @Sendable (String, Int) async throws -> (TrackPage, Int)
    @ObservationIgnored private let initialCursor: Int
    private var generation = 0
    public var paginationGeneration: Int { generation }
    public private(set) var cursor: Int

    public init(source: any MusicSource) {
        initialCursor = 1; cursor = 1
        loadPage = { query, page in (try await source.search(query: query, page: page), page + 1) }
    }

    public init(videoSource: PluginSource) {
        initialCursor = 0; cursor = 0
        loadPage = { query, offset in try await videoSource.searchVideos(query: query, offset: offset) }
    }

    public func search(_ value: String) async {
        generation += 1
        let current = generation
        query = value.trimmingCharacters(in: .whitespacesAndNewlines)
        tracks = []
        cursor = initialCursor
        hasMore = false
        errorMessage = nil
        warnings = []
        isLoading = !query.isEmpty
        guard !query.isEmpty else { return }
        await load(generation: current)
    }

    public func loadMore() async {
        guard hasMore, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        await load(generation: generation)
    }

    public func playbackPages() -> PlaybackPageLoader? {
        guard hasMore else { return nil }
        let query = self.query, load = loadPage
        return .following(cursor) { offset in
            let (page, next) = try await load(query, offset)
            return (page.tracks, page.hasMore && next > offset ? next : nil)
        }
    }

    public func cancel() {
        generation += 1
        isLoading = false
    }

    private func load(generation current: Int) async {
        do {
            let (result, nextCursor) = try await loadPage(query, cursor)
            guard current == generation else { return }
            guard !Task.isCancelled else { isLoading = false; return }
            var seen = Set(tracks.map(\.id))
            tracks += result.tracks.filter { seen.insert($0.id).inserted }
            warnings = result.warnings
            hasMore = result.hasMore
            cursor = nextCursor
            isLoading = false
        } catch {
            guard current == generation else { return }
            isLoading = false
            if Task.isCancelled || error is CancellationError { return }
            errorMessage = error.localizedDescription
        }
    }
}

