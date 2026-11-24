import Foundation
@testable import MBFoundation
import Testing

@Test @MainActor func preferencesDeduplicateSearchAndPersistLatestRapidChanges() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AppPersistence(directory: directory)
    let media = MediaLibrary(directory: directory.appendingPathComponent("media"))
    let model = PreferencesModel(store: store, media: media)
    await model.restore()
    for index in 0..<30 { model.recordSearch("\(index)") }
    model.recordSearch("  20 \n")
    model.recordSearch(" \n ")
    model.values.volume = 0.27
    model.values.appearance = .dark
    model.values.cacheMegabytes = 0
    model.values.playerStyle = .vinyl
    model.values.lyricStyle = .tilt
    model.values.normalization = .dynamic
    model.values.language = .english
    model.values.quality = .lossless
    model.values.hotComments = true; model.values.fog = true; model.values.smoke = true
    model.values.growFromCover = true; model.values.stageSparks = true; model.values.lyricsStage = true
    model.values.nowPlayingLyrics = false
    model.values.selectedSourceID = "fixture"
    await model.flush()
    let restored = PreferencesModel(store: AppPersistence(directory: directory), media: media)
    await restored.restore()
    #expect(restored.values.searchHistory.count == 20)
    #expect(restored.values.searchHistory[0] == "20")
    #expect(restored.values.searchHistory.filter { $0 == "20" }.count == 1)
    #expect(restored.values.volume == 0.27)
    #expect(restored.values.appearance == .dark)
    #expect(restored.values.cacheMegabytes == 0)
    #expect(restored.values.playerStyle == .vinyl)
    #expect(restored.values.lyricStyle == .tilt)
    #expect(restored.values.normalization == .dynamic)
    #expect(restored.values.language == .english)
    #expect(restored.values.quality == .lossless)
    #expect(restored.values.hotComments && restored.values.fog && restored.values.smoke)
    #expect(restored.values.growFromCover && restored.values.stageSparks && restored.values.lyricsStage)
    #expect(restored.values.selectedSourceID == "fixture")
    #expect(!restored.values.nowPlayingLyrics)
}

@Test @MainActor func preferencesDoNotOverwriteSavedValuesWhileStartupIsStillRestoring() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AppPersistence(directory: directory)
    var saved = AppPreferences()
    saved.selectedSourceID = "source-fixture"; saved.searchHistory = ["saved term"]
    saved.quality = .lossless; saved.fog = true
    try await store.savePreferences(saved)
    let model = PreferencesModel(store: store, media: MediaLibrary(directory: directory.appendingPathComponent("media")))
    model.values.selectedSourceID = "temporary-startup-source"
    await model.flush()
    #expect(try await store.loadPreferences() == saved)
    await model.restore()
    #expect(model.isRestored)
    #expect(model.values == saved)
    model.values.volume = 0.42; await model.flush()
    #expect(try await store.loadPreferences()?.volume == 0.42)
}

@Test func olderPreferencesEnableNowPlayingLyricsAndPreserveExplicitOptOut() throws {
    let older = try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"volume":0.4}"#.utf8))
    #expect(older.nowPlayingLyrics)
    #expect(older.volume == 0.4)
    var disabled = older
    disabled.nowPlayingLyrics = false
    let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(disabled))
    #expect(!restored.nowPlayingLyrics)
}

@Test func sourceSwitchPausePreferenceMigratesAndPersistsWithoutLosingSavedSettings() throws {
    let old = try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"volume":0.27,"selectedSourceID":"saved"}"#.utf8))
    #expect(!old.pauseOtherSourcesOnSwitch)
    var changed = old; changed.pauseOtherSourcesOnSwitch = true
    let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(changed))
    #expect(restored == changed)
}

@MainActor private final class EventAudio: AudioEngine, AudioEngineEvents, AudioEngineVolume {
    var onUpdate: (() -> Void)?
    var volume: Double = 0
    var finished = false
    func load(url: URL, at milliseconds: Int64) { finished = false }
    func play() {}
    func pause() {}
    func stop() { finished = false }
    func seek(to milliseconds: Int64) {}
    func snapshot() -> AudioEngineSnapshot { AudioEngineSnapshot(positionMilliseconds: 1_000, durationMilliseconds: 1_000, finished: finished) }
    func setVolume(_ volume: Double) { self.volume = volume }
}

