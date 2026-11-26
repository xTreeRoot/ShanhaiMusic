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

/// 社区目录只读取 GitHub 公开仓库；安装仍走现有暂存和权限确认流程。
public enum PluginCommunity {
    public static func address(query: String, page: Int) -> URL {
        var url = URLComponents(string: "https://api.github.com/search/repositories")!
        url.queryItems = [
            URLQueryItem(name: "q", value: (query.trimmingCharacters(in: .whitespacesAndNewlines) + " topic:mygo-music-plugin archived:false").trimmingCharacters(in: .whitespaces)),
            URLQueryItem(name: "sort", value: "stars"), URLQueryItem(name: "per_page", value: "30"),
            URLQueryItem(name: "page", value: String(max(1, page)))
        ]
        return url.url!
    }

    public static func decode(_ data: Data, page: Int) throws -> PluginCommunityPage {
        struct Answer: Decodable {
            struct Item: Decodable {
                struct Owner: Decodable { let avatar_url: URL? }
                let full_name: String
                let name: String
                let description: String?
                let stargazers_count: Int
                let owner: Owner
            }
            let total_count: Int
            let items: [Item]
        }
        let answer = try JSONDecoder().decode(Answer.self, from: data)
        let plugins = answer.items.map { item in
            CommunityPlugin(repository: item.full_name, name: item.name.replacingOccurrences(of: "(?i)^mygo-music-(plugin-)?", with: "", options: .regularExpression),
                            description: item.description ?? "", avatar: item.owner.avatar_url, stars: item.stargazers_count)
        }
        return PluginCommunityPage(plugins: plugins, more: plugins.count == 30 && max(1, page) * 30 < min(answer.total_count, 1000))
    }

    public static func load(query: String, page: Int) async throws -> PluginCommunityPage {
        var request = URLRequest(url: address(query: query, page: page), timeoutInterval: 20)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 2 * 1024 * 1024)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw PluginFailure.invalid("无法读取社区插件，请稍后重试") }
        return try decode(data, page: page)
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
