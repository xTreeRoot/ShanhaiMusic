import Foundation
@testable import MBFoundation
import Testing

private actor VideoPreloadSource: VideoSource {
    nonisolated let displayName = "fixture"
    private(set) var requests: [String] = []
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func video(trackID: String) async throws -> MediaResource { try await video(track: videoPreloadTrack(trackID), qualityID: "auto") }
    func video(track: Track, qualityID: String) async throws -> MediaResource {
        requests.append("\(track.sourceTrackID):\(qualityID)")
        return MediaResource(url: URL(string: "https://fixture.test/\(track.sourceTrackID).mp4")!, headers: [:], fileExtension: "mp4")
    }
}
private func videoPreloadTrack(_ id: String) -> Track { Track(sourceID: "fixture", sourceTrackID: id, title: id, artist: "test", durationMilliseconds: 100_000) }

@Test @MainActor func nextVideoKeepsOnlyOnePrefixReusesItAndExpiresWithoutOpeningDecoder() async throws {
    let source = VideoPreloadSource()
    var time = ContinuousClock.now
    var requests = 0
    let preloader = NextVideoPreloader(source: source, now: { time }, prepare: { resource in
        let asset = try RemoteMediaAsset(resource: resource, transport: { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                headerFields: ["Content-Range": "bytes 0-262143/524288"])!
            return (Data(repeating: 3, count: 256 << 10), response)
        })
        requests += 1; try await asset.prime(); return asset
    })
    preloader.update(next: videoPreloadTrack("one"), quality: "720p", enabled: true); await preloader.wait()
    preloader.update(next: videoPreloadTrack("one"), quality: "720p", enabled: true); await preloader.wait()
    #expect(requests == 1)
    let ready = try #require(preloader.take(for: videoPreloadTrack("one"), quality: "720p"))
    try await ready.loader.prime()
    ready.cancel()
    preloader.update(next: videoPreloadTrack("two"), quality: "720p", enabled: true); await preloader.wait()
    preloader.update(next: videoPreloadTrack("three"), quality: "1080p", enabled: true); await preloader.wait()
    time += .seconds(91)
    #expect(preloader.take(for: videoPreloadTrack("three"), quality: "1080p") == nil)
    #expect(await source.requests == ["one:720p", "two:720p", "three:1080p"])
    preloader.update(next: videoPreloadTrack("four"), quality: "auto", enabled: false); await preloader.wait()
    #expect(requests == 3)
}

private actor UnreachableVideoSource: VideoSource {
    nonisolated let displayName = "unreachable"
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func video(trackID: String) async throws -> MediaResource {
        MediaResource(url: URL(string: "http://127.0.0.1:9/video.mp4")!, headers: [:], fileExtension: "mp4")
    }
}

@Test @MainActor func failedNativeVideoPreparationReturnsToMainActorForCancellation() async {
    let preloader = NextVideoPreloader(source: UnreachableVideoSource())
    let track = videoPreloadTrack("unreachable")
    preloader.update(next: track, quality: "auto", enabled: true)
    await preloader.wait()
    #expect(preloader.take(for: track, quality: "auto") == nil)
    preloader.cancel()
}
