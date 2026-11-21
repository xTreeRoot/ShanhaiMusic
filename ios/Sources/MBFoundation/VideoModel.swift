import AVFoundation
import Foundation
import Observation

@MainActor public protocol VideoEngine: AnyObject {
    func load(url: URL, at milliseconds: Int64) async throws
    func load(resource: MediaResource, at milliseconds: Int64) async throws
    func sync(positionMilliseconds: Int64, playing: Bool)
    func stop()
    var errorMessage: String? { get }
    var isBuffering: Bool { get }
    var recoverable: Bool { get }
    var aspectRatio: Double? { get }
}

public extension VideoEngine {
    var isBuffering: Bool { false }
    var recoverable: Bool { false }
    var aspectRatio: Double? { nil }
    func load(resource: MediaResource, at milliseconds: Int64) async throws { try await load(url: resource.url, at: milliseconds) }
}

@MainActor public protocol VideoEngineEvents: AnyObject {
    var onUpdate: (() -> Void)? { get set }
}

/// 模式偏好只应用于主动选择的内容；观看当前视频复用原音频会话。
@MainActor @Observable public final class VideoModel {
    public private(set) var mode: PlaybackMode = .music
    public private(set) var presentationMode: PlaybackMode = .music
    public private(set) var isVisible = false
    public private(set) var isFloating = false
    public private(set) var systemControlled = false
    public private(set) var isLoading = false
    public private(set) var isReady = false
    public private(set) var isBuffering = false
    public private(set) var errorMessage: String?
    public private(set) var persistenceMessage: String?
    public private(set) var track: Track?
    private var measuredAspectRatio: Double?
    public var aspectRatio: Double { measuredAspectRatio ?? track.flatMap { track in
        guard let width = track.videoWidth, let height = track.videoHeight, width > 0, height > 0 else { return track.shortVideo == true ? 9.0 / 16 : nil }
        return Double(width) / Double(height)
    } ?? 16.0 / 9 }
    public private(set) var isSupported: Bool
    public var player: AVPlayer? { (engine as? NativeVideoEngine)?.player }
    @ObservationIgnored private let source: (any VideoSource)?
    @ObservationIgnored private let engine: any VideoEngine
    @ObservationIgnored private let store: (any PlaybackModeStore)?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var preferenceRevision = 0
    @ObservationIgnored private var position: Int64 = 0
    @ObservationIgnored private var playing = false
    @ObservationIgnored private var refreshUsed = false
    @ObservationIgnored private var warmAttempted = false

    public init(source: (any VideoSource)?, engine: any VideoEngine = NativeVideoEngine(), store: (any PlaybackModeStore)? = nil) {
        self.source = source
        self.isSupported = source != nil
        self.engine = engine
        self.store = store
        if let events = engine as? any VideoEngineEvents {
            events.onUpdate = { [weak self] in self?.refreshEngineStatus() }
        }
    }

    public func restore() async {
        let current = preferenceRevision
        do {
            if let saved = try await store?.loadPlaybackMode(), current == preferenceRevision {
                mode = saved
            }
        } catch { persistenceMessage = "播放模式读取失败" }
    }

    public func choose(_ mode: PlaybackMode) async {
        preferenceRevision += 1
        let revision = preferenceRevision
        self.mode = mode
        if mode == .music {
            presentationMode = .music
            isVisible = false; isFloating = false
            release()
        }
        let previous = saveTask
        let saving = Task {
            await previous?.value
            do {
                try await store?.savePlaybackMode(mode)
                if revision == preferenceRevision { persistenceMessage = nil }
            } catch {
                if revision == preferenceRevision { persistenceMessage = "播放模式保存失败" }
            }
        }
        saveTask = saving
        await saving.value
    }

    public func updateTrack(_ track: Track?) {
        guard self.track?.id != track?.id else { return }
        refreshUsed = false
        warmAttempted = false
        release()
        self.track = track
        position = 0
        if track == nil || track?.localFileID != nil { isVisible = false; isFloating = false; presentationMode = .music }
        if isVisible || isFloating { load() }
    }

