import Foundation
import Testing
@testable import MBFoundation

private actor CommunityReplies {
    var fail = true
    var old: CheckedContinuation<PluginCommunityPage, Never>?
    var waiting = false
    func fetch(_ query: String, _ page: Int) async throws -> PluginCommunityPage {
        if query == "old" { return await withCheckedContinuation { old = $0; waiting = true } }
        if page == 2 && fail { fail = false; throw PluginFailure.invalid("offline") }
        let first = CommunityPlugin(repository: "owner/one", name: "One", description: "", avatar: nil, stars: 1)
        let second = CommunityPlugin(repository: "owner/two", name: "Two", description: "", avatar: nil, stars: 0)
        return PluginCommunityPage(plugins: page == 1 ? [first] : [first, second], more: page == 1)
    }
    func finishOld() { old?.resume(returning: PluginCommunityPage(plugins: [], more: false)); old = nil }
}

@Test @MainActor func communityPaginationRetriesWithoutLosingInstalledPage() async {
    let replies = CommunityReplies()
    let model = PluginCommunityModel { try await replies.fetch($0, $1) }
    await model.search("radio")
    #expect(model.plugins.map(\.repository) == ["owner/one"])
    await model.loadMore()
    #expect(model.error != nil && model.plugins.count == 1 && model.more)
    await model.loadMore()
    #expect(model.plugins.map(\.repository) == ["owner/one", "owner/two"])
    #expect(model.error == nil && !model.more)
}

@Test @MainActor func communityDropsAnOlderSearchThatFinishesLast() async {
    let replies = CommunityReplies()
    let model = PluginCommunityModel { try await replies.fetch($0, $1) }
    let old = Task { await model.search("old") }
    while !(await replies.waiting) { await Task.yield() }
    await model.search("new")
    await replies.finishOld(); await old.value
    #expect(model.plugins.map(\.repository) == ["owner/one"])
    #expect(!model.loading && model.more)
}

@Test func communityRequestAndPageLimitsMatchGitHubContract() throws {
    let url = PluginCommunity.address(query: " 中文 & radio ", page: 0)
    let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    #expect(query.first(where: { $0.name == "q" })?.value == "中文 & radio topic:mygo-music-plugin archived:false")
    #expect(query.first(where: { $0.name == "page" })?.value == "1")
    let item: [String: Any] = ["full_name": "owner/radio", "name": "mygo-music-plugin-radio", "description": NSNull(), "stargazers_count": 2, "owner": [:]]
    let data = try JSONSerialization.data(withJSONObject: ["total_count": 5000, "items": Array(repeating: item, count: 30)])
    #expect(try PluginCommunity.decode(data, page: 33).more)
    #expect(try !PluginCommunity.decode(data, page: 34).more)
    #expect(try PluginCommunity.decode(data, page: 1).plugins.first?.name == "radio")
}

@Test func pluginIconsStayInsideTheirCodeDirectory() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    for icon in ["art/icon.png", "../outside.png", "/outside.png", "bad\\icon.png"] {
        let data = try JSONSerialization.data(withJSONObject: ["id": "fixture", "icon": icon])
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: data)
        let plugin = InstalledPlugin(manifest: manifest, directory: root, dataDirectory: root)
        if icon == "art/icon.png" { #expect(plugin.iconURL?.path == root.appendingPathComponent(icon).path) }
        else { #expect(plugin.iconURL == nil) }
    }
}
