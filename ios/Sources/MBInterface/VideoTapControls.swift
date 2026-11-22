import MBFoundation
import SwiftUI

/// 手势仅作用于视频画面，浮层按钮与进度条不会吞掉画面单击。
struct VideoTapControls: ViewModifier {
    let chrome: VideoChromeState
    let playback: PlaybackModel
    var allowsAdjustments = true
    func body(content: Content) -> some View {
        content.modifier(VideoAdjustmentControls(enabled: allowsAdjustments)).contentShape(Rectangle())
            .gesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { tap in
                switch tap {
                case .first: playback.toggle(); chrome.interacted()
                case .second: chrome.singleTap()
                }
            })
            .accessibilityAction(named: "显示或隐藏控制条") { chrome.singleTap() }
            .task(id: "\(chrome.revision):\(playback.state == .playing):\(chrome.visible)") {
                guard chrome.visible, !chrome.scrubbing, playback.state == .playing else { return }
                let revision = chrome.revision
                do { try await Task.sleep(for: .seconds(4)); try Task.checkCancellation(); chrome.autoHide(revision: revision) }
                catch { /* 用户继续操作或页面离开时取消自动收起。 */ }
            }
            .onChange(of: playback.controlRevision) { _, _ in chrome.interacted() }
    }
}