    public func handleSelection(_ track: Track?, intent: PlaybackSelectionIntent, positionMilliseconds: Int64, playing: Bool) {
        if intent == .content {
            refreshUsed = false
            warmAttempted = false
            release()
            self.track = track
            position = positionMilliseconds
            self.playing = playing
            presentationMode = track?.localFileID == nil ? mode : .music
            isFloating = false
            isVisible = presentationMode == .video && track != nil
            if isVisible || isFloating { load() }
        } else {
            updateTrack(track)
            sync(positionMilliseconds: positionMilliseconds, playing: playing)
        }
    }

    public func open() {
        guard presentationMode == .video, track != nil else { return }
        isFloating = false; isVisible = true
        if !isReady && task == nil { load() }
    }

    /// 音频已播放后后台准备同一条静音视频，打开时复用已加载的播放器。
    public func preloadCurrent() {
        guard !isVisible, playing, !isReady, task == nil, !warmAttempted,
              let track, track.localFileID == nil else { return }
        warmAttempted = true
        load(warming: true)
    }

    public func watchCurrent() {
        guard let track, track.localFileID == nil else { return }
        presentationMode = .video
        open()
    }

    public func browse() { isVisible = false; isFloating = false; release() }
    public func minimize() {
        guard isVisible, presentationMode == .video, track != nil else { return }
        isFloating = true; isVisible = false
    }
    public func retry() { refreshUsed = false; release(); load() }

    public func sync(positionMilliseconds: Int64, playing: Bool) {
        position = positionMilliseconds
        self.playing = playing
        guard isReady else { return }
        refreshEngineStatus()
        guard isReady else { return }
        if !systemControlled { engine.sync(positionMilliseconds: position, playing: playing) }
        isBuffering = engine.isBuffering
        measuredAspectRatio = engine.aspectRatio
    }

    /// 系统画中画使用视频时钟；应用和锁屏上的显式操作仍可同步到当前视频。
    public func setSystemControlled(_ value: Bool) { systemControlled = value }
    public func applySystemPlayback(positionMilliseconds: Int64, playing: Bool) {
        guard isReady else { return }
        engine.sync(positionMilliseconds: positionMilliseconds, playing: playing)
    }

    private func refreshEngineStatus() {
        guard isReady else { return }
        if let message = engine.errorMessage {
            let shouldRetry = engine.recoverable && !refreshUsed
            release()
            if shouldRetry { refreshUsed = true; load() }
            else { errorMessage = message }
            return
        }
        isBuffering = engine.isBuffering
        measuredAspectRatio = engine.aspectRatio
    }

    public func waitUntilLoaded() async { await task?.value }

    private func load(warming: Bool = false) {
        guard isVisible || isFloating || warming, let track, let source else { return }
        generation += 1
        let current = generation
        isLoading = true
        errorMessage = nil
        task = Task {
            do {
                let supported = await source.supportsVideo(track: track)
                guard current == generation, !Task.isCancelled else { return }
                isSupported = supported
                guard supported else { isLoading = false; task = nil; return }
                let resource = try await source.video(track: track)
                try Task.checkCancellation()
                try await engine.load(resource: resource, at: position)
                guard current == generation, !Task.isCancelled else { return }
                engine.sync(positionMilliseconds: position, playing: playing)
                measuredAspectRatio = engine.aspectRatio
                isReady = true
                isBuffering = engine.isBuffering
                isLoading = false
                task = nil
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                release()
                if !warming, MediaStreamingFailure.allowsRefresh(error), !refreshUsed { refreshUsed = true; load(); return }
                if !warming { errorMessage = "视频加载失败，点击重试" }
            }
        }
    }

    private func release() {
        generation += 1
        task?.cancel()
        task = nil
        engine.stop()
        measuredAspectRatio = nil
        isReady = false
        isLoading = false
        isBuffering = false
        errorMessage = nil
    }
}

