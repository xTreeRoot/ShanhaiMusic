import Foundation
import MBFoundation
import Testing

@MainActor private final class TestVideo: VideoEngine, VideoEngineEvents {
    var onUpdate: (() -> Void)?
    var isBuffering = false
    var aspectRatio: Double?
    var loads: [URL] = []
    var positions: [Int64] = []
    var isPlaying = false
    var stopped = 0
    var errorMessage: String?
    func load(url: URL, at milliseconds: Int64) async throws { loads.append(url); positions.append(milliseconds) }
    func sync(positionMilliseconds: Int64, playing: Bool) { positions.append(positionMilliseconds); isPlaying = playing }
    func stop() { stopped += 1; isPlaying = false; errorMessage = nil }
}

private actor VideoFixture: VideoSource, PlaybackModeStore, PlaybackSource {
    nonisolated let displayName = "test"
    var mode: PlaybackMode?
    private var supported = true
    private var hold = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    private var requested: [String] = []
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) -> MediaResource {
        MediaResource(url: URL(string: "https://media.example/\(trackID).m4a")!, headers: [:], fileExtension: "m4a")
    }
    func captions(trackID: String) -> CaptionTimeline { CaptionTimeline(lines: []) }
    func supportsVideo(track: Track) -> Bool { supported }
    func setSupported(_ value: Bool) { supported = value }
    func video(trackID: String) async -> MediaResource {
        requested.append(trackID)
        if hold, trackID == "one" {
            await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        }
        return MediaResource(url: URL(string: "https://media.example/\(trackID).mp4")!, headers: [:], fileExtension: "mp4")
    }
    func block() { hold = true }
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil; hold = false }
    func calls() -> [String] { requested }
    func loadPlaybackMode() -> PlaybackMode? { mode }
    func savePlaybackMode(_ mode: PlaybackMode) { self.mode = mode }
}

private let videoTracks = ["one", "two"].map { Track(sourceID: "test", sourceTrackID: $0, title: $0, artist: "UP", durationMilliseconds: 90_000) }

@Test @MainActor func pausedVideoRefreshesBufferingAndErrorsWithoutAnAudioTick() async {
    let source = VideoFixture(), engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    model.updateTrack(videoTracks[0])
    engine.isBuffering = true
    model.watchCurrent()
    await model.waitUntilLoaded()
    #expect(model.isBuffering && !engine.isPlaying)
    engine.isBuffering = false
    engine.onUpdate?()
    #expect(!model.isBuffering)
    engine.errorMessage = "Video failed"
    engine.onUpdate?()
    #expect(model.errorMessage == "Video failed" && !model.isReady)
    await model.choose(.music)
    engine.isBuffering = true
    engine.onUpdate?()
    #expect(!model.isBuffering && model.errorMessage == nil)
}

@Test(arguments: [false, true]) @MainActor
func changingBrowseModeDoesNotOpenTheCurrentTrackOrLoadVideo(playing: Bool) async {
    let source = VideoFixture()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine, store: source)
    await model.choose(.video)
    #expect(model.mode == .video)
    #expect(await source.mode == .video)
    #expect(!model.isVisible && !model.isLoading)
    model.updateTrack(videoTracks[0])
    model.sync(positionMilliseconds: 42_000, playing: playing)
    await model.choose(.music)
    await model.choose(.video)
    #expect(model.presentationMode == .music && !model.isVisible && !model.isLoading)
    #expect(await source.calls().isEmpty && engine.loads.isEmpty)
    model.handleSelection(videoTracks[1], intent: .transport, positionMilliseconds: 0, playing: playing)
    model.handleSelection(videoTracks[0], intent: .automatic, positionMilliseconds: 42_000, playing: playing)
    #expect(!model.isVisible)
    #expect(await source.calls().isEmpty)
    model.watchCurrent()
    await model.waitUntilLoaded()
    #expect(model.presentationMode == .video && model.isVisible && model.isReady)
    #expect(await source.calls() == ["one"])
    #expect(engine.positions.first == 42_000 && engine.isPlaying == playing)
}

@Test @MainActor func restoredVideoPreferenceAndTrackStayPausedWithoutVideoRequests() async {
    let source = VideoFixture()
    await source.savePlaybackMode(.video)
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine, store: source)
    await model.restore()
    model.updateTrack(videoTracks[1])
    model.sync(positionMilliseconds: 42_000, playing: false)
    #expect(model.mode == .video)
    #expect(model.presentationMode == .music)
    #expect(!model.isVisible && !model.isLoading)
    #expect(await source.calls().isEmpty)
    #expect(engine.loads.isEmpty)
}

