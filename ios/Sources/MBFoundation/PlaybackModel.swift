import Foundation
import Observation

public enum PlaybackState: Sendable { case paused, loading, playing, failed }
public enum PlaybackSelectionIntent: Sendable { case content, transport, automatic }

/// 恢复只选中曲目和进度；用户播放时重新解析地址。失败重试、切曲和取消使用同一代次隔离。
@MainActor @Observable
public final class PlaybackModel {
    private struct TrackKey: Hashable {
        let sourceID: String
        let trackID: String
        init(_ track: Track) { sourceID = track.sourceID; trackID = track.sourceTrackID }
    }

    public let queue = PlaybackQueue()
    private var trackProblems: [TrackKey: TrackProblem] = [:]
    public private(set) var selectionRevision = 0
    public private(set) var selectionIntent: PlaybackSelectionIntent = .content
    public private(set) var state: PlaybackState = .paused
    public private(set) var audioBuffering = false
    @ObservationIgnored public private(set) var positionMilliseconds: Int64 {
        get { access(keyPath: \.positionMilliseconds); return clockPosition }
        set {
            guard clockPosition != newValue else { return }
            if progressObservationEnabled { withMutation(keyPath: \.positionMilliseconds) { clockPosition = newValue } }
            else { clockPosition = newValue }
        }
    }
    @ObservationIgnored public private(set) var bassLevel: Double {
        get { access(keyPath: \.bassLevel); return clockBass }
        set {
            guard clockBass != newValue else { return }
            if progressObservationEnabled { withMutation(keyPath: \.bassLevel) { clockBass = newValue } }
            else { clockBass = newValue }
        }
    }
    public private(set) var durationMilliseconds: Int64 = 0
    public private(set) var timeline = CaptionTimeline(lines: [])
    public private(set) var errorMessage: String?
    public private(set) var captionMessage: String?
    public private(set) var persistenceMessage: String?
    public private(set) var controlRevision = 0
    public private(set) var station: PlaybackStation?
    @ObservationIgnored public var stateDidChange: (() -> Void)?
    @ObservationIgnored private var clockPosition: Int64 = 0
    @ObservationIgnored private var clockBass = 0.0
    @ObservationIgnored private var progressObservationEnabled = true
    @ObservationIgnored private let source: (any PlaybackSource)?
    @ObservationIgnored private let engine: any AudioEngine
    @ObservationIgnored private let preloader: NextAudioPreloader
    @ObservationIgnored private let store: (any PlaybackSessionStore)?
    @ObservationIgnored private let mediaLibrary: MediaLibrary?
    @ObservationIgnored private let download: @Sendable (MediaResource) async throws -> URL
    @ObservationIgnored private var loadingTask: Task<Void, Never>?
    @ObservationIgnored private var captionTask: Task<Void, Never>?
    @ObservationIgnored private var cacheTask: Task<Void, Never>?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var ready = false
    @ObservationIgnored private var wantsPlay = false
    @ObservationIgnored private var videoHold = false
    @ObservationIgnored private var refreshUsed = false
    @ObservationIgnored private var localFile: URL?
    @ObservationIgnored private var savedPosition: Int64 = -1
    @ObservationIgnored private var saveRevision = 0
    @ObservationIgnored private var restored = false
    @ObservationIgnored private var stationTask: Task<Void, Never>?
    @ObservationIgnored private var stationRevision = 0
    @ObservationIgnored private var stationAdvance = false
    private var pageLoader: PlaybackPageLoader?
    private var pageTask: Task<Void, Never>?
    public var nextQueuePageID: UUID? { pageLoader?.id }
    public var hasNextQueuePage: Bool { pageLoader != nil }
    public var isLoadingQueuePage: Bool { pageTask != nil }
    public private(set) var queuePageFailed = false
    @ObservationIgnored private var pageRevision = 0
    @ObservationIgnored private var pageAdvance: PlaybackSelectionIntent?

