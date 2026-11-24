import Observation

/// 单击只切换控制条；拖动时不自动收起，双击由播放模型处理。
@MainActor @Observable public final class VideoChromeState {
    public private(set) var visible = true
    public private(set) var scrubbing = false
    public private(set) var revision = 0
    public init() {}
    public func singleTap() { visible.toggle(); revision += 1 }
    public func interacted() { revision += 1 }
    public func editing(_ active: Bool) { scrubbing = active; revision += 1 }
    public func autoHide(revision expected: Int) {
        guard expected == revision, !scrubbing else { return }
        visible = false
    }
}