@Test @MainActor func explicitContentClickAppliesPreferenceIncludingSameTrack() async {
    let source = VideoFixture()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 42_000, playing: false)
    await model.waitUntilLoaded()
    #expect(model.presentationMode == .video && model.isVisible)
    #expect(engine.positions.first == 42_000)
    #expect(!engine.isPlaying)
    model.sync(positionMilliseconds: 45_000, playing: true)
    #expect(engine.isPlaying)
    #expect(engine.positions.last == 45_000)
    model.browse()
    #expect(!model.isVisible)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "one"])
    await model.choose(.music)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    #expect(model.presentationMode == .music && !model.isVisible)
    #expect(await source.calls() == ["one", "one"])
}

@Test(arguments: [PlaybackSelectionIntent.transport, .automatic]) @MainActor
func transportAndAutomaticAdvanceKeepExplicitlySelectedVideoMode(_ intent: PlaybackSelectionIntent) async {
    let source = VideoFixture()
    let model = VideoModel(source: source, engine: TestVideo())
    model.updateTrack(videoTracks[0])
    model.watchCurrent()
    await model.waitUntilLoaded()
    model.handleSelection(videoTracks[1], intent: intent, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.track == videoTracks[1])
    #expect(model.presentationMode == .video && model.isVisible)
    #expect(await source.calls() == ["one", "two"])
}

@Test @MainActor func videoQueueAdvanceKeepsPresentationAndBrowsingDoesNotReopen() async {
    let source = VideoFixture()
    let model = VideoModel(source: source, engine: TestVideo())
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    model.handleSelection(videoTracks[1], intent: .automatic, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "two"])
    model.browse()
    model.handleSelection(videoTracks[0], intent: .transport, positionMilliseconds: 0, playing: true)
    model.sync(positionMilliseconds: 10_000, playing: true)
    #expect(!model.isVisible)
    #expect(model.presentationMode == .video)
    #expect(await source.calls() == ["one", "two"])
    model.open()
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "two", "one"])
}

@Test @MainActor func leavingVideoModeRejectsLateResolution() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let old = Task { await model.waitUntilLoaded() }
    await Task.yield()
    await model.choose(.music)
    await source.release()
    await old.value
    #expect(engine.loads.isEmpty)
    #expect(!model.isReady && !model.isVisible)
    #expect(model.presentationMode == .music)
    #expect(model.errorMessage == nil)
}

@Test @MainActor func newContentRejectsOldVideoAndUnsupportedSourceCanReturnToMusic() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let old = Task { await model.waitUntilLoaded() }
    await Task.yield()
    model.handleSelection(videoTracks[1], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    await source.release()
    await old.value
    #expect(engine.loads.map(\.lastPathComponent) == ["two.mp4"])
    #expect(model.track == videoTracks[1])
    let unsupported = VideoModel(source: nil, engine: TestVideo())
    unsupported.updateTrack(videoTracks[0])
    unsupported.watchCurrent()
    #expect(unsupported.isVisible && !unsupported.isSupported && !unsupported.isLoading)
    await unsupported.choose(.music)
    #expect(!unsupported.isVisible && unsupported.presentationMode == .music)
    await unsupported.choose(.video)
    #expect(!unsupported.isVisible && !unsupported.isLoading)
}

@Test @MainActor func choosingVideoAgainDoesNotInterruptVideoAlreadyLoadingFromContentClick() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    #expect(model.isLoading)
    await model.choose(.video)
    await source.release()
    await model.waitUntilLoaded()
    #expect(model.isReady && !model.isLoading)
    #expect(engine.loads.count == 1)
}

@Test @MainActor func unsupportedCapabilityCanBeRetriedWithoutStuckLoadingTask() async {
    let source = VideoFixture()
    await source.setSupported(false)
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    model.updateTrack(videoTracks[0])
    model.watchCurrent()
    await model.waitUntilLoaded()
    #expect(!model.isSupported && !model.isLoading && model.isVisible)
    await source.setSupported(true)
    model.retry()
    await model.waitUntilLoaded()
    #expect(model.isReady && model.isSupported && engine.loads.count == 1)
}

private actor OrderedModeStore: PlaybackModeStore {
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    private(set) var saved: [PlaybackMode] = []
    func loadPlaybackMode() -> PlaybackMode? { saved.last }
    func savePlaybackMode(_ mode: PlaybackMode) async {
        if saved.isEmpty {
            await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        }
        saved.append(mode)
    }
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}

