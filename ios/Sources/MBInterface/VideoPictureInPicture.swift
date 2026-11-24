#if os(iOS)
import AVKit
import MBFoundation
import SwiftUI

/// 系统画中画复用当前静音视频，音频与系统的暂停、跳转使用同一播放会话。
@MainActor @Observable final class VideoPictureInPicture: NSObject, @preconcurrency AVPictureInPictureControllerDelegate {
    private(set) var active = false
    private(set) var starting = false
    var running: Bool { active || starting }
    // UIKit 承载状态不参与 SwiftUI 观察；画面交接时改写 layer 不能反过来触发旧、新 Surface 重建。
    @ObservationIgnored private var controller: AVPictureInPictureController?
    @ObservationIgnored private var layer: AVPlayerLayer?
    @ObservationIgnored private var enabled = false
    @ObservationIgnored private var restoring = false
    @ObservationIgnored private var restore: (() -> Void)?
    @ObservationIgnored private weak var video: VideoModel?
    @ObservationIgnored private weak var playback: PlaybackModel?
    @ObservationIgnored private var clockTask: Task<Void, Never>?
    @ObservationIgnored private var revision = 0
    @ObservationIgnored private var trackID: String?

    func configure(enabled: Bool, video: VideoModel, playback: PlaybackModel, restore: @escaping () -> Void) {
        self.enabled = enabled; self.video = video; self.playback = playback; self.restore = restore
        controller?.canStartPictureInPictureAutomaticallyFromInline = enabled
        if !enabled, running { controller?.stopPictureInPicture() }
    }
    func attach(_ layer: AVPlayerLayer) {
        guard AVPictureInPictureController.isPictureInPictureSupported(), self.layer !== layer, !running else { return }
        controller?.delegate = nil
        self.layer = layer
        controller = AVPictureInPictureController(playerLayer: layer)
        controller?.delegate = self
        controller?.canStartPictureInPictureAutomaticallyFromInline = enabled
    }
    func detach(_ layer: AVPlayerLayer) {
        guard self.layer === layer, !running else { return }
        controller?.delegate = nil
        controller = nil; self.layer = nil
    }
    func start() {
        guard enabled, controller?.isPictureInPicturePossible == true, !running else { return }
        starting = true
        controller?.startPictureInPicture()
    }
    func stop() {
        if running { controller?.stopPictureInPicture() }
    }
    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        starting = true; restoring = false
        video?.setSystemControlled(true)
        revision = playback?.controlRevision ?? 0; trackID = playback?.queue.current?.id
    }
    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        starting = false; active = true
        clockTask?.cancel()
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.synchronizeControls()
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }
    private func synchronizeControls() {
        guard active, let video, let playback, let player = video.player, video.isReady else { return }
        if trackID != playback.queue.current?.id || revision != playback.controlRevision {
            trackID = playback.queue.current?.id
            video.applySystemPlayback(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
        } else {
            if player.timeControlStatus == .paused, playback.state == .playing { playback.pause() }
            else if player.rate > 0, playback.state == .paused { playback.play() }
            let seconds = player.currentTime().seconds
            if seconds.isFinite, abs(seconds * 1_000 - Double(playback.positionMilliseconds)) > 650 {
                playback.seek(to: Int64(max(0, seconds * 1_000)))
            }
        }
        revision = playback.controlRevision
    }
    private func finish() {
        clockTask?.cancel(); clockTask = nil
        active = false; starting = false
        video?.setSystemControlled(false)
        if !restoring, video?.isAudioOnly != true, UIApplication.shared.applicationState == .background {
            playback?.pause(); video?.browse()
        }
        if let video, let playback {
            video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
        }
        restoring = false
    }
    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) { finish() }
    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: any Error) { finish() }
    func pictureInPictureController(_ controller: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        restoring = true; restore?(); completionHandler(true)
    }
}

extension EnvironmentValues { @Entry var videoPictureInPicture: VideoPictureInPicture? = nil }
#endif