    public init(source: (any PlaybackSource)?, engine: any AudioEngine, store: (any PlaybackSessionStore)? = nil,
                mediaLibrary: MediaLibrary? = nil,
                download: @escaping @Sendable (MediaResource) async throws -> URL = { try await NetworkClient.shared.download($0, maximumBytes: 256 << 20) }) {
        self.source = source
        self.engine = engine
        preloader = NextAudioPreloader(source: source, engine: engine)
        self.store = store
        self.download = download
        self.mediaLibrary = mediaLibrary
        if let events = engine as? any AudioEngineEvents { events.onUpdate = { [weak self] in self?.tick() } }
    }

    public func restore() async {
        defer { stateDidChange?() }
        guard !restored else { return }
        restored = true
        let current = generation
        do {
            guard let session = try await store?.loadSession(), current == generation, queue.current == nil,
                  session.queue.indices.contains(session.index) else { return }
            queue.replace(with: session.queue, selecting: session.index)
            station = session.station
            queue.restorePreferences(shuffled: session.shuffled ?? false, repeatMode: session.repeatMode ?? .off, order: session.playOrder)
            durationMilliseconds = queue.current?.durationMilliseconds ?? 0
            positionMilliseconds = durationMilliseconds > 0 ? max(0, min(session.positionMilliseconds, durationMilliseconds - 1)) : max(0, session.positionMilliseconds)
            timeline = CaptionTimeline(lines: session.captions ?? [])
            state = .paused
        } catch { persistenceMessage = "播放记录读取失败" }
    }

    public func select(_ tracks: [Track], index: Int, intent: PlaybackSelectionIntent = .content, autoplay: Bool = true) {
        guard tracks.indices.contains(index) else { return }
        endStation()
        endPages()
        queue.replace(with: tracks, selecting: index)
        jump(to: index, intent: intent, autoplay: autoplay)
    }

    public func jump(to index: Int, intent: PlaybackSelectionIntent = .content, autoplay: Bool = true) {
        guard queue.tracks.indices.contains(index) else { return }
        stationAdvance = false
        pageAdvance = nil
        defer { stateDidChange?() }
        let prepared = autoplay ? preloader.take(for: queue.tracks[index]) : nil
        reset()
        selectionIntent = intent
        selectionRevision += 1
        queue.select(index: index)
        durationMilliseconds = queue.current?.durationMilliseconds ?? 0
        persistSoon()
        if autoplay { play(prepared: prepared) }
        refillPages()
    }

    public func next() {
        if station != nil, queue.index == queue.tracks.indices.last { refillStation(advance: true); return }
        if pageLoader != nil, pageTail { refillPages(advance: .transport); return }
        if let index = queue.nextIndex(automatic: false) { jump(to: index, intent: .transport) }
    }
    public func previous() {
        if let index = queue.previousIndex() { jump(to: index, intent: .transport) }
    }

    /// 预览复用真实队列顺序，不选曲、不启动解码。
    public func neighboringTrack(forward: Bool) -> Track? {
        if forward && (station != nil && queue.index == queue.tracks.indices.last || pageLoader != nil && pageTail) { return nil }
        let index = forward ? queue.nextIndex(automatic: false) : queue.previousIndex()
        return index.flatMap { queue.tracks.indices.contains($0) ? queue.tracks[$0] : nil }
    }

    public func didSwitchSource(to id: String, pauseOtherSources: Bool) {
        if pauseOtherSources, let current = queue.current, current.sourceID != id { pause() }
    }

    public func startStation(_ station: PlaybackStation, tracks: [Track], index: Int) {
        select(tracks, index: index)
        self.station = station
        persistSoon()
        refillStation()
    }
    public func endStation() {
        stationRevision += 1; stationTask?.cancel(); stationTask = nil; stationAdvance = false; station = nil
        persistSoon()
    }

    public func attachPages(_ loader: PlaybackPageLoader) {
        guard queue.current != nil, station == nil else { return }
        endPages()
        pageLoader = loader
        refillPages()
        stateDidChange?()
    }

    private func endPages() {
        pageRevision += 1; pageTask?.cancel(); pageTask = nil; pageLoader = nil; pageAdvance = nil; queuePageFailed = false
    }

    private var pageTail: Bool { queue.index != nil && queue.index == queue.playOrder.last }

    public func loadNextQueuePage() { refillPages(requested: true) }