@Test @MainActor func slowPreferenceWritesDoNotDelayPictureAndLatestModeWinsOnDisk() async {
    let store = OrderedModeStore()
    let model = VideoModel(source: VideoFixture(), engine: TestVideo(), store: store)
    model.updateTrack(videoTracks[0])
    let first = Task { await model.choose(.video) }
    await store.waitBlocked()
    #expect(!model.isVisible)
    model.watchCurrent()
    await model.waitUntilLoaded()
    #expect(model.isVisible && model.isReady)
    let last = Task { await model.choose(.music) }
    while model.mode != .music { await Task.yield() }
    #expect(!model.isVisible && !model.isReady)
    await store.release()
    await first.value
    await last.value
    #expect(await store.saved == [.video, .music])
    #expect(model.mode == .music && model.persistenceMessage == nil)
}

@MainActor private final class ContinuousAudio: AudioEngine {
    var loads = 0
    var starts = 0
    var stops = 0
    var position: Int64 = 0
    var finished = false
    var actuallyPlaying = false
    var errorMessage: String?
    func load(url: URL, at milliseconds: Int64) { loads += 1; position = milliseconds }
    func play() { starts += 1; actuallyPlaying = true }
    func pause() { actuallyPlaying = false }
    func stop() { stops += 1; actuallyPlaying = false }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot {
        AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: 90_000, finished: finished, errorMessage: errorMessage)
    }
}

@Test @MainActor func repeatedModeSwitchesKeepAudioTrackTimeAndPauseStateWithoutRestarting() async {
    let source = VideoFixture()
    let audio = ContinuousAudio()
    let playback = PlaybackModel(source: source, engine: audio, download: { _ in URL(fileURLWithPath: "/tmp/mb-mode-test.m4a") })
    let picture = TestVideo()
    let video = VideoModel(source: source, engine: picture)
    playback.select(videoTracks, index: 0)
    await playback.waitUntilLoaded()
    video.handleSelection(playback.queue.current, intent: playback.selectionIntent, positionMilliseconds: 0, playing: true)
    let loads = audio.loads, starts = audio.starts, stops = audio.stops
    for index in 0..<12 {
        audio.position = Int64(42_000 + index * 1_000)
        playback.tick()
        video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
        video.watchCurrent()
        await video.waitUntilLoaded()
        #expect(video.isVisible && video.isReady && picture.isPlaying)
        #expect(picture.positions.last == playback.positionMilliseconds)
        await video.choose(.music)
        #expect(!video.isVisible && !video.isReady && !picture.isPlaying)
        #expect(playback.queue.current == videoTracks[0] && playback.state == .playing)
        #expect(audio.loads == loads && audio.starts == starts && audio.stops == stops)
    }
    playback.pause()
    playback.seek(to: 65_000)
    video.sync(positionMilliseconds: playback.positionMilliseconds, playing: false)
    video.watchCurrent()
    await video.waitUntilLoaded()
    #expect(picture.positions.last == 65_000 && !picture.isPlaying)
    #expect(playback.state == .paused && playback.positionMilliseconds == 65_000)
    #expect(audio.loads == loads && audio.starts == starts && audio.stops == stops)
}

private actor SlowModeStore: PlaybackModeStore {
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    func loadPlaybackMode() async -> PlaybackMode? {
        await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        return .video
    }
    func savePlaybackMode(_ mode: PlaybackMode) {}
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}

@Test @MainActor func lateRestoredPreferenceCannotOverrideExplicitMusicChoice() async {
    let store = SlowModeStore()
    let model = VideoModel(source: VideoFixture(), engine: TestVideo(), store: store)
    let restoring = Task { await model.restore() }
    await store.waitBlocked()
    await model.choose(.music)
    await store.release()
    await restoring.value
    #expect(model.mode == .music)
    #expect(!model.isVisible)
}

@Test @MainActor func closingVideoKeepsSavedPreferenceAndIgnoresLateLoadRetryAndPolling() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let video = VideoModel(source: source, engine: engine, store: source)
    await video.choose(.video)
    video.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let loading = Task { await video.waitUntilLoaded() }
    await Task.yield()
    video.browse()
    #expect(video.mode == .video)
    #expect(await source.mode == .video)
    #expect(!video.isVisible && !video.isLoading)
    await source.release()
    await loading.value
    #expect(engine.loads.isEmpty)
    await video.restore()
    video.updateTrack(videoTracks[0])
    for index in 0..<20 {
        video.sync(positionMilliseconds: Int64(index * 250), playing: index.isMultiple(of: 2))
    }
    video.retry()
    #expect(video.mode == .video)
    #expect(!video.isVisible && !video.isLoading && !video.isReady)
    #expect(await source.calls() == ["one"])
    #expect(engine.loads.isEmpty)
    video.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await video.waitUntilLoaded()
    #expect(video.isVisible && video.isReady)
    #expect(await source.calls() == ["one", "one"])
    #expect(engine.loads.count == 1)
}

