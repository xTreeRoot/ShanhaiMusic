import AVFoundation
import Foundation
@preconcurrency import MediaPlayer
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// CarPlay 系统正在播放、锁屏与耳机共用播放会话；歌词使用音频时钟，不依赖前台页面。
@MainActor public final class SystemPlaybackCoordinator: NSObject {
    private struct InfoKey: Equatable {
        let trackID: String
        let title: String
        let artist: String
        let second: Int64
        let duration: Int64
        let controlRevision: Int
        let playing: Bool
        let artwork: Bool
    }
    private weak var playback: PlaybackModel?
    private var commands: [(MPRemoteCommand, Any)] = []
    private var interruptedRevision: Int?
    private var interruptedTrack: String?
    private var infoKey: InfoKey?
    private var lyricsEnabled = true
    private let publish: ([String: Any]?, MPNowPlayingPlaybackState) -> Void
    private var artworkTrack: String?
    private var artwork: MPMediaItemArtwork?
    private var artworkTask: Task<Void, Never>?
    #if os(iOS)
    private var audioRouteObserver: NSObjectProtocol?
    private var lastCarAudio: Bool?
    private var lastLoggedState: PlaybackState?
    #endif

    public override init() {
        publish = { info, state in
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            MPNowPlayingInfoCenter.default().playbackState = state
        }
        super.init()
    }

    init(publish: @escaping ([String: Any]?, MPNowPlayingPlaybackState) -> Void) {
        self.publish = publish
        super.init()
    }

    public func setLyricsEnabled(_ enabled: Bool) {
        guard lyricsEnabled != enabled else { return }
        lyricsEnabled = enabled
        update()
    }

    public func connect(_ playback: PlaybackModel, onCarAudioChanged: (@MainActor (Bool) -> Void)? = nil) {
        guard self.playback == nil else { return }
        self.playback = playback
        playback.stateDidChange = { [weak self] in self?.update() }
        let center = MPRemoteCommandCenter.shared()
        bind(center.playCommand) { $0.play() }
        bind(center.pauseCommand) { $0.pause() }
        bind(center.togglePlayPauseCommand) { $0.toggle() }
        bind(center.nextTrackCommand) { $0.next() }
        bind(center.previousTrackCommand) { $0.previous() }
        bind(center.skipForwardCommand) { $0.seek(to: $0.positionMilliseconds + 15_000) }
        bind(center.skipBackwardCommand) { $0.seek(to: $0.positionMilliseconds - 15_000) }
        center.skipForwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.preferredIntervals = [15]
        center.changePlaybackPositionCommand.isEnabled = true
        let token = center.changePlaybackPositionCommand.addTarget { @Sendable [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent, event.positionTime.isFinite,
                  event.positionTime >= 0, event.positionTime * 1_000 < Double(Int64.max) else { return .commandFailed }
            let position = Int64(event.positionTime * 1_000)
            Task { @MainActor in self?.playback?.seek(to: position) }
            return .success
        }
        commands.append((center.changePlaybackPositionCommand, token))
        #if os(iOS)
        NotificationCenter.default.addObserver(self, selector: #selector(interruption(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        audioRouteObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            let disconnected = raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            Task { @MainActor [weak self] in
                guard let self, self.playback != nil else { return }
                let audioOnly = self.updateCarAudio(onChange: onCarAudioChanged)
                if disconnected && !audioOnly { self.pauseAfterRouteLoss() }
            }
        }
        updateCarAudio(onChange: onCarAudioChanged)
        #endif
        update()
    }

    private func bind(_ command: MPRemoteCommand, action: @escaping @MainActor (PlaybackModel) -> Void) {
        command.isEnabled = true
        let token = command.addTarget { @Sendable [weak self] _ in
            Task { @MainActor in if let playback = self?.playback { action(playback) } }
            return .success
        }
        commands.append((command, token))
    }