    private func refillPages(advance: PlaybackSelectionIntent? = nil, requested: Bool = false) {
        guard let loader = pageLoader, let index = queue.index,
              let position = queue.playOrder.firstIndex(of: index) else { return }
        if let advance { pageAdvance = advance }
        guard pageTask == nil, requested || advance != nil || (!queuePageFailed && queue.playOrder.count - position <= 2) else { return }
        queuePageFailed = false
        let revision = pageRevision
        pageTask = Task {
            var cursor: PlaybackPageLoader? = loader
            do {
                for _ in 0..<8 {
                    guard let load = cursor else { break }
                    let batch = try await load.fetch()
                    guard revision == pageRevision, !Task.isCancelled else { return }
                    cursor = batch.next
                    var seen = Set(queue.tracks.map(\.id))
                    let fresh = batch.tracks.filter { seen.insert($0.id).inserted }
                    for track in fresh { queue.append(track) }
                    if !fresh.isEmpty { break }
                }
                if pageTail { cursor = nil }
                pageLoader = cursor
                pageTask = nil
                let advance = pageAdvance; pageAdvance = nil
                if let advance, let index = queue.nextIndex(automatic: advance == .automatic), cursor == nil || !pageTail {
                    jump(to: index, intent: advance)
                } else if advance == .automatic, cursor == nil {
                    engine.stop(); ready = false; removeFile()
                }
                persistSoon(); stateDidChange?()
            } catch {
                guard revision == pageRevision, !Task.isCancelled else { return }
                pageTask = nil; pageLoader = cursor
                queuePageFailed = true
                if pageAdvance != nil { errorMessage = error.localizedDescription; stateDidChange?() }
                pageAdvance = nil
            }
        }
    }
    private func refillStation(advance: Bool = false) {
        guard let station, let provider = source as? any StationPlaybackSource else { return }
        stationAdvance = stationAdvance || advance
        guard stationTask == nil, advance || queue.tracks.count - (queue.index ?? 0) <= 2 else { return }
        let revision = stationRevision
        stationTask = Task {
            do {
                var tracks: [Track] = []
                for _ in 0..<3 where tracks.isEmpty { tracks = try await provider.stationNext(station).filter { $0.unavailable != true }; try Task.checkCancellation() }
                guard revision == stationRevision, !Task.isCancelled else { return }
                stationTask = nil
                guard !tracks.isEmpty else { stationAdvance = false; errorMessage = "电台暂无可播放曲目"; stateDidChange?(); return }
                let next = queue.tracks.count
                for track in tracks { queue.append(track) }
                let advance = stationAdvance; stationAdvance = false
                if advance { jump(to: next, intent: .automatic) }
                persistSoon(); stateDidChange?()
            } catch {
                guard revision == stationRevision, !Task.isCancelled else { return }
                stationTask = nil; stationAdvance = false
                errorMessage = AppLocalization.text("电台加载失败：%@", arguments: [error.localizedDescription]); stateDidChange?()
            }
        }
    }

    public func toggleShuffle() { preloader.cancel(); queue.toggleShuffle(); persistSoon(); stateDidChange?() }
    public func setVolume(_ volume: Double) { (engine as? any AudioEngineVolume)?.setVolume(volume) }
    public func setNormalization(_ mode: AudioNormalization) { (engine as? any AudioEngineNormalization)?.setNormalization(mode) }
    public func updateLocalTrack(_ track: Track) {
        guard track.localFileID != nil, queue.update(track) else { return }
        if queue.current?.id == track.id {
            timeline = .lrc(track.localMetadata?.lyrics ?? "", durationMilliseconds: track.durationMilliseconds)
            captionMessage = timeline.lines.isEmpty ? "本地文件没有带时间的歌词" : nil
        }
        persistSoon(); stateDidChange?()
    }
    public func cycleRepeat() { preloader.cancel(); queue.cycleRepeat(); persistSoon(); stateDidChange?() }
    public func enqueue(_ track: Track, next: Bool = false) {
        let empty = queue.current == nil
        preloader.cancel()
        queue.append(track, next: next)
        if empty { durationMilliseconds = track.durationMilliseconds }
        persistSoon(); stateDidChange?()
    }
    public func moveQueue(_ offsets: IndexSet, to destination: Int) { preloader.cancel(); queue.move(offsets, to: destination); persistSoon(); stateDidChange?() }
    /// 清除播放会话并取消所有旧请求，切换音源后迟到的恢复与加载不能重新选中旧曲目。
    public func clear() {
        restored = true
        queue.remove(IndexSet(queue.tracks.indices))
        endStation()
        endPages()
        reset()
        durationMilliseconds = 0
        controlRevision += 1
        selectionRevision += 1
        persistSoon()
        stateDidChange?()
    }
    public func removeQueue(_ offsets: IndexSet) {
        preloader.cancel()
        let removedCurrent = queue.remove(offsets)
        if queue.tracks.isEmpty { clear(); return }
        else if removedCurrent, let index = queue.index { jump(to: index, intent: .transport) }
        persistSoon(); stateDidChange?()
    }

