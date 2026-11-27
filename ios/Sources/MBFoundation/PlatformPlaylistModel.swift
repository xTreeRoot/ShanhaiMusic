import Foundation
import Observation

/// 使用外部插件的现有歌单合同；只允许写入同一插件账号拥有的普通歌单。
extension PluginSource {
    public func writablePlaylists() async throws -> [PluginEntity] {
        guard try await supports("playlists"), try await supports("addToPlaylist") else {
            throw PluginFailure.invalid("此插件尚未提供平台歌单写入接口")
        }
        guard try await account() != nil else { throw PluginFailure.invalid("请先登录当前插件的平台账号") }
        let value = try await catalogInvoke("playlists", refresh: true)
        guard case .array = value else { throw PluginFailure.invalid("平台歌单响应无效") }
        return try await entities(.object(["playlists": value])).filter {
            $0.value["mine"] == .bool(true) && ($0.value["fixed"] != .bool(true) || $0.value["addable"] == .bool(true))
        }
    }

    public func add(_ track: Track, toPlatformPlaylist playlist: PluginEntity) async throws {
        guard track.sourceID == manifest.id, playlist.kind == .playlist else {
            throw PluginFailure.invalid("只能添加当前插件的歌曲")
        }
        let generation = accountGeneration
        let targets = try await writablePlaylists()
        guard generation == accountGeneration else { throw CancellationError() }
        guard targets.contains(where: { $0.sourceID == playlist.sourceID }) else {
            throw PluginFailure.invalid("这个歌单已不可编辑，请重新选择")
        }
        try await mutate("addToPlaylist", arguments: .object([
            "id": .string(playlist.sourceID), "trackIds": .array([.string(track.sourceTrackID)])
        ]))
    }
}

@MainActor @Observable
public final class PlatformPlaylistModel {
    public private(set) var playlists: [PluginEntity] = []
    public private(set) var loading = false
    public private(set) var saving = false
    public private(set) var error: String?
    private let source: PluginSource
    private let track: Track
    public init(source: PluginSource, track: Track) { self.source = source; self.track = track }
    public func load() async {
        guard !loading else { return }
        loading = true; error = nil
        defer { loading = false }
        do {
            let value = try await source.writablePlaylists()
            try Task.checkCancellation(); playlists = value
        } catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
    public func add(to playlist: PluginEntity) async -> Bool {
        guard !saving else { return false }
        saving = true; error = nil
        defer { saving = false }
        do { try await source.add(track, toPlatformPlaylist: playlist); try Task.checkCancellation(); return true }
        catch is CancellationError { return false }
        catch { self.error = error.localizedDescription; return false }
    }
}