    private func update() {
        let hasTrack = playback?.queue.current != nil
        #if os(iOS)
        let state = hasTrack ? playback?.state : nil
        if lastLoggedState != state {
            lastLoggedState = state
            switch state {
            case .loading: AppPersistence.shared.logPlayback(.audioLoading)
            case .playing: AppPersistence.shared.logPlayback(.audioPlaying)
            case .paused: AppPersistence.shared.logPlayback(.audioPaused)
            case .failed: AppPersistence.shared.logPlayback(.audioFailed)
            case nil: break
            }
        }
        #endif
        for (command, _) in commands { command.isEnabled = hasTrack }
        MPRemoteCommandCenter.shared().nextTrackCommand.isEnabled = hasTrack && ((playback?.queue.tracks.count ?? 0) > 1 || playback?.station != nil)
        MPRemoteCommandCenter.shared().previousTrackCommand.isEnabled = hasTrack && (playback?.queue.tracks.count ?? 0) > 1
        guard let playback, let track = playback.queue.current else {
            artworkTask?.cancel(); artworkTask = nil; artwork = nil; artworkTrack = nil
            infoKey = nil
            publish(nil, .stopped)
            return
        }
        if artworkTrack != track.id {
            artworkTrack = track.id; artwork = nil; artworkTask?.cancel()
            if let url = track.artworkURL {
                artworkTask = Task { [weak self] in
                    do {
                        let data: Data
                        if url.isFileURL { data = try await Task.detached(priority: .utility) { try Data(contentsOf: url) }.value }
                        else {
                            data = try await ArtworkCache.shared.data(url: url, sourceID: track.sourceID, headers: track.artworkHeaders)
                        }
                        guard !Task.isCancelled, self?.artworkTrack == track.id else { return }
                        self?.artwork = Self.makeArtwork(data: data)
                        self?.update()
                    } catch { /* 锁屏封面失败不打断音频。 */ }
                }
            }
        }
        var title = track.title
        let artist = lyricsEnabled ? track.title : track.artist
        if lyricsEnabled, let index = playback.timeline.activeIndex(at: playback.positionMilliseconds) {
            let line = playback.timeline.lines[index].text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            if !line.isEmpty { title = line }
        }
        // 普通进度每秒更新；同一秒内换句、拖动、切曲或修改设置立即更新。
        let key = InfoKey(trackID: track.id, title: title, artist: artist,
                          second: playback.positionMilliseconds / 1_000, duration: playback.durationMilliseconds,
                          controlRevision: playback.controlRevision, playing: playback.state == .playing, artwork: artwork != nil)
        guard key != infoKey else { return }; infoKey = key
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title, MPMediaItemPropertyArtist: artist,
            MPMediaItemPropertyPlaybackDuration: Double(playback.durationMilliseconds) / 1_000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(playback.positionMilliseconds) / 1_000,
            MPNowPlayingInfoPropertyPlaybackRate: playback.state == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        publish(info, playback.state == .playing ? .playing : .paused)
    }

    // MediaPlayer 在自己的队列请求图片，回调不能继承播放器的 MainActor 隔离。
    nonisolated static func makeArtwork(data: Data) -> MPMediaItemArtwork? {
        #if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        #else
        guard let image = NSImage(data: data) else { return nil }
        #endif
        return MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }

    public func disconnect() {
        for (command, token) in commands { command.removeTarget(token); command.isEnabled = false }
        commands.removeAll()
        NotificationCenter.default.removeObserver(self)
        #if os(iOS)
        if let audioRouteObserver { NotificationCenter.default.removeObserver(audioRouteObserver) }
        audioRouteObserver = nil
        lastCarAudio = nil
        lastLoggedState = nil
        #endif
        artworkTask?.cancel(); artworkTask = nil
        playback?.stateDidChange = nil; playback = nil
        artwork = nil; artworkTrack = nil; infoKey = nil
        publish(nil, .stopped)
    }

    #if os(iOS)
    @objc private func interruption(_ notification: Notification) {
        guard let playback, let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            let playing = playback.state == .playing || playback.state == .loading
            playback.pause()
            interruptedRevision = playing ? playback.controlRevision : nil
            interruptedTrack = playback.queue.current?.id
        } else {
            let rawOptions = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume)
            if shouldResume, interruptedRevision == playback.controlRevision, interruptedTrack == playback.queue.current?.id { playback.play() }
            interruptedRevision = nil; interruptedTrack = nil
        }
    }
    @discardableResult private func updateCarAudio(onChange: (@MainActor (Bool) -> Void)?) -> Bool {
        let audioOnly = AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .carAudio }
        if audioOnly != lastCarAudio {
            lastCarAudio = audioOnly
            AppPersistence.shared.logPlayback(audioOnly ? .carAudio : .otherAudio)
            onChange?(audioOnly)
        }
        return audioOnly
    }

    private func pauseAfterRouteLoss() {
        interruptedRevision = nil; interruptedTrack = nil
        playback?.pause()
    }
    #endif
}