    public func toggle() {
        if state == .playing || (state == .loading && wantsPlay) { pause() }
        else { play() }
    }

    public func trackProblem(for track: Track) -> TrackProblem? {
        trackProblems[TrackKey(track)] ?? track.declaredProblem
    }

    /// 音质变更后，下首不能继续复用旧音质的地址与首块。
    public func invalidatePreparedAudio() { preloader.cancel() }
    public func play() { play(prepared: nil) }
    private func play(prepared: PreparedPlaybackAudio?) {
        defer { stateDidChange?() }
        controlRevision += 1
        guard let track = queue.current else { return }
        guard track.unavailable != true else { fail(track.declaredProblem?.label ?? TrackProblem.unavailable.label); return }
        guard source != nil || mediaLibrary != nil else { errorMessage = "本地示例没有音频"; return }
        if state == .failed { refreshUsed = false }
        wantsPlay = true
        errorMessage = nil
        if ready {
            do { if !videoHold { try engine.play() }; state = .playing }
            catch { failTrack(track, error: error) }
            return
        }
        guard loadingTask == nil else { state = .loading; return }
        state = .loading
        generation += 1
        let current = generation
        let start = positionMilliseconds
        captionTask?.cancel()
        captionTask = Task {
            defer { if current == generation, !Task.isCancelled { stateDidChange?() } }
            if track.localFileID != nil {
                timeline = .lrc(track.localMetadata?.lyrics ?? "", durationMilliseconds: track.durationMilliseconds)
                captionMessage = timeline.lines.isEmpty ? "本地文件没有带时间的歌词" : nil
                return
            }
            guard let source else { return }
            let result = await Self.loadCaptions(source, track: track)
            guard current == generation, !Task.isCancelled else { return }
            if result.1 == nil { timeline = result.0; persistSoon() }
            captionMessage = result.1
        }
        loadingTask = Task {
            defer { prepared?.cancel(); stateDidChange?() }
            do {
                var file: URL?
                var streamingResource: MediaResource?
                if let cached = try await mediaLibrary?.playbackFile(for: track) { file = cached }
                else {
                    guard track.localFileID == nil, let source else { throw CocoaError(.fileNoSuchFile) }
                    var resource: MediaResource
                    if let prepared { resource = prepared.resource }
                    else { resource = try await source.audio(track: track) }
                    try Task.checkCancellation()
                    if let streaming = engine as? any AudioEngineStreaming {
                        do {
                            if let prepared, let preloading = engine as? any AudioEnginePreloading {
                                do { try await preloading.load(prepared: prepared.audio, at: start) }
                                catch {
                                    try Task.checkCancellation()
                                    guard current == generation else { return }
                                    engine.stop(); prepared.cancel()
                                    resource = try await source.audio(track: track)
                                    try Task.checkCancellation()
                                    try await streaming.load(resource: resource, at: start)
                                }
                            } else { try await streaming.load(resource: resource, at: start) }
                            streamingResource = resource
                        } catch {
                            try Task.checkCancellation()
                            guard current == generation else { return }
                            guard MediaStreamingFailure.allowsWholeFile(error) else { throw error }
                            engine.stop()
                            file = try await download(resource)
                        }
                    } else { file = try await download(resource) }
                }
                guard current == generation, !Task.isCancelled else {
                    if let file { try? FileManager.default.removeItem(at: file) }
                    return
                }
                if let file {
                    localFile = file
                    try await engine.load(url: file, at: start)
                    if track.localFileID == nil { try? await mediaLibrary?.cache(file: file, track: track) }
                }
                guard current == generation, !Task.isCancelled else { return }
                // 加载时拖动进度也要保留，不能被开始下载时的旧位置盖回去。
                if positionMilliseconds != start { engine.seek(to: positionMilliseconds) }
                ready = true
                refillStation()
                loadingTask = nil
                if wantsPlay { if !videoHold { try engine.play() }; state = .playing }
                else { state = .paused }
                if trackProblems[TrackKey(track)] != nil { trackProblems[TrackKey(track)] = nil }
                if let resource = streamingResource, let mediaLibrary, await mediaLibrary.cacheEnabled(), current == generation, !Task.isCancelled {
                    cacheTask = Task {
                        do {
                            try await Task.sleep(for: .seconds(8))
                            guard current == generation, wantsPlay, !videoHold, !engine.snapshot().buffering else { return }
                            let cached = try await download(resource)
                            defer { try? FileManager.default.removeItem(at: cached) }
                            try Task.checkCancellation()
                            try await mediaLibrary.cache(file: cached, track: track)
                        } catch { /* 缓存失败不打断已经开始的分段播放。 */ }
                    }
                }
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                loadingTask = nil
                if MediaStreamingFailure.allowsRefresh(error), recoverAudio() { return }
                failTrack(track, error: error)
            }
        }
    }

