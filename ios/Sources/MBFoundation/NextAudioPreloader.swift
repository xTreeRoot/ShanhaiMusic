import Foundation

/// 预备资源只持有网络缓存；载入时由引擎接管，不能提前建立音频输出或改变当前曲目。
@MainActor public protocol PreparedAudio: AnyObject { func cancel() }
@MainActor public protocol AudioEnginePreloading: AudioEngineStreaming {
    func prepare(resource: MediaResource) async throws -> any PreparedAudio
    /// 成功后接管预备资源；之后取消令牌不会停止正在播放的音频。
    func load(prepared: any PreparedAudio, at milliseconds: Int64) async throws
}

@MainActor final class PreparedPlaybackAudio {
    let resource: MediaResource
    let audio: any PreparedAudio
    init(resource: MediaResource, audio: any PreparedAudio) { self.resource = resource; self.audio = audio }
    func cancel() { audio.cancel() }
}

/// 一次只准备下一首的首块，失败保持静默；队列、会话和音质变化均能取消。
@MainActor final class NextAudioPreloader {
    private let source: (any PlaybackSource)?
    private let engine: (any AudioEnginePreloading)?
    private var task: Task<Void, Never>?
    private var target: Track?
    private var payload: PreparedPlaybackAudio?
    private var revision = 0
    private var preparedAt: ContinuousClock.Instant?
    private let now: () -> ContinuousClock.Instant
    init(source: (any PlaybackSource)?, engine: any AudioEngine, now: @escaping () -> ContinuousClock.Instant = { .now }) {
        self.source = source; self.engine = engine as? any AudioEnginePreloading; self.now = now
    }
    func update(next: Track?, enabled: Bool) {
        guard enabled, let next, next.localFileID == nil, next.unavailable != true else { cancel(); return }
        if target != next { cancel() }
        guard target == nil, let source, let engine else { return }
        target = next
        let current = revision
        task = Task {
            do {
                let resource = try await source.audio(track: next)
                try Task.checkCancellation()
                let audio = try await engine.prepare(resource: resource)
                guard !Task.isCancelled, current == revision else { audio.cancel(); return }
                payload = PreparedPlaybackAudio(resource: resource, audio: audio); preparedAt = now()
            } catch { /* 预加载失败不改变当前曲目的错误、播放状态和队列。 */ }
            if current == revision { task = nil }
        }
    }
    func take(for track: Track) -> PreparedPlaybackAudio? {
        guard target == track, let payload, let preparedAt, preparedAt.duration(to: now()) < .seconds(90) else { cancel(); return nil }
        self.payload = nil
        cancel()
        return payload
    }
    func cancel() {
        revision += 1; task?.cancel(); task = nil
        payload?.cancel(); payload = nil; target = nil; preparedAt = nil
    }
    func wait() async { await task?.value }
}
