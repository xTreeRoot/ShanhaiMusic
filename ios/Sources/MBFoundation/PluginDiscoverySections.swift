import CryptoKit
import Foundation

extension PluginManifest {
    /// 独立栏目仅引用插件公开路由；宿主在请求前应用开关，不先加载完整首页。
    public struct Discovery: Codable, Identifiable, Sendable {
        public let id: String
        public let title: String
        public let page: String?
        public let builtin: String?
        public let defaultEnabled: Bool?
        public let categories: [String]?
        public var enabledByDefault: Bool { defaultEnabled ?? true }
        public var preferenceSection: DiscoveryPreferences.Section { .init(id: id, defaultEnabled: enabledByDefault) }
    }
    static func validateDiscovery(_ items: [Discovery]) throws {
        let builtins = Set(["playlists", "liked", "recent", "savedAlbums", "shows", "stations"])
        guard items.count <= 128, Set(items.map(\.id)).count == items.count else { throw PluginFailure.invalid("Invalid discovery sections") }
        for item in items {
            guard item.id.range(of: #"\A[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\z"#, options: .regularExpression) != nil,
                  !item.id.contains(".."), !item.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, item.title.utf8.count <= 240,
                  (item.page == nil) != (item.builtin == nil), item.page.map({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf8.count <= 4096 }) ?? builtins.contains(item.builtin ?? ""),
                  (item.categories ?? []).count <= 64, Set(item.categories ?? []).count == (item.categories ?? []).count,
                  (item.categories ?? []).allSatisfy({ !$0.isEmpty && $0.utf8.count <= 4096 }) else { throw PluginFailure.invalid("Invalid discovery section") }
        }
    }
    public var discoverySections: [Discovery] {
        if let discovery, !discovery.isEmpty { return discovery }
        var result = [Discovery(id: "home", title: "首页", page: nil, builtin: "home", defaultEnabled: true, categories: nil)]
        for item in navigation ?? [] where item.placement != .personal {
            guard let route = item.page ?? item.builtin, route != "home", route != "search" else { continue }
            let key = (item.page == nil ? "collection:" : "page:") + route
            let digest = SHA256.hash(data: Data(key.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
            result.append(Discovery(id: "nav-" + digest, title: item.title ?? route, page: item.page, builtin: item.builtin, defaultEnabled: true, categories: nil))
        }
        var seen = Set<String>()
        return result.filter { seen.insert($0.id).inserted }
    }
}

extension PluginSource {
    public func discoveryHome(preferences: DiscoveryPreferences, refresh: Bool = false) async throws -> PluginBrowse {
        if refresh { await invalidateCatalog() }
        let declared = manifest.discoverySections
        let selection = preferences.resolve(plugin: manifest.id, sections: declared.map(\.preferenceSection))
        var result = PluginBrowse(title: displayName)
        for id in selection.order where selection.enabled[id] == true {
            try Task.checkCancellation()
            guard let item = declared.first(where: { $0.id == id }) else { continue }
            // 兼容旧插件的聚合首页；声明独立栏目后完全不调用 home。
            if item.builtin == "home" {
                if try await supports("home") {
                    let home = try await home(refresh: refresh)
                    result.sections += home.sections.filter { row in !result.sections.contains { $0.title == row.title } }
                    result.tabs = home.tabs; result.selectedTab = home.selectedTab
                }
                continue
            }
            if result.sections.contains(where: { $0.morePage == item.page && item.page != nil || $0.title == item.title }) { continue }
            let page: PluginBrowse
            do {
                if let route = item.page { page = try await browse(route, refresh: refresh) }
                else { page = try await collection(item.builtin ?? "").0 }
            } catch is CancellationError { throw CancellationError() }
            catch {
                result.sections.append(.init(id: id, empty: error.localizedDescription, title: AppLocalization.text(item.title), tracks: [], links: [], morePage: item.page))
                continue
            }
            if !page.tabs.isEmpty { result.tabs = page.tabs; result.selectedTab = page.selectedTab }
            let sections = page.sections
            let entities = Array(sections.flatMap(\.entities).prefix(12))
            let tracks = Array(sections.flatMap(\.tracks).prefix(12))
            let links = Array(sections.flatMap(\.links).prefix(12))
            let message = sections.compactMap { $0.empty.isEmpty ? nil : $0.empty }.first ?? ""
            let morePage = item.page
            var section = PluginBrowse.Section(id: id, entities: entities, empty: message,
                trackLayout: sections.first?.trackLayout ?? "", title: AppLocalization.text(item.title),
                tracks: tracks, links: links, morePage: morePage)
            section.moreCollection = item.builtin
            result.sections.append(section)
        }
        return result
    }
}