/// 一个静音 AVPlayer 供主画面和展开播放器轮流使用，不启动第二条音轨。
@MainActor public final class NativeVideoEngine: VideoEngine, VideoEngineEvents {
    public let player = AVPlayer()
    public private(set) var aspectRatio: Double?
    public var onUpdate: (() -> Void)?
    private var playerObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var seeking = false
    private var seekGeneration = 0
    private var seekTarget: Int64?
    private var remote: RemoteMediaAsset?
    private var temporaryFile: URL?
    public var errorMessage: String? { player.currentItem?.error == nil ? nil : "视频读取失败，点击重试" }
    public var recoverable: Bool { remote?.lastFailure.map(MediaStreamingFailure.allowsRefresh) ?? false }
    public var isBuffering: Bool { seeking || player.currentItem?.status == .unknown || player.timeControlStatus == .waitingToPlayAtSpecifiedRate }
    public init() {
        player.isMuted = true
        playerObservation = player.observe(\.timeControlStatus) { @Sendable [weak self] _, _ in
            Task { @MainActor in self?.onUpdate?() }
        }
    }

    public func load(url: URL, at milliseconds: Int64) async throws {
        remote?.cancel()
        remote = nil
        let asset = AVURLAsset(url: url)
        try await prepare(asset, at: milliseconds)
    }

    public func load(resource: MediaResource, at milliseconds: Int64) async throws {
        if resource.url.isFileURL { try await load(url: resource.url, at: milliseconds); return }
        remote?.cancel()
        let loader = try RemoteMediaAsset(resource: resource)
        remote = loader
        do { try await prepare(loader.asset, at: milliseconds) }
        catch {
            try Task.checkCancellation()
            let failure = loader.lastFailure ?? error
            guard case MediaStreamingFailure.rangeStatus(200) = failure else { throw failure }
            loader.cancel(); remote = nil
            let file = try await NetworkClient.shared.download(resource, maximumBytes: 128 << 20)
            if Task.isCancelled { try? FileManager.default.removeItem(at: file); throw CancellationError() }
            temporaryFile = file
            try await prepare(AVURLAsset(url: file), at: milliseconds)
        }
    }

    private func prepare(_ asset: AVURLAsset, at milliseconds: Int64) async throws {
        guard try await asset.load(.isPlayable) else { throw MediaStreamingFailure.unplayable }
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard !tracks.isEmpty else { throw MediaStreamingFailure.unplayable }
        for track in tracks {
            // 首帧需要的远程轨道信息先异步就绪，渲染时不再同步询问服务器。
            let (_, size, transform) = try await track.load(.formatDescriptions, .naturalSize, .preferredTransform)
            let display = size.applying(transform)
            if abs(display.width) > 0, abs(display.height) > 0 { aspectRatio = abs(display.width / display.height) }
        }
        _ = try await asset.load(.duration)
        try Task.checkCancellation()
        seekGeneration += 1
        seeking = false
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 3
        itemObservation = item.observe(\.status) { @Sendable [weak self] _, _ in
            Task { @MainActor in self?.onUpdate?() }
        }
        player.replaceCurrentItem(with: item)
        player.isMuted = true
        _ = await player.seek(to: CMTime(value: max(0, milliseconds), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero)
        try Task.checkCancellation()
    }

    public func sync(positionMilliseconds: Int64, playing: Bool) {
        let seconds = player.currentTime().seconds
        let drift = Double(positionMilliseconds) - (seconds.isFinite ? seconds * 1_000 : 0)
        let buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        let newTarget = seekTarget.map { abs($0 - positionMilliseconds) > 120 } ?? false
        if abs(drift) > (playing ? 600 : 80), newTarget || !seeking && !buffering {
            seeking = true
            seekGeneration += 1
            let current = seekGeneration
            seekTarget = positionMilliseconds
            player.currentItem?.cancelPendingSeeks()
            player.seek(to: CMTime(value: max(0, positionMilliseconds), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                Task { @MainActor in
                    if self?.seekGeneration == current { self?.seekTarget = nil; self?.seeking = false; self?.onUpdate?() }
                }
            }
        }
        if playing {
            let rate: Float = !buffering && abs(drift) > 80 && abs(drift) <= 600 ? (drift > 0 ? 1.03 : 0.97) : 1
            if player.rate != rate { player.rate = rate }
        } else { player.pause() }
    }

    public func stop() {
        seekGeneration += 1
        itemObservation = nil
        remote?.cancel()
        remote = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        aspectRatio = nil
        seeking = false
        seekTarget = nil
        if let temporaryFile { try? FileManager.default.removeItem(at: temporaryFile); self.temporaryFile = nil }
    }
}
