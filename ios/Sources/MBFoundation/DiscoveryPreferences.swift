import Foundation

/// 独立 app/discovery 文档，栏目偏好不会被旧客户端的插件排序同步覆盖。
public struct DiscoveryPreferences: Codable, Equatable, Sendable {
    public var version = 1
    public var plugins: [String: PluginPreferences] = [:]
    public init() {}
    public var isEmpty: Bool { plugins.isEmpty }
    public func validated() throws -> Self {
        guard version == 1, plugins.count <= 512, plugins.values.reduce(0, { $0 + $1.enabled.count }) <= 4096 else { throw PlatformDataFailure.invalid }
        for (id, prefs) in plugins {
            guard !id.contains(".."), id.range(of: #"\A[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\z"#, options: .regularExpression) != nil,
                  prefs.names.isEmpty, prefs.enabled.count <= 128, (prefs.order?.value.count ?? 0) <= 128 else { throw PlatformDataFailure.invalid }
            _ = try prefs.validated()
        }
        return self
    }
    public func merged(with other: Self) throws -> Self {
        _ = try validated(); _ = try other.validated()
        var result = self
        for (id, prefs) in other.plugins { result.plugins[id] = try (result.plugins[id] ?? PluginPreferences()).merged(with: prefs) }
        return try result.validated()
    }
    public struct Section: Sendable { public let id: String; public let defaultEnabled: Bool
        public init(id: String, defaultEnabled: Bool) { self.id = id; self.defaultEnabled = defaultEnabled }
    }
    public struct Selection: Equatable, Sendable {
        public var order: [String]
        public var enabled: [String: Bool]
        public init(order: [String], enabled: [String: Bool]) { self.order = order; self.enabled = enabled }
    }
    public func resolve(plugin: String, sections: [Section]) -> Selection {
        let prefs = plugins[plugin] ?? PluginPreferences()
        let configured = prefs.order != nil
        let ids = Set(sections.map(\.id))
        let ordered = (prefs.order?.value ?? []).filter { ids.contains($0) }
        return Selection(order: ordered + sections.map(\.id).filter { !ordered.contains($0) },
            enabled: Dictionary(uniqueKeysWithValues: sections.map { ($0.id, prefs.enabled[$0.id]?.value ?? ($0.defaultEnabled && !configured)) }))
    }
    /// 只写入用户在草稿中修改的字段，保留编辑期间到达的远端修改和未安装栏目。
    public func edited(plugin: String, before: Selection, draft: Selection, writer: String) throws -> Self {
        _ = try validated()
        guard Set(before.enabled.keys) == Set(draft.enabled.keys), draft.order.count == draft.enabled.count,
              Set(draft.order) == Set(draft.enabled.keys) else { throw PlatformDataFailure.invalid }
        var prefs = plugins[plugin] ?? PluginPreferences()
        let initialize = prefs.order == nil
        for id in draft.order where initialize || before.enabled[id] != draft.enabled[id] {
            prefs = try prefs.withEnabled(id: id, enabled: draft.enabled[id] == true, writer: writer)
        }
        if initialize || before.order != draft.order {
            let unknown = (prefs.order?.value ?? []).filter { !draft.order.contains($0) }
            prefs = try prefs.withOrder(draft.order + unknown, writer: writer)
        }
        var result = self; result.plugins[plugin] = prefs
        return try result.validated()
    }
}

protocol PortablePreferences: Codable, Equatable, Sendable {
    func validated() throws -> Self
    func merged(with other: Self) throws -> Self
}
extension PluginPreferences: PortablePreferences {}
extension DiscoveryPreferences: PortablePreferences {}
typealias DiscoveryPreferenceCache = PreferenceCache<DiscoveryPreferences>