private actor RestoredSession: PlaybackSessionStore {
    func loadSession() -> PlaybackSession? {
        PlaybackSession(queue: videoTracks, index: 0, positionMilliseconds: 42_000)
    }
    func saveSession(_ session: PlaybackSession) {}
}

@Test @MainActor func sessionResumePauseAndAudioRetryNeverEmitContentSelectionOrReopenVideo() async {
    let source = VideoFixture()
    await source.savePlaybackMode(.video)
    let engine = ContinuousAudio()
    let playback = PlaybackModel(source: source, engine: engine, store: RestoredSession(), download: { _ in
        FileManager.default.temporaryDirectory.appendingPathComponent("mb-resume-test-\(UUID()).m4a")
    })
    let video = VideoModel(source: source, engine: TestVideo(), store: source)
    await video.restore()
    await playback.restore()
    video.updateTrack(playback.queue.current)
    let revision = playback.selectionRevision
    #expect(revision == 0 && playback.state == .paused)
    playback.play()
    await playback.waitUntilLoaded()
    video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
    playback.pause()
    video.sync(positionMilliseconds: playback.positionMilliseconds, playing: false)
    playback.play()
    #expect(engine.loads == 1)
    engine.errorMessage = "test interrupted stream"
    playback.tick()
    #expect(playback.state == .failed)
    engine.errorMessage = nil
    playback.play()
    await playback.waitUntilLoaded()
    for _ in 0..<20 {
        playback.tick()
        video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
    }
    #expect(engine.loads == 2)
    #expect(playback.state == .playing && playback.positionMilliseconds == 42_000)
    #expect(playback.selectionRevision == revision)
    #expect(video.mode == .video && !video.isVisible && !video.isLoading)
    #expect(await source.calls().isEmpty)
}

@Test @MainActor func musicPreloadsMutedVideoAndOpeningReusesItAndMeasuredSizeWins() async {
    let source = VideoFixture(), engine = TestVideo()
    engine.aspectRatio = 9.0 / 16
    let model = VideoModel(source: source, engine: engine)
    model.updateTrack(videoTracks[0])
    model.sync(positionMilliseconds: 1200, playing: true)
    model.preloadCurrent()
    await model.waitUntilLoaded()
    #expect(model.isReady && !model.isVisible && model.aspectRatio == 9.0 / 16)
    model.sync(positionMilliseconds: 45_000, playing: true)
    #expect(engine.loads.count == 1 && !engine.isPlaying && engine.positions == [1_200])
    model.watchCurrent()
    #expect(model.isVisible && model.isReady && engine.loads.count == 1)
    #expect(engine.isPlaying && engine.positions.last == 45_000)
    model.updateTrack(videoTracks[1])
    #expect(!model.isReady)
    await model.waitUntilLoaded()
    #expect(engine.loads.count == 2)
}

@Test @MainActor func backgroundReleasesHiddenVideoButCanPrepareOnReturn() async {
    let source = VideoFixture(), engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    model.updateTrack(videoTracks[0])
    model.sync(positionMilliseconds: 1_200, playing: true)
    model.preloadCurrent()
    await model.waitUntilLoaded()
    let stopped = engine.stopped
    model.suspendPreloading()
    #expect(!model.isReady && engine.stopped == stopped + 1)
    model.preloadCurrent()
    await model.waitUntilLoaded()
    #expect(model.isReady && engine.loads.count == 2 && !engine.isPlaying)
}

@Test @MainActor func carAudioCancelsVideoBlocksNewRequestsAndPreservesModePreference() async {
    let source = VideoFixture(), engine = TestVideo()
    let model = VideoModel(source: source, engine: engine, store: source)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.isVisible && engine.isPlaying)
    model.setAudioOnly(true)
    #expect(!model.isVisible && !model.isReady && !engine.isPlaying)
    model.handleSelection(videoTracks[1], intent: .content, positionMilliseconds: 15_000, playing: true)
    model.preloadCurrent()
    model.watchCurrent()
    model.preloadNext(videoTracks[0], enabled: true)
    await model.waitUntilLoaded()
    #expect(model.presentationMode == .music && model.mode == .video && !model.isLoading)
    #expect(await source.calls() == ["one"])
    model.setAudioOnly(false)
    #expect(!model.isVisible)
    #expect(await source.calls() == ["one"])
    model.watchCurrent()
    await model.waitUntilLoaded()
    #expect(model.isVisible && engine.isPlaying && engine.positions.last == 15_000)
    #expect(await source.calls() == ["one", "two"])
}

