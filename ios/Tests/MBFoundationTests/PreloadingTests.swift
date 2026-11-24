import Foundation
@testable import MBFoundation
import Testing

private actor PreloadSource: PlaybackSource {
    nonisolated let displayName = "preload"
    private(set) var calls: [String] = []
    var fail = false
    func failing(_ value: Bool) { fail = value }
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) async throws -> MediaResource {
        calls.append(trackID)
        if fail { throw URLError(.cannotConnectToHost) }
        return MediaResource(url: URL(string: "https://fixture.test/\(trackID)")!, headers: [:], fileExtension: "m4a")
    }
    func captions(trackID: String) async throws -> CaptionTimeline { CaptionTimeline(lines: []) }
}
@MainActor private final class PreloadToken: PreparedAudio {
    let url: URL
    var cancelled = false
    var consumed = false
    init(_ url: URL) { self.url = url }
    func cancel() { if !consumed { cancelled = true } }
}
@MainActor private final class PreloadEngine: AudioEngine, AudioEnginePreloading {
    var onUpdate: (() -> Void)?
    var position: Int64 = 0
    var duration: Int64 = 100_000
    var finished = false
    var warmed: [PreloadToken] = []
    var loaded: [URL] = []
    var reused = 0
    var failPreparedLoad = false
    var hold = false
    var continuation: CheckedContinuation<Void, Never>?
    func prepare(resource: MediaResource) async throws -> any PreparedAudio {
        let token = PreloadToken(resource.url); warmed.append(token)
        if hold { await withCheckedContinuation { continuation = $0 } }
        return token
    }
    func load(prepared: any PreparedAudio, at milliseconds: Int64) async throws {
        let token = try #require(prepared as? PreloadToken)
        if failPreparedLoad { throw URLError(.timedOut) }
        #expect(!token.cancelled)
        token.consumed = true; reused += 1; loaded.append(token.url); position = milliseconds
    }
    func load(resource: MediaResource, at milliseconds: Int64) async throws { loaded.append(resource.url); position = milliseconds }
    func load(url: URL, at milliseconds: Int64) async throws { loaded.append(url); position = milliseconds }
    func play() throws {}
    func pause() {}
    func stop() { finished = false }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot { AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: duration, finished: finished) }
}
private let preloadTracks = ["one", "two", "three"].map { Track(sourceID: "preload", sourceTrackID: $0, title: $0, artist: "test", durationMilliseconds: 100_000) }

@Test @MainActor func preloadingStartsAtSeventyPercentOnceAndNextPlaybackReusesResource() async {
    let source = PreloadSource(), engine = PreloadEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(preloadTracks, index: 0); await model.waitUntilLoaded()
    engine.position = 69_999; model.tick(); await model.waitUntilPrepared()
    #expect(await source.calls == ["one"])
    engine.position = 70_000; model.tick(); await model.waitUntilPrepared()
    #expect(await source.calls == ["one", "two"])
    #expect(model.queue.current == preloadTracks[0]); #expect(engine.loaded.count == 1)
    for _ in 0..<10 { model.tick() }
    await model.waitUntilPrepared()
    #expect(engine.warmed.count == 1)
    engine.finished = true; model.tick(); await model.waitUntilLoaded()
    #expect(engine.reused == 1)
    #expect(await source.calls == ["one", "two"])
    #expect(!engine.warmed[0].cancelled)
    #expect(model.queue.current == preloadTracks[1])
}

@Test @MainActor func changingQueueQualityPauseOrSeekCancelsPreparedAudio() async {
    let source = PreloadSource(), engine = PreloadEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(preloadTracks, index: 0); await model.waitUntilLoaded()
    for action in 0..<4 {
        engine.position = 75_000; model.tick(); await model.waitUntilPrepared()
        let token = engine.warmed.last!
        switch action {
        case 0: model.enqueue(preloadTracks[2], next: true)
        case 1: model.invalidatePreparedAudio()
        case 2: model.pause(); model.play()
        default: model.seek(to: 10_000)
        }
        #expect(token.cancelled)
    }
    model.clear(); #expect(model.queue.current == nil)
}

@Test @MainActor func latePreloadAfterSourceSwitchIsCancelledAndCannotReviveQueue() async {
    let source = PreloadSource(), engine = PreloadEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(preloadTracks, index: 0); await model.waitUntilLoaded()
    engine.hold = true; engine.position = 70_000; model.tick()
    while engine.continuation == nil { await Task.yield() }
    model.clear()
    engine.continuation?.resume(); engine.continuation = nil
    for _ in 0..<20 { await Task.yield() }
    #expect(engine.warmed.first?.cancelled == true)
    #expect(model.queue.current == nil); #expect(model.state == .paused)
}

@Test @MainActor func preloadingFailureIsSilentAndExpiredOrRejectedPreparationResolvesFreshAudio() async {
    let source = PreloadSource(), engine = PreloadEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(preloadTracks, index: 0); await model.waitUntilLoaded()
    await source.failing(true); engine.position = 70_000; model.tick(); await model.waitUntilPrepared()
    #expect(model.state == .playing); #expect(model.errorMessage == nil)
    model.tick(); await model.waitUntilPrepared(); #expect(await source.calls.count == 2)
    await source.failing(false); model.invalidatePreparedAudio(); model.tick(); await model.waitUntilPrepared()
    engine.failPreparedLoad = true
    model.next(); await model.waitUntilLoaded()
    #expect(model.state == .playing); #expect(model.errorMessage == nil)
    #expect(await source.calls == ["one", "two", "two", "two"])
    let clock = PreloadClock()
    let preloader = NextAudioPreloader(source: source, engine: engine, now: { clock.instant })
    preloader.update(next: preloadTracks[2], enabled: true); await preloader.wait()
    clock.instant = clock.instant.advanced(by: .seconds(91))
    #expect(preloader.take(for: preloadTracks[2]) == nil)
    #expect(engine.warmed.last?.cancelled == true)
}

@Test @MainActor func preloaderRespectsEndOfQueueShuffleAndSingleRepeat() async {
    let source = PreloadSource(), engine = PreloadEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(preloadTracks, index: 2); await model.waitUntilLoaded()
    engine.position = 80_000; model.tick(); await model.waitUntilPrepared()
    #expect(engine.warmed.isEmpty)
    model.cycleRepeat(); model.tick(); await model.waitUntilPrepared()
    #expect(engine.warmed.last?.url.lastPathComponent == "one")
    model.cycleRepeat(); model.tick(); await model.waitUntilPrepared()
    #expect(engine.warmed.last?.url.lastPathComponent == "three")
    model.cycleRepeat(); model.toggleShuffle()
    let expected = model.queue.nextIndex(automatic: true).map { model.queue.tracks[$0].sourceTrackID }
    model.tick(); await model.waitUntilPrepared()
    if let expected { #expect(engine.warmed.last?.url.lastPathComponent == expected) }
    model.clear()
}

@MainActor private final class PreloadClock { var instant = ContinuousClock.now }
