import Foundation

/// 只保留下一条视频的 256KiB 首块，不建立解码器或音轨；交接后由播放器接管。
@MainActor final class PreparedVideo {
    let resource: MediaResource
    let loader: RemoteMediaAsset
    init(resource: MediaResource, loader: RemoteMediaAsset) { self.resource = resource; self.loader = loader }
    func cancel() { loader.cancel() }
}

@MainActor final class NextVideoPreloader {
    private let source: (any VideoSource)?
    private let prepare: @MainActor (MediaResource) async throws -> RemoteMediaAsset
    private let now: () -> ContinuousClock.Instant
    private var target: Track?
    private var quality = "auto"
    private var task: Task<Void, Never>?
    private var payload: PreparedVideo?
    private var preparedAt: ContinuousClock.Instant?
    private var revision = 0

    init(source: (any VideoSource)?, now: @escaping () -> ContinuousClock.Instant = { .now },
         prepare: @escaping @MainActor (MediaResource) async throws -> RemoteMediaAsset = { resource in
             let loader = try RemoteMediaAsset(resource: resource)
             do { try await loader.prime(); return loader }
             catch { loader.cancel(); throw error }
         }) { self.source = source; self.now = now; self.prepare = prepare }

    func update(next: Track?, quality: String, enabled: Bool) {
        guard enabled, let next, next.localFileID == nil, next.unavailable != true else { cancel(); return }
        if target != next || self.quality != quality { cancel() }
        guard target == nil, let source else { return }
        target = next; self.quality = quality
        let current = revision
        task = Task {
            do {
                guard await source.supportsVideo(track: next) else { return }
                let resource = try await source.video(track: next, qualityID: quality)
                try Task.checkCancellation()
                let loader = try await prepare(resource)
                guard !Task.isCancelled, current == revision else { loader.cancel(); return }
                payload = PreparedVideo(resource: resource, loader: loader); preparedAt = now()
            } catch { /* 预加载失败保持静默，不改变当前播放。 */ }
            if current == revision { task = nil }
        }
    }
    func take(for track: Track, quality: String) -> PreparedVideo? {
        guard target == track, self.quality == quality, let payload, let preparedAt,
              preparedAt.duration(to: now()) < .seconds(90) else { cancel(); return nil }
        self.payload = nil; cancel(); return payload
    }
    func cancel() {
        revision += 1; task?.cancel(); task = nil
        payload?.cancel(); payload = nil; target = nil; preparedAt = nil
    }
    func wait() async { await task?.value }
}
