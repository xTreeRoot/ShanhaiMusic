import Foundation
import MBFoundation
import Testing

@MainActor private final class TestAudio: AudioEngine {
    var loaded: [Int64] = []
    var played = 0
    var stopped = 0
    var position: Int64 = 0
    var error: String?
    var finished = false
    func load(url: URL, at milliseconds: Int64) async throws { loaded.append(milliseconds); position = milliseconds }
    func play() throws { played += 1 }
    func pause() {}
    func stop() { stopped += 1; error = nil; finished = false }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot {
        AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: 90_000, finished: finished, errorMessage: error)
    }
}

private actor AudioSourceFixture: PlaybackSource {
    nonisolated let displayName = "test"
    private var audioCalls = 0
    private var lyricCalls = 0
    private var failures = 0
    private var hold = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) async throws -> MediaResource {
        audioCalls += 1
        if hold, trackID == "one" {
            await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        }
        if failures > 0 { failures -= 1; throw URLError(.networkConnectionLost) }
        return MediaResource(url: URL(string: "https://media.example/\(trackID)")!, headers: [:], fileExtension: "m4a")
    }
    func captions(trackID: String) async throws -> CaptionTimeline {
        lyricCalls += 1
        return CaptionTimeline(lines: [CaptionLine(startMilliseconds: 0, endMilliseconds: 1_000, text: trackID)])
    }
    func failNext(_ attempts: Int = 1) { failures = attempts }
    func calls() -> (Int, Int) { (audioCalls, lyricCalls) }
    func block() { hold = true }
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { hold = false; continuation?.resume(); continuation = nil }
}

private actor SessionFixture: PlaybackSessionStore {
    var session: PlaybackSession?
    init(_ session: PlaybackSession? = nil) { self.session = session }
    func loadSession() -> PlaybackSession? { session }
    func saveSession(_ session: PlaybackSession) { self.session = session }
}

private actor DelayedRestoreFixture: PlaybackSessionStore {
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    func loadSession() async -> PlaybackSession? {
        await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        return PlaybackSession(queue: audioTracks, index: 0, positionMilliseconds: 1000)
    }
    func saveSession(_ session: PlaybackSession) {}
    func waitForLoad() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}

private let audioTracks = ["one", "two"].map { Track(sourceID: "test", sourceTrackID: $0, title: $0, artist: "UP", durationMilliseconds: 90_000) }
private let fakeDownload: @Sendable (MediaResource) async throws -> URL = { _ in
    FileManager.default.temporaryDirectory.appendingPathComponent("mb-fake-\(UUID()).m4a")
}

