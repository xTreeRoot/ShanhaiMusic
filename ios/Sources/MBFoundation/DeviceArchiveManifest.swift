import Foundation

struct DeviceArchivePlugin: Codable, Sendable {
    var id: String
    var repository: String?
    var revision: String?
    var name: String?
    var enabled: Bool
    var settings: [String: String]
}

/// 与桌面和 Android 共用的持久曲目元数据；asset 始终是归档内相对路径。
struct DeviceArchiveTrack: Codable, Sendable {
    var source: String
    var track: String
    var title: String
    var artist: String
    var artwork: String?
    var duration: Int64
    var page: String?
    var author: String?
    var asset: String?
    var kind: String?
    var lyrics: String?
    var album: String?
    var albumArtist: String?
    var folder: String?
    var year: String?
    var trackNumber: Int?
    var discNumber: Int?
    var resourceType: String?
    var resourceUrl: String?
    var key: String { source + ":" + track }

    init(_ item: Track, asset: String? = nil, kind: String? = nil) {
        source = item.sourceID; track = item.sourceTrackID; title = item.title; artist = item.artist
        artwork = item.artworkURL?.scheme == "https" ? item.artworkURL?.absoluteString : nil
        duration = item.durationMilliseconds; page = item.pageURL?.absoluteString; author = item.artistID
        self.asset = asset; self.kind = kind
        lyrics = item.localMetadata?.lyrics; album = item.localMetadata?.album; albumArtist = item.localMetadata?.albumArtist
        folder = item.localMetadata?.folderID; year = item.localMetadata?.year
        trackNumber = item.localMetadata?.trackNumber; discNumber = item.localMetadata?.discNumber
        resourceType = item.resourceType; resourceUrl = item.resourceURL?.absoluteString
    }

    func metadata(localID: String? = nil) -> Track {
        var result = Track(sourceID: localID == nil ? source : "local", sourceTrackID: localID ?? track,
                           title: title, artist: artist, artworkURL: artwork.flatMap(URL.init(string:)),
                           durationMilliseconds: duration, pageURL: page.flatMap(URL.init(string:)))
        result.artistID = author; result.localFileID = localID
        result.localMetadata = LocalAudioMetadata(album: album, albumArtist: albumArtist, year: year,
            trackNumber: trackNumber, discNumber: discNumber, folderID: folder, lyrics: lyrics)
        result.resourceType = resourceType; result.resourceURL = resourceUrl.flatMap(URL.init(string:))
        return result
    }
}

struct DeviceArchivePlaylist: Codable, Sendable { let id: String; let name: String; let tracks: [String] }
struct DeviceArchiveQueue: Codable, Sendable {
    let tracks: [DeviceArchiveTrack]
    let index: Int
    let position: Int64
    let shuffled: Bool
    let `repeat`: String
}
struct DeviceArchiveLibrary: Codable, Sendable {
    var tracks: [DeviceArchiveTrack] = []
    var favorites: [String] = []
    var recent: [String] = []
    var playlists: [DeviceArchivePlaylist] = []
    var searchHistory: [String] = []
    var queue: DeviceArchiveQueue?
    enum CodingKeys: String, CodingKey { case tracks, favorites, recent, playlists, searchHistory, queue }
    init() {}
    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        tracks = try values.decodeIfPresent([DeviceArchiveTrack].self, forKey: .tracks) ?? []
        favorites = try values.decodeIfPresent([String].self, forKey: .favorites) ?? []
        recent = try values.decodeIfPresent([String].self, forKey: .recent) ?? []
        playlists = try values.decodeIfPresent([DeviceArchivePlaylist].self, forKey: .playlists) ?? []
        searchHistory = try values.decodeIfPresent([String].self, forKey: .searchHistory) ?? []
        queue = try values.decodeIfPresent(DeviceArchiveQueue.self, forKey: .queue)
    }
}

struct DeviceArchiveManifest: Codable, Sendable {
    var version = 1
    var platform: String
    var plugins: [DeviceArchivePlugin]
    var library = DeviceArchiveLibrary()
    var pluginPreferences: PluginPreferences?
    var discoveryPreferences: DiscoveryPreferences?

    func validated() throws -> Self {
        guard version == 1, ["desktop", "ios", "android"].contains(platform), plugins.count <= 1000 else { throw PlatformDataFailure.invalid }
        var ids = Set<String>()
        for item in plugins {
            guard item.id.range(of: #"\A[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\z"#, options: .regularExpression) != nil,
                  !item.id.contains(".."), item.id.lowercased() != "local", ids.insert(item.id.lowercased()).inserted else { throw PlatformDataFailure.invalid }
        }
        var tracks = Set<String>()
        for item in library.tracks {
            guard !item.source.isEmpty, !item.track.isEmpty, tracks.insert(item.key).inserted,
                  item.asset.map({ DeviceArchiveFile.validPath($0) && $0.hasPrefix("media/") }) ?? true else { throw PlatformDataFailure.invalid }
        }
        _ = try pluginPreferences?.validated(); _ = try discoveryPreferences?.validated()
        return self
    }
}
