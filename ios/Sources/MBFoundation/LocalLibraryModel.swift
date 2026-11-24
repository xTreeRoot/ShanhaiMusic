import Foundation
import Observation

@MainActor @Observable
public final class LocalLibraryModel {
    public private(set) var snapshot = LocalLibrarySnapshot()
    public private(set) var catalog = LocalCatalog(tracks: [])
    public private(set) var errorMessage: String?
    @ObservationIgnored let store: LocalLibraryStore
    @ObservationIgnored private var revision = 0
    public init(store: LocalLibraryStore = .shared) { self.store = store }
    public func restore() async { await perform { try await $0.snapshot() } }
    public func updateTracks(_ items: [SavedMedia]) { catalog = LocalCatalog(tracks: items.map(\.track)) }
    public func contains(_ track: Track) -> Bool { catalog.tracks.contains { $0.id == track.id } }
    public func resolve(_ ids: [String]) -> [Track] {
        let tracks = Dictionary(catalog.tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return ids.compactMap { tracks[$0] }
    }
    public var favorites: [Track] { resolve(snapshot.favorites) }
    public var recent: [Track] { Array(resolve(snapshot.recent).prefix(100)) }
    public var resources: [Track] { snapshot.resources ?? [] }
    public func saveResource(_ track: Track) async { await perform { try await $0.saveResource(track) } }
    public func removeResource(_ id: String) async { await perform { try await $0.removeResource(id) } }
    public func toggleFavorite(_ track: Track) async {
        await perform { try await $0.toggleFavorite(track.id) }
    }
    public func recordPlay(_ track: Track) async {
        guard contains(track) else { return }
        await perform { try await $0.recordPlay(track.id) }
    }
    public func createPlaylist(_ name: String) async { await perform { try await $0.createPlaylist(name) } }
    public func renamePlaylist(_ id: String, name: String) async { await perform { try await $0.renamePlaylist(id, name: name) } }
    public func deletePlaylist(_ id: String) async { await perform { try await $0.deletePlaylist(id) } }
    public func add(_ tracks: [Track], to id: String) async { await perform { try await $0.addTracks(tracks.map(\.id), to: id) } }
    public func remove(_ tracks: [Track], from id: String) async { await perform { try await $0.removeTracks(tracks.map(\.id), from: id) } }
    public func move(in id: String, offsets: IndexSet, to destination: Int) async {
        let available = Set(catalog.tracks.map(\.id))
        await perform { try await $0.moveTracks(in: id, offsets: offsets, to: destination, available: available) }
    }
    private func perform(_ action: (LocalLibraryStore) async throws -> LocalLibrarySnapshot) async {
        revision += 1; let current = revision
        do {
            let value = try await action(store)
            guard current == revision else { return }
            snapshot = value; errorMessage = nil
        } catch { if current == revision { errorMessage = error.localizedDescription } }
    }
}
