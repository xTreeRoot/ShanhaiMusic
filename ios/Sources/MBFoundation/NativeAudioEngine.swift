import AVFoundation
import Foundation

public struct AudioEngineSnapshot: Sendable {
    public let positionMilliseconds: Int64
    public let durationMilliseconds: Int64
    public let bassLevel: Double
    public let finished: Bool
    public let errorMessage: String?
    public let buffering: Bool
    public let recoverable: Bool
    public init(positionMilliseconds: Int64, durationMilliseconds: Int64, finished: Bool = false, bassLevel: Double = 0, errorMessage: String? = nil, buffering: Bool = false, recoverable: Bool = false) {
        self.positionMilliseconds = positionMilliseconds
        self.durationMilliseconds = durationMilliseconds
        self.bassLevel = bassLevel
        self.finished = finished
        self.errorMessage = errorMessage
        self.buffering = buffering
        self.recoverable = recoverable
    }
}

@MainActor public protocol AudioEngine: AnyObject {
    func load(url: URL, at milliseconds: Int64) async throws
    func play() throws
    func pause()
    func stop()
    func seek(to milliseconds: Int64)
    func snapshot() -> AudioEngineSnapshot
}

@MainActor public protocol AudioEngineEvents: AnyObject {
    var onUpdate: (() -> Void)? { get set }
}

@MainActor public protocol AudioEngineVolume: AnyObject { func setVolume(_ volume: Double) }

@MainActor public protocol AudioEngineStreaming: AnyObject {
    func load(resource: MediaResource, at milliseconds: Int64) async throws
}