    public func holdForVideo(_ hold: Bool) {
        guard videoHold != hold else { return }
        videoHold = hold
        if hold { preloader.cancel() }
        guard ready else { return }
        if hold { engine.pause(); cacheTask?.cancel() }
        else if wantsPlay {
            do { try engine.play() }
            catch { if let track = queue.current { failTrack(track, error: error) } }
        }
    }

    private func recoverAudio() -> Bool {
        guard !refreshUsed, wantsPlay else { return false }
        refreshUsed = true
        cacheTask?.cancel(); cacheTask = nil
        engine.stop(); ready = false
        removeFile()
        play()
        return true
    }

    public func pause() {
        defer { stateDidChange?() }
        controlRevision += 1
        stationAdvance = false
        pageAdvance = nil
        wantsPlay = false
        preloader.cancel()
        if ready {
            let snapshot = engine.snapshot()
            bassLevel = snapshot.bassLevel
            positionMilliseconds = max(0, snapshot.positionMilliseconds)
        }
        engine.pause()
        state = .paused
        persistSoon()
    }

    public func seek(to milliseconds: Int64) {
        defer { stateDidChange?() }
        controlRevision += 1
        cacheTask?.cancel()
        positionMilliseconds = max(0, min(milliseconds, max(0, durationMilliseconds - 1)))
        if durationMilliseconds == 0 || Double(positionMilliseconds) / Double(durationMilliseconds) < 0.7 { preloader.cancel() }
        if ready { engine.seek(to: positionMilliseconds) }
        persistSoon()
    }

    public func setProgressObservationEnabled(_ enabled: Bool) {
        guard progressObservationEnabled != enabled else { return }
        progressObservationEnabled = enabled
        if enabled {
            withMutation(keyPath: \.positionMilliseconds) {}
            withMutation(keyPath: \.bassLevel) {}
        }
    }