@Test @MainActor func nativeVideoMeasuresPortraitTrackAndClearsGeometryOnStop() async throws {
    let file = try #require(Bundle.module.url(forResource: "Fixtures/video-portrait", withExtension: "mp4"))
    let engine = NativeVideoEngine()
    try await engine.load(resource: MediaResource(url: file, headers: [:], fileExtension: "mp4"), at: 0)
    #expect(abs((engine.aspectRatio ?? 0) - 9.0 / 16) < 0.001)
    engine.stop()
    #expect(engine.aspectRatio == nil)
}

@Test @MainActor func floatingVideoReusesTheLoadedEngineAndKeepsTransportSelection() async {
    let source = VideoFixture(), engine = TestVideo(), model = VideoModel(source: nil)
    #expect(!model.isFloating)
    let video = VideoModel(source: source, engine: engine)
    video.updateTrack(videoTracks[0]); video.sync(positionMilliseconds: 12_000, playing: true)
    video.watchCurrent(); await video.waitUntilLoaded()
    let loaded = engine.loads.count, stopped = engine.stopped
    video.minimize()
    #expect(video.isFloating && !video.isVisible && video.isReady)
    video.sync(positionMilliseconds: 13_000, playing: true)
    video.open()
    #expect(!video.isFloating && video.isVisible)
    #expect(engine.loads.count == loaded && engine.stopped == stopped && engine.positions.last == 13_000)
    video.minimize(); video.handleSelection(videoTracks[1], intent: .transport, positionMilliseconds: 0, playing: true)
    await video.waitUntilLoaded()
    #expect(video.isFloating && video.track == videoTracks[1] && engine.loads.count == loaded + 1)
    video.browse()
    #expect(!video.isFloating && !video.isReady && !video.isVisible)
}

@Test @MainActor func systemVideoControlsAreNotOverwrittenByPeriodicAudioTicks() async {
    let source = VideoFixture(), engine = TestVideo(), video = VideoModel(source: source, engine: engine)
    video.updateTrack(videoTracks[0]); video.watchCurrent(); await video.waitUntilLoaded()
    video.setSystemControlled(true)
    let count = engine.positions.count
    video.sync(positionMilliseconds: 10_000, playing: true)
    #expect(engine.positions.count == count)
    video.applySystemPlayback(positionMilliseconds: 20_000, playing: false)
    #expect(engine.positions.last == 20_000 && !engine.isPlaying)
    video.setSystemControlled(false)
    video.sync(positionMilliseconds: 21_000, playing: true)
    #expect(engine.positions.last == 21_000 && engine.isPlaying)
}

@Test @MainActor func visibleVideoLoadingFreezesTheAudioClockAndRejectsALateFinishedSnapshot() async {
    let source = VideoFixture(), audio = ContinuousAudio(), picture = TestVideo()
    let playback = PlaybackModel(source: source, engine: audio, download: { _ in URL(fileURLWithPath: "/tmp/mb-video-hold.m4a") })
    let video = VideoModel(source: source, engine: picture)
    video.onPlaybackHoldChanged = { [weak playback] in playback?.holdForVideo($0) }
    await source.block()
    await video.choose(.video)
    playback.select(videoTracks, index: 0)
    video.handleSelection(playback.queue.current, intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    await playback.waitUntilLoaded()
    #expect(video.isLoading && !audio.actuallyPlaying)
    // 音频结束消息可能已在途，缓冲闸门仍必须阻止自动换片。
    audio.position = 90_000; audio.finished = true
    playback.tick()
    #expect(playback.queue.index == 0 && playback.positionMilliseconds == 0)
    audio.position = 0; audio.finished = false
    picture.isBuffering = true
    await source.release(); await video.waitUntilLoaded()
    #expect(video.isBuffering && !audio.actuallyPlaying)
    picture.isBuffering = false; picture.onUpdate?()
    #expect(audio.actuallyPlaying)
    picture.isBuffering = true; picture.onUpdate?()
    #expect(!audio.actuallyPlaying)
    playback.pause()
    let starts = audio.starts
    picture.isBuffering = false; picture.onUpdate?()
    #expect(!audio.actuallyPlaying && audio.starts == starts && playback.state == .paused)
    video.browse()
    #expect(!audio.actuallyPlaying)
}
