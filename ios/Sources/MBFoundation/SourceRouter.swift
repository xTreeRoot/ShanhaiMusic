import Foundation

/// 队列按曲目来源解析媒体；切换浏览音源后，旧队列继续使用原音源。
public actor SourceRouter: PlaybackSource, VideoSource {
    public nonisolated let displayName = "山海"
    private var sources: [String: any MusicSource] = [:]
    private var quality = AudioQuality.high
    public func setQuality(_ quality: AudioQuality) { self.quality = quality }
    public init() {}
    public func register(_ source: any MusicSource, id: String) { sources[id] = source }
    public func keep(_ ids: Set<String>) { sources = sources.filter { ids.contains($0.key) } }
    public func remove(_ id: String) { sources[id] = nil }
    public func search(query: String, page: Int) async throws -> TrackPage { throw PluginFailure.invalid("Select a source before searching") }
    public func audio(trackID: String) async throws -> MediaResource { throw PluginFailure.invalid("A source ID is required") }
    public func captions(trackID: String) async throws -> CaptionTimeline { throw PluginFailure.invalid("A source ID is required") }
    public func video(trackID: String) async throws -> MediaResource { throw PluginFailure.invalid("A source ID is required") }
    public func audio(track: Track) async throws -> MediaResource {
        guard let source = sources[track.sourceID] as? any PlaybackSource else { throw PluginFailure.unavailable }
        return try await source.audio(track: track, quality: quality)
    }
    public func captions(track: Track) async throws -> CaptionTimeline {
        guard let source = sources[track.sourceID] as? any PlaybackSource else { throw PluginFailure.unavailable }
        return try await source.captions(track: track)
    }
    public func supportsVideo(track: Track) async -> Bool {
        guard let source = sources[track.sourceID] as? any VideoSource else { return false }
        return await source.supportsVideo(track: track)
    }
    public func video(track: Track) async throws -> MediaResource {
        guard let source = sources[track.sourceID] as? any VideoSource else { throw PluginFailure.unavailable }
        return try await source.video(track: track, quality: quality)
    }
    public func video(track: Track, qualityID: String) async throws -> MediaResource {
        guard let source = sources[track.sourceID] as? any VideoSource else { throw PluginFailure.unavailable }
        return try await source.video(track: track, qualityID: qualityID)
    }
    public func source(for track: Track) -> (any MusicSource)? { sources[track.sourceID] }
    public func source(id: String) -> (any MusicSource)? { sources[id] }
}
