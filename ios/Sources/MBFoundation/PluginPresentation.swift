import Foundation

/// 本地展示偏好独立于插件代码、远程清单和登录数据。
public struct PluginPresentation: Codable, Equatable, Sendable {
    public var names: [String: String] = [:]
    public var order: [String] = []
    public init() {}
    public func name(id: String, fallback: String) -> String { names[id] ?? fallback }
    public func sorted(_ plugins: [InstalledPlugin]) -> [InstalledPlugin] {
        let positions = Dictionary(order.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { a, _ in a })
        return plugins.sorted {
            let a = positions[$0.id] ?? Int.max, b = positions[$1.id] ?? Int.max
            return a == b ? $0.manifest.displayName.localizedStandardCompare($1.manifest.displayName) == .orderedAscending : a < b
        }
    }
    public mutating func rename(_ id: String, to value: String) throws {
        let name = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard name.count <= 60 else { throw PluginFailure.invalid("本地名称最多 60 个字符") }
        names[id] = name.isEmpty ? nil : name
    }
}

/// 调整已安装插件时保留暂未安装插件的位置，恢复插件后仍可回到原顺序。
func preservingMissingPluginOrder(_ previous: [String], visible: [String]) -> [String] {
    let present = Set(visible)
    var remaining = ArraySlice(visible)
    var result = previous.map { present.contains($0) ? remaining.removeFirst() : $0 }
    result.append(contentsOf: remaining)
    return result
}
