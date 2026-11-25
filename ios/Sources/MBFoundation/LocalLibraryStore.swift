import Foundation

public struct LocalPlaylist: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public var name: String
    public var trackIDs: [String]
    public init(id: String = UUID().uuidString, name: String, trackIDs: [String] = []) {
        self.id = id; self.name = name; self.trackIDs = trackIDs
    }
}

public struct LocalLibrarySnapshot: Codable, Equatable, Sendable {
    public var favorites: [String] = []
    public var recent: [String] = []
    public var playlists: [LocalPlaylist] = []
    public var resources: [Track]?
    public init() {}
}

public enum LocalLibraryFailure: LocalizedError {
    case missingPlaylist, emptyName, invalidMove
    public var errorDescription: String? {
        switch self { case .missingPlaylist: AppLocalization.text("歌单不存在"); case .emptyName: AppLocalization.text("请输入歌单名称"); case .invalidMove: AppLocalization.text("曲目位置已变化，请重试") }
    }
}

/// 保存用户编排。丢失的文件暂不显示，但保留喜欢、歌单和最近播放的引用，重新导入后恢复。
public actor LocalLibraryStore {
    public static let shared = LocalLibraryStore()
    private let file: URL
    private var state: LocalLibrarySnapshot?
    public init(directory: URL? = nil) {
        let root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MBMobile")
        file = root.appendingPathComponent("local-library.json")
    }
    public func snapshot() throws -> LocalLibrarySnapshot {
        if let state { return state }
        let loaded = FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode(LocalLibrarySnapshot.self, from: Data(contentsOf: file)) : LocalLibrarySnapshot()
        state = loaded; return loaded
    }
    public func setFavorite(_ trackID: String, on: Bool) throws -> LocalLibrarySnapshot {
        try change { state in
            state.favorites.removeAll { $0 == trackID }
            if on { state.favorites.insert(trackID, at: 0) }
        }
    }
    func restoreDeviceBackup(_ incoming: DeviceArchiveLibrary, tracks: [String: Track], overwrite: Bool = false) throws -> LocalLibrarySnapshot {
        try change { state in
            if overwrite { state.favorites = []; state.recent = []; state.playlists = []; state.resources = [] }
            func mapped(_ key: String) -> String { tracks[key]?.id ?? key }
            for id in incoming.favorites.map(mapped) where !state.favorites.contains(id) { state.favorites.append(id) }
            for id in incoming.recent.map(mapped) where !state.recent.contains(id) { state.recent.append(id) }
            for item in incoming.playlists {
                let ids = item.tracks.map(mapped)
                if let at = state.playlists.firstIndex(where: { $0.id == item.id }) {
                    for id in ids where !state.playlists[at].trackIDs.contains(id) { state.playlists[at].trackIDs.append(id) }
                } else { state.playlists.append(LocalPlaylist(id: item.id, name: item.name, trackIDs: ids)) }
            }
            var resources = state.resources ?? []
            for track in tracks.values where track.localFileID == nil && !resources.contains(where: { $0.id == track.id }) { resources.append(track) }
            state.resources = resources
        }
    }
    public func saveResource(_ track: Track) throws -> LocalLibrarySnapshot {
        try change { state in
            var resources = state.resources ?? []; resources.removeAll { $0.id == track.id }
            resources.insert(track, at: 0); state.resources = resources
        }
    }
    public func removeResource(_ id: String) throws -> LocalLibrarySnapshot {
        try change { $0.resources?.removeAll { $0.id == id } }
    }
    public func toggleFavorite(_ trackID: String) throws -> LocalLibrarySnapshot {
        let on = try !snapshot().favorites.contains(trackID)
        return try setFavorite(trackID, on: on)
    }
    public func recordPlay(_ trackID: String) throws -> LocalLibrarySnapshot {
        try change { state in
            state.recent.removeAll { $0 == trackID }; state.recent.insert(trackID, at: 0)
            state.recent = Array(state.recent.prefix(200))
        }
    }
    public func createPlaylist(_ name: String) throws -> LocalLibrarySnapshot {
        let name = try checkedName(name)
        return try change { $0.playlists.append(LocalPlaylist(name: name)) }
    }
    public func renamePlaylist(_ id: String, name: String) throws -> LocalLibrarySnapshot {
        let name = try checkedName(name)
        return try changePlaylist(id) { $0.name = name }
    }
    public func deletePlaylist(_ id: String) throws -> LocalLibrarySnapshot {
        try change { state in
            guard state.playlists.contains(where: { $0.id == id }) else { throw LocalLibraryFailure.missingPlaylist }
            state.playlists.removeAll { $0.id == id }
        }
    }
    public func addTracks(_ trackIDs: [String], to id: String) throws -> LocalLibrarySnapshot {
        try changePlaylist(id) { playlist in
            var known = Set(playlist.trackIDs)
            playlist.trackIDs.append(contentsOf: trackIDs.filter { known.insert($0).inserted })
        }
    }
    public func removeTracks(_ trackIDs: [String], from id: String) throws -> LocalLibrarySnapshot {
        try changePlaylist(id) { playlist in let removed = Set(trackIDs); playlist.trackIDs.removeAll { removed.contains($0) } }
    }
    public func moveTracks(in id: String, offsets: IndexSet, to destination: Int, available: Set<String>) throws -> LocalLibrarySnapshot {
        try changePlaylist(id) { playlist in
            let indices = playlist.trackIDs.indices.filter { available.contains(playlist.trackIDs[$0]) }
            guard !offsets.isEmpty, offsets.allSatisfy({ indices.indices.contains($0) }), (0...indices.count).contains(destination) else { throw LocalLibraryFailure.invalidMove }
            var visible = indices.map { playlist.trackIDs[$0] }
            let moved = offsets.sorted().map { visible[$0] }
            for index in offsets.sorted(by: >) { visible.remove(at: index) }
            visible.insert(contentsOf: moved, at: destination - offsets.filter { $0 < destination }.count)
            // 暂时不在磁盘上的引用保持原槽位，只重排可见的曲目。
            for (offset, index) in indices.enumerated() { playlist.trackIDs[index] = visible[offset] }
        }
    }
    private func checkedName(_ name: String) throws -> String {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw LocalLibraryFailure.emptyName }; return value
    }
    private func changePlaylist(_ id: String, _ mutate: (inout LocalPlaylist) throws -> Void) throws -> LocalLibrarySnapshot {
        try change { state in
            guard let index = state.playlists.firstIndex(where: { $0.id == id }) else { throw LocalLibraryFailure.missingPlaylist }
            try mutate(&state.playlists[index])
        }
    }
    private func change(_ mutate: (inout LocalLibrarySnapshot) throws -> Void) throws -> LocalLibrarySnapshot {
        var next = try snapshot(); try mutate(&next)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try JSONEncoder().encode(next).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(next).write(to: file, options: .atomic)
        #endif
        state = next; return next
    }
}
