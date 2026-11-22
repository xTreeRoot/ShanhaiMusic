import Foundation

public enum VideoAdjustmentKind: Sendable { case volume, brightness }

/// 按手势起点的系统值计算；向上增加、向下减少，亮度保留可见下限。
public struct VideoAdjustmentDrag: Sendable {
    public let kind: VideoAdjustmentKind
    public let initial: Double
    public let height: Double
    public init(kind: VideoAdjustmentKind, initial: Double, height: Double) {
        self.kind = kind; self.initial = initial; self.height = height
    }
    public func value(translationY: Double) -> Double {
        let minimum = kind == .brightness ? 0.01 : 0
        let baseline = initial.isFinite ? min(1, max(minimum, initial)) : minimum
        guard translationY.isFinite, height.isFinite, height > 0 else { return baseline }
        return min(1, max(minimum, baseline - translationY / (height * 0.75)))
    }
}