@Test @MainActor func previewUsesRealQueueOrderWithoutSelectingOrResolvingAnotherTrack() async {
    let source = AudioSourceFixture()
    let model = PlaybackModel(source: source, engine: TestAudio(), download: fakeDownload)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    let calls = await source.calls()
    for _ in 0..<3 { #expect(model.neighboringTrack(forward: true)?.id == audioTracks[1].id) }
    #expect(model.queue.index == 0)
    #expect(await source.calls().0 == calls.0)
    model.toggleShuffle()
    let expected = model.queue.nextIndex(automatic: false).map { model.queue.tracks[$0].id }
    #expect(model.neighboringTrack(forward: true)?.id == expected)
}

@Test @MainActor func switchingSourceOnlyPausesWhenEnabledAndRetainsQueueAndPosition() async {
    let model = PlaybackModel(source: AudioSourceFixture(), engine: TestAudio(), download: fakeDownload)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    model.seek(to: 8_000)
    model.didSwitchSource(to: "other", pauseOtherSources: false)
    #expect(model.state == .playing)
    model.didSwitchSource(to: "test", pauseOtherSources: true)
    #expect(model.state == .playing)
    model.didSwitchSource(to: "other", pauseOtherSources: true)
    #expect(model.state == .paused)
    #expect(model.queue.tracks == audioTracks && model.queue.index == 0)
    #expect(model.positionMilliseconds == 8_000)
}

@Test @MainActor func restoredPlaybackIsPausedAndResolvesFreshAudioOnlyOnPlay() async {
    let source = AudioSourceFixture()
    let engine = TestAudio()
    let store = SessionFixture(PlaybackSession(queue: audioTracks, index: 1, positionMilliseconds: 42_000))
    let model = PlaybackModel(source: source, engine: engine, store: store, download: fakeDownload)
    await model.restore()
    #expect(model.queue.current == audioTracks[1])
    #expect(model.positionMilliseconds == 42_000)
    #expect(model.state == .paused)
    #expect(await source.calls().0 == 0)
    model.play()
    await model.waitUntilLoaded()
    #expect(engine.loaded == [42_000])
    #expect(engine.played == 1)
    #expect(model.timeline.lines.first?.text == "two")
    model.pause()
    await model.saveNow()
    #expect(await store.session?.positionMilliseconds == 42_000)
}

@Test @MainActor func brokenPlaybackKeepsTrackPositionAndReloadsLyricsOnRetry() async {
    let source = AudioSourceFixture()
    let engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    engine.position = 35_000
    model.tick()
    engine.position = 35_250
    engine.error = "broken stream"
    model.tick()
    #expect(model.state == .failed)
    #expect(model.positionMilliseconds == 35_250)
    #expect(model.queue.current == audioTracks[0])
    model.play()
    await model.waitUntilLoaded()
    #expect(engine.loaded == [0, 35_250])
    #expect(await source.calls().0 == 2)
    #expect(await source.calls().1 == 2)
    #expect(model.state == .playing)
}

@Test @MainActor func trackProblemStaysWithFailedSourceUntilSuccessfulRetry() async {
    let source = AudioSourceFixture()
    let model = PlaybackModel(source: source, engine: TestAudio(), download: fakeDownload)
    let track = audioTracks[0]
    let otherSource = Track(sourceID: "other", sourceTrackID: track.sourceTrackID, title: track.title, artist: track.artist)
    await source.failNext(2)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    #expect(model.trackProblem(for: track) == .network)
    #expect(model.trackProblem(for: otherSource) == nil)
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    #expect(model.trackProblem(for: track) == .network)
    #expect(model.trackProblem(for: audioTracks[1]) == nil)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    #expect(model.state == .playing)
    #expect(model.trackProblem(for: track) == nil)
}

@Test @MainActor func pauseDuringLoadingDoesNotAutoPlayAndSeekUsesLatestPosition() async {
    let source = AudioSourceFixture()
    await source.block()
    let engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await source.waitBlocked()
    model.seek(to: 12_000)
    model.pause()
    await source.release()
    await model.waitUntilLoaded()
    #expect(engine.played == 0)
    #expect(engine.position == 12_000)
    #expect(model.state == .paused)
    model.play()
    #expect(engine.played == 1)
    #expect(await source.calls().0 == 1)
}

@Test @MainActor func failedLoadRetainsResumePositionForAnotherAttempt() async {
    let source = AudioSourceFixture()
    await source.failNext(2)
    let engine = TestAudio()
    let store = SessionFixture(PlaybackSession(queue: audioTracks, index: 1, positionMilliseconds: 22_000))
    let model = PlaybackModel(source: source, engine: engine, store: store, download: fakeDownload)
    await model.restore()
    model.play()
    await model.waitUntilLoaded()
    #expect(model.state == .failed)
    #expect(model.positionMilliseconds == 22_000)
    model.play()
    await model.waitUntilLoaded()
    #expect(engine.loaded == [22_000])
    #expect(model.state == .playing)
}

@Test @MainActor func recoverableAudioFailureRefetchesOnceAndKeepsPausedVideoIntent() async {
    let source = AudioSourceFixture(), engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    await source.failNext()
    model.holdForVideo(true)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    #expect(await source.calls().0 == 2)
    #expect(model.state == .playing)
    #expect(engine.played == 0)
    model.pause()
    model.holdForVideo(false)
    #expect(engine.played == 0)
    model.play()
    #expect(engine.played == 1)
}

@Test @MainActor func manualNextWrapsButNaturalEndStopsAtEndOfQueue() async {
    let source = AudioSourceFixture()
    let engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    engine.finished = true
    model.tick()
    #expect(model.state == .paused)
    #expect(model.queue.index == 1)
    model.next()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 0)
    #expect(model.state == .playing)
}

@Test @MainActor func oldAudioDoesNotReplaceTheNewSelection() async {
    let source = AudioSourceFixture()
    await source.block()
    let engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await source.waitBlocked()
    let old = Task { await model.waitUntilLoaded() }
    await Task.yield()
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    await source.release()
    await old.value
    #expect(engine.loaded == [0])
    #expect(model.queue.current == audioTracks[1])
    #expect(model.timeline.lines.first?.text == "two")
}

@Test @MainActor func sessionRestoresCaptionsWithoutNetworkAndPauseDoesNotAdvanceQueue() async {
    let source = AudioSourceFixture()
    let engine = TestAudio()
    let saved = CaptionLine(startMilliseconds: 40_000, endMilliseconds: 44_000, text: "Saved caption")
    let store = SessionFixture(PlaybackSession(queue: audioTracks, index: 0, positionMilliseconds: 42_000, captions: [saved]))
    let model = PlaybackModel(source: source, engine: engine, store: store, download: fakeDownload)
    await model.restore()
    #expect(model.timeline.lines == [saved])
    #expect(await source.calls().1 == 0)
    model.play()
    await model.waitUntilLoaded()
    engine.finished = true
    model.pause()
    model.tick()
    #expect(model.queue.index == 0)
    await model.saveNow()
    #expect(await store.session?.captions?.first?.text == "one")
}

