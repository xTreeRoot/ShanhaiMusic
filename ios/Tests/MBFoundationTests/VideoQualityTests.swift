import Foundation
import MBFoundation
import Testing

private actor QualitySource: VideoSource {
    nonisolated let displayName = "Quality fixture"
    private var requests: [(String, String)] = []
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func video(trackID: String) -> MediaResource { fatalError("Must forward quality") }
    func video(track: Track, qualityID: String) -> MediaResource {
        requests.append((track.sourceTrackID, qualityID))
        return MediaResource(url: URL(string: "https://media.example/\(qualityID).mp4")!, headers: [:], fileExtension: "mp4",
            videoQuality: track.sourceTrackID == "low" ? "720p" : qualityID,
            videoQualities: [VideoQualityOption(id: "1080p", label: "1080p")!, VideoQualityOption(id: "720p", label: "720p")!])
    }
    func qualities() -> [String] { requests.map(\.1) }
}
@MainActor private final class QualityEngine: VideoEngine {
    var positions: [Int64] = []
    var playing = false
    var errorMessage: String? { nil }
    func load(url: URL, at milliseconds: Int64) { positions.append(milliseconds) }
    func sync(positionMilliseconds: Int64, playing: Bool) { self.playing = playing }
    func stop() {}
}

@Test @MainActor func videoQualitySurvivesFallbackNextVideoAndPreferenceReloadWhileKeepingClock() async throws {
    var preferences = AppPreferences(); preferences.videoQuality = "1080p"
    let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(preferences))
    let source = QualitySource(), engine = QualityEngine(), model = VideoModel(source: source, engine: engine)
    model.setQuality(restored.videoQuality)
    await model.choose(.video)
    let low = Track(sourceID: "fixture", sourceTrackID: "low", title: "Low", artist: "")
    let next = Track(sourceID: "fixture", sourceTrackID: "next", title: "Next", artist: "")
    model.handleSelection(low, intent: .content, positionMilliseconds: 12_345, playing: true)
    await model.waitUntilLoaded()
    #expect(model.actualQualityID == "720p" && model.qualityID == "1080p")
    model.handleSelection(next, intent: .automatic, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(await source.qualities() == ["1080p", "1080p"])
    model.sync(positionMilliseconds: 34_567, playing: false)
    model.setQuality("720p")
    await model.waitUntilLoaded()
    #expect(engine.positions.last == 34_567 && !engine.playing)
    #expect(model.qualityID == "720p" && model.actualQualityID == "720p")
    #expect(await source.qualities() == ["1080p", "1080p", "720p"])
}
