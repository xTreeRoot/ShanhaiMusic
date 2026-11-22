import Foundation

public struct PluginNavigationPreview: Sendable {
    public let revision = UUID()
    public let content: PluginBrowse?
    public let error: String?
}

public struct PluginDiscoverySnapshot: Sendable {
    public let home: PluginBrowse
    public let previews: [String: PluginNavigationPreview]
}

extension PluginManifest.Navigation {
    public var previewKey: String { page.map { "page:" + $0 } ?? "collection:" + (builtin ?? "") }
}

extension PluginSource {
    public nonisolated var personalNavigation: [PluginManifest.Navigation] {
        (manifest.navigation ?? []).filter { $0.placement == .personal && ($0.page != nil || $0.builtin != nil) }
    }

    public nonisolated func discoveryNavigation(_ home: PluginBrowse?) -> [PluginManifest.Navigation] {
        (manifest.navigation ?? []).filter { item in
            guard item.placement != .personal, item.builtin != "home", item.builtin != "search", item.page != "home", item.page != "search",
                  let destination = item.page ?? item.builtin else { return false }
            return !(home?.sections.contains { $0.morePage == destination || (item.title?.isEmpty == false && $0.title == item.title) } ?? false)
        }
    }

    /// 刷新绕过目录缓存，并等待独立导航栏目的新内容；栏目失败单独反馈，不丢首页。
    public func refreshDiscovery() async throws -> PluginDiscoverySnapshot {
        await invalidateCatalog()
        let home = try await home(refresh: true)
        let items = discoveryNavigation(home)
        let previews = await withTaskGroup(of: (String, PluginNavigationPreview).self) { group in
            for item in items {
                group.addTask {
                    do {
                        let page: PluginBrowse
                        if let value = item.page { page = try await self.browse(value, refresh: true) }
                        else { page = try await self.collection(item.builtin ?? "").0 }
                        return (item.previewKey, PluginNavigationPreview(content: page, error: nil))
                    } catch { return (item.previewKey, PluginNavigationPreview(content: nil, error: error.localizedDescription)) }
                }
            }
            var values: [String: PluginNavigationPreview] = [:]
            for await (key, preview) in group { values[key] = preview }
            return values
        }
        try Task.checkCancellation()
        return PluginDiscoverySnapshot(home: home, previews: previews)
    }
}
