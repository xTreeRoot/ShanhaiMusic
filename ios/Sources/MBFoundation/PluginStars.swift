import Foundation
import libxml2

public struct PluginStarsEntry: Identifiable, Sendable {
    public let repository: String
    public let issue: String?
    let pending: PendingPlugin?
    public var id: String { repository }
    public var manifest: PluginManifest? { pending?.manifest }
}

enum PluginStars {
    static func address(_ input: String) throws -> URL {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.contains("://"), text.split(separator: "/").first?.contains(".") == true { text = "https://" + text }
        guard var components = URLComponents(string: text) else { throw invalidAddress }
        components.fragment = nil
        guard let url = components.url, isWebURL(url) else { throw invalidAddress }
        return url
    }

    private static var invalidAddress: PluginFailure { .invalid(AppLocalization.text("请输入插件仓库或列表网页地址")) }

    private static func isWebURL(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "") && url.host?.isEmpty == false && url.user == nil && url.password == nil
    }

    private static func isGitHub(_ url: URL) -> Bool {
        url.host?.lowercased() == "github.com" && (url.port == nil || url.port == (url.scheme?.lowercased() == "https" ? 443 : 80))
    }

    private static func isStars(_ url: URL) -> Bool {
        let parts = url.path.split(separator: "/")
        return isGitHub(url) && parts.count >= 4 && parts[0] == "stars" && parts[2] == "lists"
    }

    static func repository(_ input: String, relativeTo base: URL? = nil) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !text.hasPrefix("#"), !text.hasPrefix("?") else { return nil }
        if let link = URLComponents(string: text), ["mb", "mygo-music"].contains(link.scheme?.lowercased() ?? ""),
           link.host == "install-plugin", link.user == nil, link.password == nil, link.port == nil,
           link.path.isEmpty || link.path == "/", link.fragment == nil {
            let repos = link.queryItems?.filter { $0.name == "repo" } ?? []
            if repos.count == 1, let repo = repos.first?.value, validRepository(repo) { return PluginInstaller.normalizedRepository(repo) }
            return nil
        }
        if base == nil, text.split(separator: "/").first?.contains(".") == false,
           let repo = PluginInstaller.normalizedRepository(text), validRepository(repo) { return repo }
        let candidate = base != nil ? URL(string: text, relativeTo: base)?.absoluteURL : try? address(text)
        guard let url = candidate, isWebURL(url), isGitHub(url), !isStars(url) else { return nil }
        let parts = url.path.split(separator: "/")
        guard parts.count >= 2, parts.count == 2 || ["releases", "tree", "blob"].contains(parts[2]) else { return nil }
        let repo = parts.prefix(2).joined(separator: "/")
        return validRepository(repo) ? PluginInstaller.normalizedRepository(repo) : nil
    }

    static func repositories(_ input: String) async throws -> [String] {
        if let repository = repository(input) { return [repository] }
        let url = try address(input)
        var request = URLRequest(url: url)
        request.setValue("text/html, */*;q=0.8", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 4 * 1_024 * 1_024, permitting: isWebURL)
        guard let response = response as? HTTPURLResponse else { throw NetworkFailure.response }
        guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
        let page = response.url ?? url
        if let repo = repository(page.absoluteString) { return [repo] }
        return try parse(bytes, relativeTo: page)
    }

    static func parse(_ bytes: Data, relativeTo address: URL) throws -> [String] {
        let options = HTML_PARSE_NONET.rawValue | HTML_PARSE_NOERROR.rawValue | HTML_PARSE_NOWARNING.rawValue
        guard !bytes.isEmpty, bytes.count <= 4 * 1_024 * 1_024,
              let document = bytes.withUnsafeBytes({ htmlReadMemory($0.bindMemory(to: CChar.self).baseAddress, Int32($0.count), nil, "UTF-8", Int32(options)) }) else { throw NetworkFailure.response }
        defer { xmlFreeDoc(document) }

        let stars = isStars(address)
        var start = xmlDocGetRootElement(document)
        if stars {
            var list: xmlNodePtr?
            visit(start) { node in
                if attribute(node, "id") == "user-list-repositories" { list = node; return false }
                return true
            }
            guard let list else { throw PluginFailure.invalid(AppLocalization.text("没有找到 GitHub 星标仓库列表")) }
            start = list.pointee.children
        }
        var result: [String] = [], seen: Set<String> = ["xtreeroot/shanhaimusic", "xtreeroot/mb", "xtreeroot/mb-plugin-template"]
        visit(start) { node in
            guard name(node) == "a", let path = attribute(node, "href") else { return true }
            if stars {
                guard let parent = node.pointee.parent, name(parent) == "h2",
                      attribute(parent, "class")?.split(whereSeparator: \.isWhitespace).contains("h3") == true else { return true }
            }
            if let repo = repository(path, relativeTo: address), seen.insert(repo.lowercased()).inserted { result.append(repo) }
            return result.count <= 32
        }
        guard result.count <= 32 else { throw PluginFailure.invalid(AppLocalization.text("每次最多导入 32 个插件仓库")) }
        guard !result.isEmpty else { throw PluginFailure.invalid(AppLocalization.text("没有找到插件仓库链接")) }
        return result
    }

    private static func validRepository(_ repo: String) -> Bool {
        !repo.contains("..") && !repo.split(separator: "/").contains(".") && repo.range(of: #"\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\z"#, options: .regularExpression) != nil
    }

    private static func name(_ node: xmlNodePtr) -> String? {
        guard node.pointee.type == XML_ELEMENT_NODE, let value = node.pointee.name else { return nil }
        return String(cString: value)
    }

    private static func attribute(_ node: xmlNodePtr, _ key: String) -> String? {
        guard let attribute = xmlHasProp(node, key), let text = attribute.pointee.children,
              text.pointee.type == XML_TEXT_NODE, text.pointee.next == nil, let value = text.pointee.content else { return nil }
        return String(cString: value)
    }

    @discardableResult private static func visit(_ head: xmlNodePtr?, _ body: (xmlNodePtr) -> Bool) -> Bool {
        var next = head
        while let node = next {
            guard body(node), visit(node.pointee.children, body) else { return false }
            next = node.pointee.next
        }
        return true
    }
}
