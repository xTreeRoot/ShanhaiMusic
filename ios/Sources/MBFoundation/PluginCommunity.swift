import Foundation
import Observation

public struct CommunityPlugin: Identifiable, Equatable, Sendable {
    public let repository: String
    public let name: String
    public let description: String
    public let avatar: URL?
    public let stars: Int
    public var id: String { repository.lowercased() }
}

public struct PluginCommunityPage: Sendable {
    public let plugins: [CommunityPlugin]
    public let more: Bool
}

/// 社区只来自山海维护列表；仓库元信息不改变目录成员。
public enum PluginCommunity {
    public static let catalogURL = URL(string: "https://github.com/stars/xTreeRoot/lists/fork-mygo-%E5%B1%B1%E6%B5%B7-%E8%87%AA%E8%A1%8C%E7%BB%B4%E6%8A%A4%E4%BB%93%E5%BA%93-%E5%A4%9A%E5%B9%B3%E5%8F%B0")!

    public static func page(_ catalog: [CommunityPlugin], query: String, page: Int) -> PluginCommunityPage {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = catalog.filter { query.isEmpty || ($0.name + "\n" + $0.repository + "\n" + $0.description).lowercased().contains(query) }
        let start = min((max(1, page) - 1) * 30, filtered.count), end = min(max(1, page) * 30, filtered.count)
        return PluginCommunityPage(plugins: Array(filtered[start..<end]), more: end < filtered.count)
    }

    public static func load(query: String, page: Int) async throws -> PluginCommunityPage {
        let values = try await MaintainedCommunity.shared.load()
        return self.page(values, query: query, page: page)
    }

    fileprivate static func repository(_ repository: String) async -> CommunityPlugin {
        struct Metadata: Decodable {
            struct Owner: Decodable { let avatar_url: URL? }
            let description: String?
            let stargazers_count: Int
            let owner: Owner
        }
        let parts = repository.split(separator: "/"), raw = String(parts.last ?? "")
        let short = raw.replacingOccurrences(of: "(?i)^mygo-music-(plugin-)?", with: "", options: .regularExpression)
        var metadata: Metadata?
        if let url = URL(string: "https://api.github.com/repos/" + repository) {
            var request = URLRequest(url: url, timeoutInterval: 15)
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            if let (data, response) = try? await NetworkClient.shared.data(for: request, maximumBytes: 256 * 1024),
               (response as? HTTPURLResponse)?.statusCode == 200 { metadata = try? JSONDecoder().decode(Metadata.self, from: data) }
        }
        return CommunityPlugin(repository: repository, name: short.isEmpty ? raw : short,
                               description: metadata?.description ?? repository,
                               avatar: metadata?.owner.avatar_url ?? URL(string: "https://github.com/\(parts.first ?? "").png?size=120"),
                               stars: metadata?.stargazers_count ?? 0)
    }
}

private actor MaintainedCommunity {
    static let shared = MaintainedCommunity()
    private var cached: [CommunityPlugin] = []
    private var updated: Date?
    private var request: Task<[CommunityPlugin], any Error>?

    func load() async throws -> [CommunityPlugin] {
        if let updated, Date().timeIntervalSince(updated) < 3600 { return cached }
        if let request { return try await request.value }
        let task = Task {
            let repos = try await PluginStars.repositories(PluginCommunity.catalogURL.absoluteString)
            return await withTaskGroup(of: (Int, CommunityPlugin).self) { group in
                for (index, repo) in repos.enumerated() { group.addTask { (index, await PluginCommunity.repository(repo)) } }
                var values: [(Int, CommunityPlugin)] = []
                for await item in group { values.append(item) }
                return values.sorted { $0.0 < $1.0 }.map(\.1)
            }
        }
        request = task
        do { let values = try await task.value; cached = values; updated = Date(); request = nil; return values }
        catch { request = nil; throw error }
    }
}

@MainActor @Observable public final class PluginCommunityModel {
    public private(set) var plugins: [CommunityPlugin] = []
    public private(set) var loading = false
    public private(set) var more = true
    public private(set) var error: String?
    private var page = 0
    public private(set) var query = ""
    public var hasLoaded: Bool { page > 0 }
    private var generation = 0
    @ObservationIgnored private let fetch: @Sendable (String, Int) async throws -> PluginCommunityPage

    public init(fetch: @escaping @Sendable (String, Int) async throws -> PluginCommunityPage = { try await PluginCommunity.load(query: $0, page: $1) }) { self.fetch = fetch }

    public func search(_ query: String) async {
        generation += 1; self.query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        plugins = []; page = 0; more = true; loading = false; error = nil
        await loadMore()
    }

    public func loadMore() async {
        guard !loading, more else { return }
        loading = true; error = nil
        let gen = generation, next = page + 1, search = query
        do {
            let result = try await fetch(search, next)
            guard gen == generation else { return }
            loading = false
            guard !Task.isCancelled else { return }
            var seen = Set(plugins.map(\.id))
            plugins += result.plugins.filter { seen.insert($0.id).inserted }
            page = next; more = result.more && !result.plugins.isEmpty
        } catch {
            guard gen == generation else { return }
            loading = false
            if !(error is CancellationError) { self.error = error.localizedDescription }
        }
    }
}

extension InstalledPlugin {
    public var iconURL: URL? {
        if let icon = manifest.icon, !icon.isEmpty, !icon.hasPrefix("/"), !icon.contains("\\"), !icon.contains("\0"), !icon.split(separator: "/").contains("..") {
            let base = directory.resolvingSymlinksInPath()
            let file = directory.appendingPathComponent(icon).resolvingSymlinksInPath()
            if file.path.hasPrefix(base.path + "/") { return file }
        }
        guard let repo = repository.flatMap(PluginInstaller.normalizedRepository), let owner = repo.split(separator: "/").first else { return nil }
        return URL(string: "https://github.com/\(owner).png?size=120")
    }
}
