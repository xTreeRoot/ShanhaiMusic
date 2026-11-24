import Foundation

/// 插件声明真实可用的画质标识；仅把标识回传插件，不保存会过期的媒体地址。
public struct VideoQualityOption: Identifiable, Equatable, Sendable {
    public let id: String
    public let label: String
    public init?(id: String, label: String) {
        guard Self.valid(id), id != "auto", !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        self.id = id
        self.label = String(label.prefix(64))
    }
    public static func valid(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64 && id.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [45, 46, 95].contains($0) }
    }
}
