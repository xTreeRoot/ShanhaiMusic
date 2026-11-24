import Foundation
@testable import MBFoundation
import Testing

@Test func pluginStarsParsesOnlyRepositoryRows() throws {
    let html = """
    <h2 class="h3"><a href="/owner/aside">Aside</a></h2>
    <div id="user-list-repositories">
      <h2 class="other h3"><a href="/xTreeRoot/mygo-music-soda"><span>owner / </span>soda</a></h2>
      <h2 class="h3"><a href="/XTREEROOT/MYGO-MUSIC-SODA">Duplicate</a></h2>
      <h2 class="h3"><a href="/xTreeRoot/MB">Host app</a></h2>
      <h2 class="h3"><a href="/XTREEROOT/MB-Plugin-Template">开发模板</a></h2>
      <h2 class="h3"><a href="/someone/mb-plugin-template">Same name, different owner</a></h2>
      <h2 class="h3"><a href="/xTreeRoot/mb-plugin-template-extra">Distinct plugin</a></h2>
      <h2 class="h3"><a href="//other.test/other">External</a></h2>
      <p><a href="/owner/not-a-heading">Mention</a></p>
      <h2 class="h3"><a href="/xTreeRoot/mygo-ximalaya">Ximalaya</a></h2>
    </div>
    """
    let stars = try #require(URL(string: "https://github.com/stars/example/lists/plugins"))
    if let fixture = ProcessInfo.processInfo.environment["MB_STARS_HTML_FIXTURE"] {
        let live = try Data(contentsOf: URL(fileURLWithPath: fixture))
        #expect(try PluginStars.parse(live, relativeTo: stars) == ["xTreeRoot/mygo-music-soda", "xTreeRoot/mygo-ximalaya", "xTreeRoot/mygo-music-youtube-music", "xTreeRoot/mygo-music-netease"])
    } else {
        #expect(try PluginStars.parse(Data(html.utf8), relativeTo: stars) == ["xTreeRoot/mygo-music-soda", "someone/mb-plugin-template", "xTreeRoot/mb-plugin-template-extra", "xTreeRoot/mygo-ximalaya"])
    }
    #expect(throws: (any Error).self) { try PluginStars.parse(Data("<h1>Sign in</h1>".utf8), relativeTo: stars) }
}

@Test func pluginCatalogReadsOrdinaryWebPageAndDirectLinks() throws {
    let address = try PluginStars.address(" catalog.test/plugins/?group=audio#details ")
    #expect(address.absoluteString == "https://catalog.test/plugins/?group=audio")
    for input in ["https://github.com@other.test/stars/a/lists/b", "file:///tmp/plugin.zip"] {
        #expect(throws: (any Error).self) { try PluginStars.address(input) }
    }
    let html = """
    <a href="mb://install-plugin?repo=Owner%2FAudio&amp;from=catalog">Install</a>
    <a href="//github.com/owner/audio/releases">Duplicate</a>
    <a href="https://github.com/Other/Video/releases/download/v1/plugin-universal.zip">Download</a>
    <a href="https://github.com.fake.test/Other/Fake">Fake</a>
    """
    #expect(try PluginStars.parse(Data(html.utf8), relativeTo: address) == ["Owner/Audio", "Other/Video"])
    #expect(throws: (any Error).self) { try PluginStars.parse(Data("<h1>No links</h1>".utf8), relativeTo: address) }
    for input in ["Owner/Audio", " github.com/Owner/Audio/releases ", "mb://install-plugin?repo=Owner%2FAudio"] {
        #expect(PluginStars.repository(input) == "Owner/Audio")
    }
}

@Test @MainActor func pluginStarsBatchRequiresReviewAndKeepsData() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let fixture = root.appendingPathComponent("fixture")
    let installer = PluginInstaller(directory: root.appendingPathComponent("plugins"))
    try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
    let entry = fixture.appendingPathComponent("index.js")
    try Data(#"{"id":"fixture","name":"Stars Fixture","permissions":{"network":["fixture.test"]}}"#.utf8).write(to: fixture.appendingPathComponent("plugin.json"))
    try Data("// old revision".utf8).write(to: entry)
    var old = try await installer.prepare(fixture)
    old.repository = "owner/fixture"
    let previous = try await installer.install(old)
    let saved = previous.dataDirectory.appendingPathComponent("account.json")
    let state = Data("synthetic account state".utf8)
    try state.write(to: saved)
    let next = Data("// new revision".utf8)
    try next.write(to: entry)
    let model = PluginModel(installer: installer, starsRepositories: { _ in ["owner/offline", "Owner/Fixture", "other/duplicate"] }) { installer, repo in
        if repo == "owner/offline" { throw NetworkFailure.http(503) }
        var pending = try await installer.prepare(fixture)
        pending.repository = repo
        return pending
    }
    await model.restore()
    await model.reviewStars("fixture")
    let review = try #require(model.starsEntries)
    #expect(review.compactMap(\.manifest).map(\.id) == ["fixture"])
    #expect(review.filter { $0.issue != nil }.count == 2)
    #expect(try Data(contentsOf: previous.directory.appendingPathComponent("index.js")) != next)

    await model.applyStars()
    #expect(model.starsFinished)
    #expect(model.starsEntries?.filter { $0.issue != nil }.count == 2)
    #expect(model.installed.first?.repository == "Owner/Fixture")
    #expect(try Data(contentsOf: previous.directory.appendingPathComponent("index.js")) == next)
    #expect(try Data(contentsOf: saved) == state)
    let staging = root.appendingPathComponent("plugins/pending")
    #expect(try FileManager.default.contentsOfDirectory(atPath: staging.path).isEmpty)
}

@Test func proxyMigrationAddsGitHubWithoutReplacingUserChoices() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    try Data(#"["music.example.test","api.github.com"]"#.utf8).write(to: directory.appendingPathComponent("proxy-hosts.json"))
    let store = AppPersistence(directory: directory)
    let hosts = try #require(try await store.loadProxyHosts())
    let policy = try ProxyPolicy(hosts.joined(separator: "\n"))
    for host in ["music.example.test", "github.com", "api.github.com", "codeload.github.com", "release-assets.githubusercontent.com"] {
        #expect(policy.usesSystemProxy(for: URL(string: "https://" + host)!))
    }
    #expect(!policy.usesSystemProxy(for: URL(string: "https://example.com/github.com")!))
    #expect(hosts.filter { $0 == "api.github.com" }.count == 1)
    try await store.saveProxyHosts([])
    #expect(try await store.loadProxyHosts() == [])
}

@Test func pluginImportAcceptsRepositoryAddressesWithoutFetchingStarsHTML() async throws {
    #expect(try await PluginStars.repositories("https://github.com/owner/repo.git") == ["owner/repo"])
    #expect(try await PluginStars.repositories("owner/repo") == ["owner/repo"])
}
