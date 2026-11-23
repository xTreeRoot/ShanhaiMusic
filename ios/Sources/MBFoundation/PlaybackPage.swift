import Foundation

public struct PlaybackPageLoader: Sendable {
    let fetch: @Sendable () async throws -> (tracks: [Track], next: Self?)

    public init(_ fetch: @escaping @Sendable () async throws -> (tracks: [Track], next: Self?)) {
        self.fetch = fetch
    }

    public static func following<Cursor: Hashable & Sendable>(
        _ cursor: Cursor, visited: Set<Cursor> = [],
        load: @escaping @Sendable (Cursor) async throws -> ([Track], Cursor?)
    ) -> Self? {
        guard !visited.contains(cursor) else { return nil }
        let seen = visited.union([cursor])
        return Self {
            let (tracks, next) = try await load(cursor)
            return (tracks, next.flatMap { following($0, visited: seen, load: load) })
        }
    }
}
