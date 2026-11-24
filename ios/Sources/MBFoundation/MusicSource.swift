import Foundation

/// 平台返回的曲目使用来源内 ID；UI 的 ID 同时包含来源，避免跨平台冲突。
public struct Track: Identifiable, Equatable, Codable, Sendable {
    public let sourceID: String
    public let sourceTrackID: String
    public let title: String
    public let artist: String
    public var artworkURL: URL?
    public let durationMilliseconds: Int64
    public let pageURL: URL?
    public var artworkHeaders: [String: String] = [:]
    public var artistID: String?
    public var unavailable: Bool?
    public var unavailableReason: TrackProblem?
    public var localFileID: String?
    public var localMetadata: LocalAudioMetadata?
    public var resourceType: String?
    public var resourceURL: URL?
    public var videoWidth: Int?
    public var videoHeight: Int?
    public var shortVideo: Bool?

    public var id: String { "\(sourceID):\(sourceTrackID)" }

    public var declaredProblem: TrackProblem? {
        unavailableReason ?? (unavailable == true ? .unavailable : nil)
    }

    public init(sourceID: String, sourceTrackID: String, title: String, artist: String,
                artworkURL: URL? = nil, durationMilliseconds: Int64 = 0, pageURL: URL? = nil) {
        self.sourceID = sourceID
        self.sourceTrackID = sourceTrackID
        self.title = title
        self.artist = artist
        self.artworkURL = artworkURL
        self.durationMilliseconds = max(0, durationMilliseconds)
        self.pageURL = pageURL
    }
}

public enum TrackProblem: String, Codable, LocalizedError, Sendable {
    case vip, noSource, signIn, unavailable, network, unknown

    public var label: String {
        switch self {
        case .vip: AppLocalization.text("需要 VIP")
        case .noSource: AppLocalization.text("音源不可用")
        case .signIn: AppLocalization.text("登录后可播放")
        case .unavailable: AppLocalization.text("此曲目暂不可播放")
        case .network: AppLocalization.text("网络错误，可重试")
        case .unknown: AppLocalization.text("播放失败，原因未知")
        }
    }

    public var errorDescription: String? { label }

    static func declared(_ code: String?) -> Self? {
        switch code {
        case "vip": .vip
        case "noSource": .noSource
        case "signIn": .signIn
        case "unavailable": .unavailable
        default: nil
        }
    }

    static func isConnectionError(_ error: Error) -> Bool {
        guard let error = error as? URLError else { return false }
        switch error.code {
        case .timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
             .dnsLookupFailed, .notConnectedToInternet, .internationalRoamingOff, .dataNotAllowed:
            return true
        default: return false
        }
    }
}

public struct TrackPage: Sendable {
    public let tracks: [Track]
    public let hasMore: Bool
    public var warnings: [String] = []

    public init(tracks: [Track], hasMore: Bool) {
        self.tracks = tracks
        self.hasMore = hasMore
    }
}

/// 页面仅依赖通用能力；平台地址、请求与响应转换留在音源模块。
public protocol MusicSource: Sendable {
    var displayName: String { get }
    func search(query: String, page: Int) async throws -> TrackPage
}

public enum DiscoveryCategory: String, CaseIterable, Codable, Identifiable, Sendable {
    case recommended, chart, trending, fresh
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .recommended: "为你推荐"
        case .chart: "音乐榜"
        case .trending: "音乐区热门"
        case .fresh: "新鲜投稿"
        }
    }
}

public struct DiscoverySection: Identifiable, Sendable {
    public let category: DiscoveryCategory
    public let tracks: [Track]
    public let subtitle: String?
    public let errorMessage: String?
    public var id: DiscoveryCategory { category }

    public init(category: DiscoveryCategory, tracks: [Track], subtitle: String? = nil, errorMessage: String? = nil) {
        self.category = category
        self.tracks = tracks
        self.subtitle = subtitle
        self.errorMessage = errorMessage
    }
}

public protocol DiscoverySource: MusicSource {
    func home() async throws -> [DiscoverySection]
    func discovery(_ category: DiscoveryCategory) async throws -> DiscoverySection
}

public struct MediaResource: Sendable {
    public let url: URL
    public let headers: [String: String]
    public let fileExtension: String
    public let videoQuality: String?
    public let videoQualities: [VideoQualityOption]
    public init(url: URL, headers: [String: String], fileExtension: String, videoQuality: String? = nil, videoQualities: [VideoQualityOption] = []) {
        self.url = url
        self.headers = headers
        self.fileExtension = fileExtension
        self.videoQuality = videoQuality
        self.videoQualities = videoQualities
    }
}

public protocol PlaybackSource: MusicSource {
    func audio(track: Track, quality: AudioQuality) async throws -> MediaResource
    func audio(track: Track) async throws -> MediaResource
    func captions(track: Track) async throws -> CaptionTimeline
    func audio(trackID: String) async throws -> MediaResource
    func captions(trackID: String) async throws -> CaptionTimeline
}

public protocol VideoSource: MusicSource {
    func video(track: Track, qualityID: String) async throws -> MediaResource
    func video(track: Track, quality: AudioQuality) async throws -> MediaResource
    func supportsVideo(track: Track) async -> Bool
    func video(track: Track) async throws -> MediaResource
    func video(trackID: String) async throws -> MediaResource
}

public enum PlaybackMode: String, Codable, Sendable { case music, video }

public protocol PlaybackModeStore: Sendable {
    func loadPlaybackMode() async throws -> PlaybackMode?
    func savePlaybackMode(_ mode: PlaybackMode) async throws
}

public enum TimeLabel {
    public static func string(milliseconds: Int64) -> String {
        let seconds = max(0, milliseconds) / 1_000
        return "\(seconds / 60):\(String(format: "%02d", seconds % 60))"
    }
}


public extension PlaybackSource {
    func audio(track: Track, quality: AudioQuality) async throws -> MediaResource { try await audio(track: track) }
    func audio(track: Track) async throws -> MediaResource { try await audio(trackID: track.sourceTrackID) }
    func captions(track: Track) async throws -> CaptionTimeline { try await captions(trackID: track.sourceTrackID) }
}
public extension VideoSource {
    func video(track: Track, qualityID: String) async throws -> MediaResource { try await video(track: track) }
    func video(track: Track, quality: AudioQuality) async throws -> MediaResource { try await video(track: track) }
    func supportsVideo(track: Track) async -> Bool { true }
    func video(track: Track) async throws -> MediaResource { try await video(trackID: track.sourceTrackID) }
}

public protocol SuggestionSource: MusicSource { func suggestions(_ query: String) async throws -> [String] }
public protocol PlaybackRecorder: MusicSource { func played(_ track: Track) async }
