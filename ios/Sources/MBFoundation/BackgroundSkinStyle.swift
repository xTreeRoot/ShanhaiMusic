import Foundation

public enum BackgroundAccent: String, Codable, CaseIterable, Identifiable, Sendable {
    case system, peach, rose, silver, forest, brick, red, orange
    public var id: String { rawValue }
}

/// 保留原图，缩放、定位和视觉参数独立保存，重新编辑不会反复损失图片区域。
public struct BackgroundSkinStyle: Codable, Equatable, Sendable {
    public var zoom = 1.0
    public var horizontal = 0.0
    public var vertical = 0.0
    public var blur = 0.0
    public var accent = BackgroundAccent.system
    public var appliesToPlayer = false
    public init() {}
    private enum CodingKeys: String, CodingKey { case zoom, horizontal, vertical, blur, accent, appliesToPlayer }
    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        func number(_ key: CodingKeys, _ fallback: Double, _ range: ClosedRange<Double>) throws -> Double {
            let n = try values.decodeIfPresent(Double.self, forKey: key) ?? fallback
            return n.isFinite ? min(range.upperBound, max(range.lowerBound, n)) : fallback
        }
        zoom = try number(.zoom, 1, 1...4)
        horizontal = try number(.horizontal, 0, -1...1)
        vertical = try number(.vertical, 0, -1...1)
        blur = try number(.blur, 0, 0...30)
        accent = try values.decodeIfPresent(BackgroundAccent.self, forKey: .accent) ?? .system
        appliesToPlayer = try values.decodeIfPresent(Bool.self, forKey: .appliesToPlayer) ?? false
    }
}
