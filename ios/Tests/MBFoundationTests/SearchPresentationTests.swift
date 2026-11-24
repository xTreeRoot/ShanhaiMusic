import Foundation
import Testing
@testable import MBFoundation

private actor CombinedSearchFixture: MusicSource {
    nonisolated let displayName = "Fixture"
    let id: String
    let fail: Bool
    private var pages: [Int] = []
    init(_ id: String, fail: Bool = false) { self.id = id; self.fail = fail }
    func search(query: String, page: Int) async throws -> TrackPage {
        pages.append(page)
        if fail { throw PluginFailure.execution("offline") }
        return TrackPage(tracks: [Track(sourceID: id, sourceTrackID: String(page), title: query, artist: id)], hasMore: page < 2)
    }
    func requested() -> [Int] { pages }
}

@Test func combinedSearchKeepsPerSourcePaginationTypesAndPartialResults() async throws {
    let a = CombinedSearchFixture("a"), b = CombinedSearchFixture("b"), broken = CombinedSearchFixture("offline", fail: true)
    let source = UnifiedSearchSource(providers: [SearchProvider(id: "a", name: "本地名称", source: a),
        SearchProvider(id: "b", name: "视频站", type: "video", source: b), SearchProvider(id: "broken", name: "离线站", source: broken)])
    let first = try await source.search(query: "test", page: 1)
    #expect(first.tracks.map(\.sourceID) == ["a", "b"] && first.hasMore)
    #expect(first.tracks.map(\.resourceType) == ["music", "video"])
    #expect(first.warnings.count == 1 && first.warnings[0].contains("离线站"))
    let next = try await source.search(query: "test", page: 2)
    #expect(next.tracks.map(\.sourceTrackID) == ["2", "2"] && !next.hasMore)
    #expect(await broken.requested() == [1])
    #expect(await a.requested() == [1, 2])
}

@Test func resourceBookmarksPersistAndReplaceWithoutTouchingPlaylists() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = LocalLibraryStore(directory: root)
    _ = try await store.createPlaylist("保留歌单")
    var track = Track(sourceID: "fixture", sourceTrackID: "a", title: "Example", artist: "Fixture")
    track.resourceURL = URL(string: "https://example.test/file"); track.resourceType = "software"
    _ = try await store.saveResource(track); _ = try await store.saveResource(track)
    let restored = try await LocalLibraryStore(directory: root).snapshot()
    #expect(restored.resources == [track] && restored.playlists.first?.name == "保留歌单")
    #expect(try await store.removeResource(track.id).resources?.isEmpty == true)
}

@Test func pluginPresentationPersistsOutsideInstalledCodeAndCanRestoreDefault() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root)
    var value = PluginPresentation(); try value.rename("a", to: "  我的平台  "); value.order = ["b", "a"]
    try await installer.savePresentation(value)
    let restored = try await PluginInstaller(directory: root).presentation()
    #expect(restored.names["a"] == "我的平台" && restored.order == ["b", "a"])
    try value.rename("a", to: " "); #expect(value.name(id: "a", fallback: "原名") == "原名")
    #expect(throws: PluginFailure.self) { try value.rename("a", to: String(repeating: "a", count: 61)) }
}

@MainActor @Test func pluginRenameAndOrderSurviveCodeUpdateAndModelReload() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root.appendingPathComponent("store"))
    func input(_ id: String, version: String) throws -> URL {
        let folder = root.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("{\"id\":\"\(id)\",\"name\":\"\(id)\",\"version\":\"\(version)\"}".utf8).write(to: folder.appendingPathComponent("plugin.json"))
        try Data("export function search(){return {tracks:[]};}".utf8).write(to: folder.appendingPathComponent("index.js"))
        return folder
    }
    for id in ["a", "b"] { _ = try await installer.install(installer.prepare(input(id, version: "1.0.0"))) }
    let model = PluginModel(installer: installer); await model.restore()
    await model.rename(try #require(model.installed.first), to: "我的平台")
    await model.move(from: [1], to: 0)
    _ = try await installer.install(installer.prepare(input("a", version: "1.1.0")))
    let restored = PluginModel(installer: installer); await restored.restore()
    #expect(restored.installed.map(\.id) == ["b", "a"])
    #expect(restored.displayName(try #require(restored.installed.last)) == "我的平台")
    #expect(restored.installed.last?.manifest.version == "1.1.0")
}
