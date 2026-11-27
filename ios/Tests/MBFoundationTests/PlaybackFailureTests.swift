import Foundation
@testable import MBFoundation
import Testing

private actor FailureSource: PlaybackSource {
    nonisolated let displayName = "failure fixture"
    private(set) var calls: [String] = []
    var broken: Set<String> = []
    func setBroken(_ ids: Set<String>) { broken = ids }
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func captions(trackID: String) async throws -> CaptionTimeline { CaptionTimeline(lines: []) }
    func audio(trackID: String) async throws -> MediaResource {
        calls.append(trackID)
        if broken.contains(trackID) { throw PluginFailure.playback(.noSource, "Fixture unavailable") }
        return MediaResource(url: URL(string: "https://fixture.test/\(trackID)")!, headers: [:], fileExtension: "m4a")
    }
}

@MainActor private final class FailureAudio: PreparedAudio {
    let id: String
    init(_ id: String) { self.id = id }
    func cancel() {}
}

@MainActor private final class FailureEngine: AudioEngine, AudioEnginePreloading {
    var loaded: [String] = []
    var prepared: [String] = []
    var reused: [String] = []
    var broken: Set<String> = []
    var error: String?
    var position: Int64 = 0
    var finished = false
    func load(url: URL, at milliseconds: Int64) async throws { loaded.append(url.lastPathComponent); position = milliseconds }
    func load(resource: MediaResource, at milliseconds: Int64) async throws { try await load(url: resource.url, at: milliseconds) }
    func prepare(resource: MediaResource) async throws -> any PreparedAudio {
        let id = resource.url.lastPathComponent
        prepared.append(id)
        if broken.contains(id) { throw PluginFailure.playback(.noSource, "Fixture HTTP failure") }
        return FailureAudio(id)
    }
    func load(prepared: any PreparedAudio, at milliseconds: Int64) async throws {
        let audio = try #require(prepared as? FailureAudio)
        reused.append(audio.id); loaded.append(audio.id); position = milliseconds
    }
    func play() throws {}
    func pause() {}
    func stop() { error = nil; finished = false }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot {
        AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: 100_000, finished: finished, errorMessage: error)
    }
}

private let failureTracks = ["one", "two", "three", "four"].map {
    Track(sourceID: "fixture", sourceTrackID: $0, title: $0, artist: "fixture", durationMilliseconds: 100_000)
}

@Test @MainActor func currentAndConsecutiveFailuresAutomaticallyReachPlayableTrack() async {
    let source = FailureSource(), engine = FailureEngine()
    await source.setBroken(["one", "two"])
    let model = PlaybackModel(source: source, engine: engine)
    model.select(failureTracks, index: 0)
    await model.waitUntilLoaded()
    #expect(model.queue.current == failureTracks[2] && model.state == .playing)
    #expect(await source.calls == ["one", "two", "three"])
    #expect(model.trackProblem(for: failureTracks[0]) == .noSource)
    #expect(model.queue.tracks == failureTracks)
    model.clear()
}

@Test @MainActor func runtimeFailureUsesShuffleOrderAndEscapesRepeatOne() async {
    let source = FailureSource(), engine = FailureEngine()
    let model = PlaybackModel(source: source, engine: engine)
    model.select(failureTracks, index: 0); await model.waitUntilLoaded()
    model.queue.restorePreferences(shuffled: true, repeatMode: .one, order: [0, 2, 1, 3])
    engine.error = "broken decoder"; model.tick(); await model.waitUntilLoaded()
    #expect(model.state == .playing && model.queue.current == failureTracks[2])
    #expect(engine.loaded == ["one", "three"])
    #expect(model.trackProblem(for: failureTracks[0]) == .unknown)
    model.clear()
}

@Test(arguments: [false, true]) @MainActor func failedNextPreloadsFollowingTrackAndHandsItOff(http: Bool) async {
    let source = FailureSource(), engine = FailureEngine()
    if http { engine.broken = ["two", "three"] } else { await source.setBroken(["two", "three"]) }
    let model = PlaybackModel(source: source, engine: engine)
    model.select(failureTracks, index: 0); await model.waitUntilLoaded()
    engine.position = 70_000; model.tick(); await model.waitUntilPrepared()
    #expect(model.state == .playing && model.queue.current == failureTracks[0] && model.errorMessage == nil)
    #expect(model.neighboringTrack(forward: true) == failureTracks[3])
    #expect(engine.prepared.last == "four")
    for _ in 0..<5 { model.tick() }; await model.waitUntilPrepared()
    #expect(await source.calls == ["one", "two", "three", "four"])
    engine.finished = true; model.tick(); await model.waitUntilLoaded()
    #expect(model.queue.current == failureTracks[3] && model.state == .playing)
    #expect(engine.reused == ["four"])
    #expect(await source.calls == ["one", "two", "three", "four"])
    model.clear()
}

@Test @MainActor func allFailedTracksStopAfterOnePassAndExplicitRetryRemainsPossible() async {
    let source = FailureSource(), engine = FailureEngine()
    await source.setBroken(Set(failureTracks.map(\.sourceTrackID)))
    let model = PlaybackModel(source: source, engine: engine)
    model.cycleRepeat(); model.cycleRepeat()
    model.select(failureTracks, index: 0); await model.waitUntilLoaded()
    #expect(model.state == .failed)
    #expect(await source.calls == ["one", "two", "three", "four"])
    await source.setBroken([])
    model.jump(to: 0); await model.waitUntilLoaded()
    #expect(model.state == .playing && model.queue.current == failureTracks[0])
    #expect(model.trackProblem(for: failureTracks[0]) == nil)
    model.clear()
}

@Test @MainActor func pausedRuntimeFailureDoesNotAdvanceOrResume() async {
    let engine = FailureEngine()
    let model = PlaybackModel(source: FailureSource(), engine: engine)
    model.select(failureTracks, index: 0); await model.waitUntilLoaded()
    model.pause()
    engine.error = "late paused decoder failure"; model.tick()
    // 暂停时不能因后台错误自动选曲；主动播放仍可重新解析。
    #expect(model.state == .failed && model.queue.current == failureTracks[0])
    model.clear()
}
