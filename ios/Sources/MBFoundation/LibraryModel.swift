import Foundation
import Observation

@MainActor @Observable
public final class LibraryModel {
    public let route: LibraryRoute
    public let sourceID: String
    public private(set) var title: String
    public private(set) var tracks: [Track] = []
    public private(set) var links: [LibraryLink] = []
    public private(set) var isLoading = false
    public private(set) var errorMessage: String?
    public private(set) var nextCursor: String?
    @ObservationIgnored private let source: any LibrarySource
    private var generation = 0
    public var paginationGeneration: Int { generation }
    @ObservationIgnored private var failedCursor: String?
    @ObservationIgnored private var failedRefresh = false
    public init(source: any LibrarySource, route: LibraryRoute) {
        self.source = source; self.sourceID = source.displayName; self.route = route; self.title = route.title
    }
    public func restore() async { generation += 1; await load(cursor: nil, generation: generation) }
    public func refresh() async {
        generation += 1
        let current = generation
        await source.invalidateLibraryCache()
        guard current == generation, !Task.isCancelled else { return }
        await load(cursor: nil, generation: current)
    }
    public func loadMore() async {
        guard let nextCursor, !isLoading else { return }
        await load(cursor: nextCursor, generation: generation)
    }
    public func playbackPages() -> PlaybackPageLoader? {
        guard let nextCursor else { return nil }
        let source = self.source, route = self.route
        return .following(nextCursor) { cursor in
            let page = try await source.library(route, cursor: cursor)
            return (page.tracks, page.nextCursor)
        }
    }
    public func retry() async {
        guard !isLoading else { return }
        await load(cursor: failedRefresh ? nil : failedCursor, generation: generation)
    }
    private func load(cursor: String?, generation: Int) async {
        isLoading = true; errorMessage = nil
        defer { if generation == self.generation { isLoading = false } }
        do {
            let result = try await source.library(route, cursor: cursor)
            guard generation == self.generation, !Task.isCancelled else { return }
            title = result.title
            if cursor == nil { tracks = []; links = [] }
            var trackIDs = Set(tracks.map(\.id)), linkIDs = Set(links.map(\.id))
            tracks += result.tracks.filter { trackIDs.insert($0.id).inserted }
            links += result.links.filter { linkIDs.insert($0.id).inserted }
            nextCursor = result.nextCursor == cursor ? nil : result.nextCursor
            failedCursor = nil; failedRefresh = false
        } catch {
            if generation == self.generation && !Task.isCancelled {
                errorMessage = error.localizedDescription; failedCursor = cursor; failedRefresh = cursor == nil
            }
        }
    }
}