    public func tick() {
        defer { stateDidChange?() }
        guard ready else { return }
        let snapshot = engine.snapshot()
        audioBuffering = snapshot.buffering
        if audioBuffering { cacheTask?.cancel() }
        bassLevel = snapshot.bassLevel
        // 可见视频未就绪时，迟到的音频快照不能推进进度或自动跳到下一条。
        if !videoHold { positionMilliseconds = max(0, snapshot.positionMilliseconds) }
        if snapshot.durationMilliseconds > 0 { durationMilliseconds = snapshot.durationMilliseconds }
        if let error = snapshot.errorMessage {
            if snapshot.recoverable, recoverAudio() { return }
            if let track = queue.current { trackProblems[TrackKey(track)] = .unknown }
            fail(error); return
        }
        if snapshot.finished && wantsPlay && !videoHold {
            if pageLoader != nil, pageTail, queue.repeatMode != .one {
                wantsPlay = false; engine.pause(); state = .paused; refillPages(advance: .automatic); return
            }
            if station != nil, queue.index == queue.tracks.indices.last {
                wantsPlay = false; engine.pause(); state = .paused; refillStation(advance: true); return
            }
            if let index = queue.nextIndex(automatic: true) { jump(to: index, intent: .automatic) }
            else { wantsPlay = false; engine.stop(); state = .paused; ready = false; removeFile(); persistSoon() }
            return
        }
        let waitingForPage = pageLoader != nil && pageTail && queue.repeatMode != .one
        let nextIndex = waitingForPage || (station != nil && queue.index == queue.tracks.indices.last) ? nil : queue.nextIndex(automatic: true)
        let next = nextIndex.flatMap { queue.tracks.indices.contains($0) ? queue.tracks[$0] : nil }
        preloader.update(next: next, enabled: wantsPlay && !videoHold && !audioBuffering && durationMilliseconds > 0 && Double(positionMilliseconds) / Double(durationMilliseconds) >= 0.7)
        if abs(positionMilliseconds - savedPosition) >= 5_000 { persistSoon() }
    }

    public func saveNow() async {
        let pending = saveTask
        pending?.cancel()
        await pending?.value
        await writeSession()
    }

    private func writeSession() async {
        guard let store else { return }
        let session = PlaybackSession(queue: queue.tracks, index: queue.index ?? 0, positionMilliseconds: positionMilliseconds, captions: timeline.lines,
            shuffled: queue.shuffled, repeatMode: queue.repeatMode, playOrder: queue.playOrder, station: station)
        do { try await store.saveSession(session); persistenceMessage = nil; savedPosition = session.positionMilliseconds }
        catch { persistenceMessage = "播放记录保存失败" }
    }

    func waitUntilPrepared() async { await preloader.wait() }

    public func waitUntilLoaded() async {
        while let task = loadingTask { await task.value }
        await captionTask?.value
    }

    private func reset() {
        preloader.cancel()
        generation += 1
        loadingTask?.cancel()
        loadingTask = nil
        captionTask?.cancel()
        captionTask = nil
        cacheTask?.cancel(); cacheTask = nil
        engine.stop()
        ready = false
        wantsPlay = false
        audioBuffering = false
        refreshUsed = false
        positionMilliseconds = 0; bassLevel = 0
        timeline = CaptionTimeline(lines: [])
        captionMessage = nil
        errorMessage = nil
        state = .paused
        removeFile()
    }

    private func failTrack(_ track: Track, error: any Error) {
        let problem: TrackProblem
        if case PluginFailure.playback(let known, _) = error { problem = known }
        else { problem = error as? TrackProblem ?? (TrackProblem.isConnectionError(error) ? .network : .unknown) }
        trackProblems[TrackKey(track)] = problem
        fail(problem == .unknown ? AppLocalization.text("音频加载失败：%@", arguments: [error.localizedDescription]) : problem.label)
    }

    private func fail(_ message: String) {
        preloader.cancel()
        engine.stop()
        ready = false
        wantsPlay = false
        audioBuffering = false
        state = .failed
        errorMessage = message
        // 保留曲目、字幕与位置；下一次播放会重取临时地址。
        removeFile()
        persistSoon()
    }

    private func removeFile() {
        if let localFile { try? FileManager.default.removeItem(at: localFile) }
        localFile = nil
    }

    private func persistSoon() {
        guard store != nil else { return }
        saveRevision += 1
        guard saveTask == nil else { return }
        saveTask = Task {
            // 合并同一轮状态更新；持续 tick 不取消待保存任务，避免一直播放却一直不落盘。
            await Task.yield()
            while !Task.isCancelled {
                let revision = saveRevision
                await writeSession()
                if revision == saveRevision { break }
            }
            saveTask = nil
        }
    }

    private static func loadCaptions(_ source: any PlaybackSource, track: Track) async -> (CaptionTimeline, String?) {
        do { return (try await source.captions(track: track), nil) }
        catch { return (CaptionTimeline(lines: []), error.localizedDescription) }
    }
}