@Test @MainActor func engineEventAdvancesQueueWithoutForegroundViewTicksAndAppliesVolume() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent("fixture.m4a")
    try Data([0, 1, 2]).write(to: file)
    let media = MediaLibrary(directory: directory.appendingPathComponent("media"))
    let tracks = ["one", "two"].map { id in
        var track = Track(sourceID: "local", sourceTrackID: id, title: id, artist: "test", durationMilliseconds: 1_000)
        track.localFileID = id; track.localMetadata = LocalAudioMetadata(lyrics: "[00:00.00]Hello"); return track
    }
    for track in tracks { try await media.saveDownload(file: file, track: track) }
    let engine = EventAudio()
    let model = PlaybackModel(source: nil, engine: engine, mediaLibrary: media)
    model.setVolume(0.27)
    model.select(tracks, index: 0)
    await model.waitUntilLoaded()
    #expect(engine.volume == 0.27)
    #expect(model.timeline.lines.first?.text == "Hello")
    var updated = tracks[0]
    updated.localMetadata?.lyrics = "[00:00.00]Updated"
    model.updateLocalTrack(updated)
    #expect(model.timeline.lines.first?.text == "Updated")
    #expect(model.queue.index == 0)
    engine.finished = true
    engine.onUpdate?()
    await model.waitUntilLoaded()
    #expect(model.queue.index == 1)
    #expect(model.selectionIntent == .automatic)
    model.removeQueue(IndexSet(integersIn: 0..<2))
}

private actor StreamingSource: PlaybackSource {
    nonisolated let displayName = "test"
    var downloads = 0
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) -> MediaResource { MediaResource(url: URL(string: "https://media.example/audio.m4a")!, headers: ["Referer": "https://example.test"], fileExtension: "m4a") }
    func captions(trackID: String) -> CaptionTimeline { CaptionTimeline(lines: []) }
    func download(_ resource: MediaResource) -> URL { downloads += 1; return FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a") }
}

@MainActor private final class StreamingEngine: AudioEngine, AudioEngineStreaming {
    var rejectRange = false
    var remote: MediaResource?
    var local: URL?
    func load(resource: MediaResource, at milliseconds: Int64) throws {
        remote = resource
        if rejectRange { throw MediaStreamingFailure.rangeStatus(200) }
    }
    func load(url: URL, at milliseconds: Int64) { local = url }
    func play() {}
    func pause() {}
    func stop() {}
    func seek(to milliseconds: Int64) {}
    func snapshot() -> AudioEngineSnapshot { AudioEngineSnapshot(positionMilliseconds: 0, durationMilliseconds: 1_000) }
}

@Test @MainActor func rangeAudioStartsWithoutDownloadingWholeFileAndKeepsRequiredHeaders() async {
    let source = StreamingSource(), engine = StreamingEngine()
    let model = PlaybackModel(source: source, engine: engine, download: { await source.download($0) })
    model.select([Track(sourceID: "test", sourceTrackID: "one", title: "one", artist: "test")], index: 0)
    await model.waitUntilLoaded()
    #expect(model.state == .playing)
    #expect(engine.remote?.headers["Referer"] == "https://example.test")
    #expect(engine.local == nil)
    #expect(await source.downloads == 0)
}

@Test @MainActor func unsupportedRangeFallsBackToDownloadAndStillPlays() async {
    let source = StreamingSource(), engine = StreamingEngine()
    engine.rejectRange = true
    let model = PlaybackModel(source: source, engine: engine, download: { await source.download($0) })
    model.select([Track(sourceID: "test", sourceTrackID: "one", title: "one", artist: "test")], index: 0)
    await model.waitUntilLoaded()
    #expect(model.state == .playing)
    #expect(engine.local != nil)
    #expect(await source.downloads == 1)
}

@Test @MainActor func automaticUpdateOptOutSurvivesRestartWithoutLosingExistingPreferences() async throws {
    let old = try JSONDecoder().decode(AppPreferences.self, from: Data("{\"volume\":0.27,\"searchHistory\":[\"saved\"]}".utf8))
    #expect(old.automaticUpdateChecks)
    var disabled = old; disabled.automaticUpdateChecks = false
    let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(disabled))
    #expect(!restored.automaticUpdateChecks && restored.volume == 0.27 && restored.searchHistory == ["saved"])
}

@Test func backgroundOpacityMigratesClampsAndPersistsAlongsideExistingSettings() throws {
    let old = try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"volume":0.27}"#.utf8))
    #expect(old.backgroundOpacity == nil)
    var changed = old; changed.backgroundOpacity = 0.65
    let restored = try JSONDecoder().decode(AppPreferences.self, from: JSONEncoder().encode(changed))
    #expect(restored.backgroundOpacity == 0.65 && restored.volume == 0.27)
    #expect(try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"backgroundOpacity":2}"#.utf8)).backgroundOpacity == 1)
    #expect(try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"backgroundOpacity":-1}"#.utf8)).backgroundOpacity == 0)
}