/// 使用公开 AVFoundation API 播放本地缓存文件；请求头和代理由下载层统一处理。
@MainActor public final class NativeAudioEngine: NSObject, AudioEngine, AudioEngineEvents, AudioEngineVolume, AudioEnginePreloading, AudioEngineNormalization {
    private let player = AVPlayer()
    private var duration: Int64 = 0
    private var ended = false
    private var remote: RemoteMediaAsset?
    private var normalizer: AudioNormalizer?
    private var normalization = AudioNormalization.standard
    private var normalizationTask: Task<Void, Never>?
    private var fixedGain = 1.0
    private var measured = false
    private var asset: AVURLAsset?
    private var loadRevision = 0
    private var seekRevision = 0
    private var seekTarget: Int64?
    public var onUpdate: (() -> Void)?
    nonisolated(unsafe) private var timeObserver: Any?
    public override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(didEnd(_:)), name: .AVPlayerItemDidPlayToEndTime, object: nil)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 50, timescale: 1_000), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.onUpdate?() }
        }
    }

    @objc private func didEnd(_ notification: Notification) {
        guard let item = notification.object as? AVPlayerItem, player.currentItem === item else { return }
        ended = true
        onUpdate?()
    }

    public func load(url: URL, at milliseconds: Int64) async throws {
        remote?.cancel(); remote = nil
        try await prepare(AVURLAsset(url: url), at: milliseconds)
    }

    public func load(resource: MediaResource, at milliseconds: Int64) async throws {
        if resource.url.isFileURL { try await load(url: resource.url, at: milliseconds); return }
        remote?.cancel()
        let loader = try RemoteMediaAsset(resource: resource)
        remote = loader
        do { try await prepare(loader.asset, at: milliseconds) }
        catch { throw loader.lastFailure ?? error }
    }

    public func prepare(resource: MediaResource) async throws -> any PreparedAudio {
        let loader = try RemoteMediaAsset(resource: resource)
        do { try await loader.prime(); return NativePreparedAudio(loader) }
        catch { loader.cancel(); throw error }
    }
    public func load(prepared: any PreparedAudio, at milliseconds: Int64) async throws {
        guard let token = prepared as? NativePreparedAudio, let loader = token.take() else { throw MediaStreamingFailure.unplayable }
        remote?.cancel(); remote = loader
        do { try await prepare(loader.asset, at: milliseconds) }
        catch { throw loader.lastFailure ?? error }
    }

    private func prepare(_ asset: AVURLAsset, at milliseconds: Int64) async throws {
        guard try await asset.load(.isPlayable) else { throw MediaStreamingFailure.unplayable }
        let time = try await asset.load(.duration)
        try Task.checkCancellation()
        duration = Self.milliseconds(time)
        ended = false
        loadRevision += 1
        self.asset = asset
        fixedGain = 1; measured = false
        normalizationTask?.cancel(); normalizationTask = nil
        let normalizer = try AudioNormalizer()
        normalizer.configure(normalization, gain: fixedGain)
        self.normalizer = normalizer
        let item = AVPlayerItem(asset: asset)
        if let audioTrack = try await asset.loadTracks(withMediaType: .audio).first { item.audioMix = try normalizer.mix(track: audioTrack) }
        try Task.checkCancellation()
        player.replaceCurrentItem(with: item)
        _ = await player.seek(to: CMTime(value: min(max(0, milliseconds), max(0, duration - 1)), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero)
        try Task.checkCancellation()
        measureGain()
    }

    public func setNormalization(_ mode: AudioNormalization) {
        guard normalization != mode else { return }
        normalization = mode
        normalizer?.configure(mode, gain: fixedGain)
        if mode == .standard { measureGain() }
        else { normalizationTask?.cancel(); normalizationTask = nil }
    }
    private func measureGain() {
        guard remote == nil, normalization == .standard, !measured, normalizationTask == nil, let asset else { return }
        let current = loadRevision
        normalizationTask = Task {
            do {
                let gain = try await LoudnessAnalyzer().gain(asset: asset)
                guard !Task.isCancelled, current == loadRevision else { return }
                fixedGain = gain; measured = true; normalizer?.configure(normalization, gain: gain)
            } catch { /* 不能解码测量时保持原始增益，播放不中断。 */ }
            if current == loadRevision, !Task.isCancelled { normalizationTask = nil }
        }
    }

    public func play() throws {
        #if os(iOS)
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try AVAudioSession.sharedInstance().setActive(true)
        #endif
        ended = false
        player.play()
    }

    public func pause() { player.pause() }
    public func setVolume(_ volume: Double) { player.volume = Float(volume.isFinite ? min(1, max(0, volume)) : 0.8) }
    public func stop() {
        seekRevision += 1
        seekTarget = nil
        loadRevision += 1
        normalizationTask?.cancel(); normalizationTask = nil
        normalizer = nil; asset = nil; fixedGain = 1
        remote?.cancel(); remote = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        duration = 0
        ended = false
    }
    public func seek(to milliseconds: Int64) {
        ended = false
        seekRevision += 1
        let revision = seekRevision
        let target = max(0, milliseconds)
        seekTarget = target
        player.currentItem?.cancelPendingSeeks()
        player.seek(to: CMTime(value: target, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.seekRevision == revision else { return }
                self.seekTarget = nil; self.onUpdate?()
            }
        }
    }
    public func snapshot() -> AudioEngineSnapshot {
        let failure = remote?.lastFailure ?? player.currentItem?.error
        return AudioEngineSnapshot(positionMilliseconds: seekTarget ?? Self.milliseconds(player.currentTime()), durationMilliseconds: duration,
                                   finished: ended, bassLevel: player.rate == 0 ? 0 : normalizer?.bassLevel ?? 0,
                                   errorMessage: player.currentItem?.error == nil ? nil : "音频读取失败，点击播放重试",
                                   buffering: seekTarget != nil || player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
                                   recoverable: failure.map(MediaStreamingFailure.allowsRefresh) ?? false)
    }

    private static func milliseconds(_ time: CMTime) -> Int64 {
        let seconds = time.seconds
        guard seconds.isFinite, seconds >= 0, seconds * 1_000 < Double(Int64.max) else { return 0 }
        return Int64((seconds * 1_000).rounded())
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        if let timeObserver { player.removeTimeObserver(timeObserver) }
    }
}

/// 消费后移交所有权，清理旧队列预备令牌不会取消当前播放。
@MainActor private final class NativePreparedAudio: PreparedAudio {
    private var loader: RemoteMediaAsset?
    init(_ loader: RemoteMediaAsset) { self.loader = loader }
    func take() -> RemoteMediaAsset? { defer { loader = nil }; return loader }
    func cancel() { loader?.cancel(); loader = nil }
}