@Test @MainActor func repeatAllWrapsAndRepeatOneReplaysButManualNextStillAdvances() async {
    let engine = TestAudio()
    let model = PlaybackModel(source: AudioSourceFixture(), engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    model.cycleRepeat()
    engine.finished = true; model.tick()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 0)
    #expect(model.selectionIntent == .automatic)
    model.cycleRepeat()
    engine.finished = true; model.tick()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 0)
    model.next()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 1)
}

@Test @MainActor func clearingQueueStopsAudioAndDoesNotResurrectOldSession() async {
    let engine = TestAudio(), store = SessionFixture()
    let model = PlaybackModel(source: AudioSourceFixture(), engine: engine, store: store, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    model.removeQueue(IndexSet(integersIn: 0..<2))
    await model.saveNow()
    #expect(model.queue.current == nil)
    #expect(model.state == .paused)
    #expect(await store.session?.queue.isEmpty == true)
    let restored = PlaybackModel(source: AudioSourceFixture(), engine: TestAudio(), store: store, download: fakeDownload)
    await restored.restore()
    #expect(restored.queue.current == nil)
}

@Test @MainActor func clearingPlaybackRemovesPlayingAndPausedTracksAndRejectsLateAudio() async {
    for paused in [false, true] {
        let engine = TestAudio(), store = SessionFixture()
        let model = PlaybackModel(source: AudioSourceFixture(), engine: engine, store: store, download: fakeDownload)
        model.select(audioTracks, index: 0)
        await model.waitUntilLoaded()
        if paused { model.pause() }
        let plays = engine.played
        model.clear()
        model.play()
        await model.saveNow()
        #expect(model.queue.tracks.isEmpty)
        #expect(model.queue.current == nil)
        #expect(model.state == .paused)
        #expect(model.positionMilliseconds == 0)
        #expect(model.durationMilliseconds == 0)
        #expect(model.timeline.lines.isEmpty)
        #expect(engine.played == plays)
        #expect(await store.session?.queue.isEmpty == true)
    }
    let source = AudioSourceFixture(), engine = TestAudio()
    await source.block()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await source.waitBlocked()
    model.clear()
    await source.release()
    await model.waitUntilLoaded()
    #expect(model.queue.current == nil)
    #expect(engine.played == 0)
}

@Test @MainActor func clearingPlaybackWhileRestoringRejectsThePreviousPlatformsSession() async {
    let store = DelayedRestoreFixture()
    let model = PlaybackModel(source: AudioSourceFixture(), engine: TestAudio(), store: store, download: fakeDownload)
    let restoring = Task { await model.restore() }
    await store.waitForLoad()
    model.clear()
    await store.release()
    await restoring.value
    #expect(model.queue.current == nil)
    #expect(model.positionMilliseconds == 0)

    let saved = SessionFixture(PlaybackSession(queue: audioTracks, index: 0, positionMilliseconds: 1000))
    let clearedBeforeRestore = PlaybackModel(source: AudioSourceFixture(), engine: TestAudio(), store: saved)
    clearedBeforeRestore.clear()
    await clearedBeforeRestore.restore()
    #expect(clearedBeforeRestore.queue.current == nil)
}

@Test @MainActor func queueMoveKeepsCurrentAudioAndShuffleVisitsEachEntryOnce() async {
    let engine = TestAudio(), store = SessionFixture()
    let model = PlaybackModel(source: AudioSourceFixture(), engine: engine, store: store, download: fakeDownload)
    let tracks = (0..<8).map { Track(sourceID: "test", sourceTrackID: String($0), title: String($0), artist: "UP") }
    model.select(tracks, index: 3)
    await model.waitUntilLoaded()
    model.toggleShuffle()
    #expect(model.queue.current == tracks[3])
    #expect(model.queue.playOrder.first == 3)
    #expect(model.queue.playOrder.sorted() == Array(0..<8))
    model.moveQueue(IndexSet(integer: 3), to: 0)
    #expect(model.queue.index == 0)
    #expect(model.queue.current == tracks[3])
    #expect(engine.loaded.count == 1)
    var visited: [String] = []
    for _ in 0..<8 {
        visited.append(model.queue.current!.sourceTrackID)
        model.next(); await model.waitUntilLoaded()
    }
    #expect(Set(visited).count == 8)
    await model.saveNow()
    let restored = PlaybackModel(source: AudioSourceFixture(), engine: TestAudio(), store: store, download: fakeDownload)
    await restored.restore()
    #expect(restored.queue.shuffled)
    #expect(restored.queue.playOrder == model.queue.playOrder)
    #expect(restored.queue.index == model.queue.index)
}

private actor SlowSessionStore: PlaybackSessionStore {
    private var first = true
    private var blocked: CheckedContinuation<Void, Never>?
    private var blockedWaiter: CheckedContinuation<Void, Never>?
    private var savedWaiter: CheckedContinuation<Void, Never>?
    private var last: PlaybackSession?
    func loadSession() -> PlaybackSession? { nil }
    func saveSession(_ value: PlaybackSession) async {
        if first {
            first = false
            await withCheckedContinuation { blocked = $0; blockedWaiter?.resume(); blockedWaiter = nil }
        }
        last = value
        if value.index == 1 { savedWaiter?.resume(); savedWaiter = nil }
    }
    func waitBlocked() async {
        if blocked != nil { return }
        await withCheckedContinuation { blockedWaiter = $0 }
    }
    func release() { blocked?.resume(); blocked = nil }
    func waitForSecondTrack() async {
        if last?.index == 1 { return }
        await withCheckedContinuation { savedWaiter = $0 }
    }
    func saved() -> PlaybackSession? { last }
}

@Test @MainActor func ongoingPlaybackPersistsNewSelectionEvenWhilePreviousWriteIsPending() async {
    let source = AudioSourceFixture()
    let engine = TestAudio()
    let store = SlowSessionStore()
    let model = PlaybackModel(source: source, engine: engine, store: store, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await store.waitBlocked()
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    engine.position = 13_000
    for _ in 0..<20 { model.tick() }
    await store.release()
    await store.waitForSecondTrack()
    #expect(await store.saved()?.positionMilliseconds == 13_000)
    #expect(await store.saved()?.captions?.first?.text == "two")
}

@Test @MainActor func loadingCanBePausedResumedAndPausedAgainWithoutStartingAudio() async {
    let source = AudioSourceFixture()
    await source.block()
    let engine = TestAudio()
    let model = PlaybackModel(source: source, engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await source.waitBlocked()
    model.toggle()
    #expect(model.state == .paused)
    model.toggle()
    #expect(model.state == .loading)
    model.toggle()
    #expect(model.state == .paused)
    await source.release()
    await model.waitUntilLoaded()
    #expect(engine.played == 0)
}

@Test @MainActor func contentClicksTransportAndNaturalAdvanceEmitDistinctSelectionIntents() async {
    let engine = TestAudio()
    let model = PlaybackModel(source: AudioSourceFixture(), engine: engine, download: fakeDownload)
    model.select(audioTracks, index: 0)
    await model.waitUntilLoaded()
    #expect(model.selectionIntent == .content)
    model.next()
    await model.waitUntilLoaded()
    #expect(model.selectionIntent == .transport)
    model.previous()
    await model.waitUntilLoaded()
    #expect(model.selectionIntent == .transport)
    engine.finished = true
    model.tick()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 1)
    #expect(model.selectionIntent == .automatic)
    model.select(audioTracks, index: 1)
    await model.waitUntilLoaded()
    #expect(model.selectionIntent == .content)
}

private actor StationFixture: PlaybackSource, StationPlaybackSource {
    nonisolated let displayName = "Station"
    var batches = 0
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) async throws -> MediaResource { MediaResource(url: URL(string: "https://media.example/audio")!, headers: [:], fileExtension: "m4a") }
    func captions(trackID: String) async throws -> CaptionTimeline { CaptionTimeline(lines: []) }
    func stationNext(_ station: PlaybackStation) async throws -> [Track] {
        batches += 1
        return [Track(sourceID: "station", sourceTrackID: "batch-\(batches)", title: "Next", artist: "", durationMilliseconds: 1000)]
    }
}

@Test @MainActor func stationRefillsQueueAdvancesAndPersistsWithoutResumingOnRestore() async throws {
    let source = StationFixture(), engine = TestAudio(), store = SessionFixture()
    let model = PlaybackModel(source: source, engine: engine, store: store, download: fakeDownload)
    let station = PlaybackStation(sourceID: "station", id: "daily", title: "Daily")
    model.startStation(station, tracks: audioTracks, index: 1)
    await model.waitUntilLoaded()
    while model.queue.tracks.count < 3 { await Task.yield() }
    engine.finished = true; model.tick()
    #expect(model.queue.current?.sourceTrackID == "batch-1")
    await model.saveNow()
    let reopened = PlaybackModel(source: source, engine: TestAudio(), store: store)
    await reopened.restore()
    #expect(reopened.station == station)
    #expect(reopened.state == .paused)
    model.select(audioTracks, index: 0, autoplay: false)
    #expect(model.station == nil)
}
