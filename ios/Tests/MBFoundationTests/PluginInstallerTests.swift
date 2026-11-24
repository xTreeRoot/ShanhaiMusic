import Foundation
@testable import MBFoundation
import Testing
import ZIPFoundation

private func writePlugin(to root: URL, version: String) throws {
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try Data("{\"id\":\"fixture\",\"name\":\"Fixture\",\"version\":\"\(version)\",\"permissions\":{\"network\":[\"*.example.test\"]}}".utf8).write(to: root.appendingPathComponent("plugin.json"))
    try "export function search(){return {tracks:[]};}".write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
}

@Test func pluginInstallDirectoryAndZipUpdatePreservesSettingsAndStorage() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let input = root.appendingPathComponent("input")
    try writePlugin(to: input, version: "1.0.0")
    let installer = PluginInstaller(directory: root.appendingPathComponent("installed"))
    let pending = try await installer.prepare(input)
    #expect(try await installer.installed().isEmpty)
    let plugin = try await installer.install(pending)
    try await installer.saveSettings(["token": "private-test-token"], for: plugin)
    try Data(#"{"session":"fixture"}"#.utf8).write(to: plugin.dataDirectory.appendingPathComponent("storage.json"))
    try writePlugin(to: input, version: "1.1.0")
    let archiveURL = root.appendingPathComponent("update.zip")
    do {
        let archive = try Archive(url: archiveURL, accessMode: .create)
        try archive.addEntry(with: "repo/plugin.json", fileURL: input.appendingPathComponent("plugin.json"), compressionMethod: .deflate)
        try archive.addEntry(with: "repo/index.js", fileURL: input.appendingPathComponent("index.js"), compressionMethod: .deflate)
    }
    let updated = try await installer.install(installer.prepare(archiveURL))
    #expect(updated.manifest.version == "1.1.0")
    #expect(try await installer.settings(for: updated) == ["token": "private-test-token"])
    #expect(try Data(contentsOf: updated.dataDirectory.appendingPathComponent("storage.json")) == Data(#"{"session":"fixture"}"#.utf8))
    let list = try await installer.installed()
    #expect(list.count == 1 && list[0].manifest.version == "1.1.0")
    try await installer.remove(updated)
    #expect(try await installer.installed().isEmpty)
    #expect(FileManager.default.fileExists(atPath: updated.dataDirectory.appendingPathComponent("storage.json").path))
}

@Test func pluginInstallRejectsZipTraversalSymlinksAndAmbiguousRoot() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    let installer = PluginInstaller(directory: root.appendingPathComponent("installed"))
    for (index, path) in ["../escape.js", "/absolute.js", "repo/../../escape.js"].enumerated() {
        let zip = root.appendingPathComponent("bad\(index).zip"), bytes = Data("bad".utf8)
        do {
            let archive = try Archive(url: zip, accessMode: .create)
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(bytes.count), provider: { position, size in bytes.subdata(in: Int(position)..<min(bytes.count, Int(position) + size)) })
        }
        await #expect(throws: PluginFailure.self) { try await installer.prepare(zip) }
    }
    let input = root.appendingPathComponent("input")
    try writePlugin(to: input, version: "1.0.0")
    try FileManager.default.createSymbolicLink(at: input.appendingPathComponent("linked.js"), withDestinationURL: input.appendingPathComponent("index.js"))
    await #expect(throws: PluginFailure.self) { try await installer.prepare(input) }
}

private actor RouterFixture: PlaybackSource, VideoSource {
    nonisolated let displayName: String
    init(_ name: String) { displayName = name }
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) -> MediaResource { MediaResource(url: URL(string: "https://\(displayName).test/\(trackID)")!, headers: [:], fileExtension: "mp3") }
    func video(trackID: String) -> MediaResource { audio(trackID: trackID) }
    func captions(trackID: String) -> CaptionTimeline { CaptionTimeline(lines: [CaptionLine(startMilliseconds: 0, endMilliseconds: 1000, text: displayName)]) }
}
@Test func mixedQueueRoutesEachTrackThroughItsOwnSource() async throws {
    let router = SourceRouter()
    await router.register(RouterFixture("one"), id: "one")
    await router.register(RouterFixture("two"), id: "two")
    for id in ["one", "two"] {
        let track = Track(sourceID: id, sourceTrackID: "same-id", title: id, artist: "")
        #expect(try await router.audio(track: track).url.host == id + ".test")
        #expect(try await router.captions(track: track).lines.first?.text == id)
        #expect(await router.supportsVideo(track: track))
    }
    await router.remove("one")
    let missing = Track(sourceID: "one", sourceTrackID: "same-id", title: "", artist: "")
    #expect(await !router.supportsVideo(track: missing))
    await #expect(throws: PluginFailure.self) { try await router.audio(track: missing) }
}

@Test func githubPluginAssetsPreferIOSAndRejectDesktopOnlyZips() {
    #expect(PluginInstaller.assetIndex(["plugin-darwin-arm64.zip", "plugin-ios-arm64.zip", "plugin.zip"]) == 1)
    #expect(PluginInstaller.assetIndex(["plugin-linux-arm64.zip", "plugin.zip"]) == 1)
    #expect(PluginInstaller.assetIndex(["plugin-darwin-arm64.zip", "plugin-windows-amd64.zip"]) == nil)
}

@Test @MainActor func pluginDisableEnableSurvivesReopenAndKeepsCodeAndSettings() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let input = root.appendingPathComponent("input"), folder = root.appendingPathComponent("plugins")
    try writePlugin(to: input, version: "1.0.0")
    let installer = PluginInstaller(directory: folder)
    let plugin = try await installer.install(installer.prepare(input))
    let model = PluginModel(installer: installer)
    await model.restore()
    #expect(model.sources[plugin.id] != nil)
    await model.setEnabled(plugin, enabled: false)
    #expect(model.sources[plugin.id] == nil && model.disabled.contains(plugin.id))
    let reopened = PluginModel(installer: PluginInstaller(directory: folder))
    await reopened.restore()
    #expect(reopened.sources[plugin.id] == nil && reopened.installed.count == 1)
    await reopened.setEnabled(plugin, enabled: true)
    #expect(reopened.sources[plugin.id] != nil)
}

@Test func installLinksAcceptOnlyKnownSchemesAndSingleValidRepository() {
    #expect(PluginInstaller.repository(fromInstallURL: URL(string: "mb://install-plugin?repo=owner/repo")!) == "owner/repo")
    #expect(PluginInstaller.repository(fromInstallURL: URL(string: "mygo-music://install-plugin?repo=owner/repo")!) == "owner/repo")
    for text in ["https://install-plugin?repo=owner/repo", "mb://other?repo=owner/repo", "mb://install-plugin?repo=../repo", "mb://install-plugin?repo=a/b&repo=c/d"] {
        #expect(PluginInstaller.repository(fromInstallURL: URL(string: text)!) == nil)
    }
}

@Test func pluginUpdateVersionComparisonUsesNumericComponents() {
    #expect(PluginInstaller.isNewer("v1.10.0", than: "1.9.0"))
    #expect(!PluginInstaller.isNewer("v1.0.0", than: "1.0.0"))
    #expect(!PluginInstaller.isNewer("1.9.0", than: "1.10.0"))
}

@Test func installedPluginNormalizesLegacyFullGitHubOriginWithoutChangingPrivateData() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let input = root.appendingPathComponent("input")
    try writePlugin(to: input, version: "0.4.0")
    let installer = PluginInstaller(directory: root.appendingPathComponent("plugins"))
    let pending = try await installer.prepare(input)
    let plugin = try await installer.install(pending)
    try Data(#"{"repository":"https://github.com/xTreeRoot/mygo-music-soda","revision":"4532285445ab259e58b2607a7183546d1329187a"}"#.utf8)
        .write(to: plugin.directory.appendingPathComponent(".mb-origin.json"))
    let privateFile = plugin.dataDirectory.appendingPathComponent("storage.json")
    try Data("private fixture".utf8).write(to: privateFile)
    let reopened = try #require(try await installer.installed().first)
    #expect(reopened.repository == "xTreeRoot/mygo-music-soda")
    #expect(try Data(contentsOf: privateFile) == Data("private fixture".utf8))
    #expect(!PluginInstaller.isNewer("v0.2.2", than: reopened.manifest.version!))
}

@Test func githubOriginNormalizationRejectsForeignHostsAndExtraPaths() {
    #expect(PluginInstaller.normalizedRepository(" https://github.com/xTreeRoot/MB.git/ ") == "xTreeRoot/MB")
    for input in ["https://github.com.evil.test/a/b", "https://user@github.com/a/b", "https://github.com/a/b/tree/main", "../b", "https://github.com/a/b?x=y"] {
        #expect(PluginInstaller.normalizedRepository(input) == nil)
    }
}

@Test func remotePluginRenameKeepsTheExistingDefaultDisplayOrder() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let input = root.appendingPathComponent("input")
    let installer = PluginInstaller(directory: root.appendingPathComponent("store"))
    func named(_ id: String, _ name: String) throws {
        try writePlugin(to: input, version: "1.0.0")
        try Data("{\"id\":\"\(id)\",\"name\":\"\(name)\"}".utf8).write(to: input.appendingPathComponent("plugin.json"))
    }
    try named("one", "Alpha"); _ = try await installer.install(installer.prepare(input))
    try named("two", "Middle"); _ = try await installer.install(installer.prepare(input))
    var display = PluginPresentation(); try display.rename("one", to: "本地名称")
    try await installer.savePresentation(display)
    try named("one", "Zulu"); _ = try await installer.install(installer.prepare(input))
    let restored = try await installer.presentation()
    #expect(restored.sorted(try await installer.installed()).map(\.id) == ["one", "two"])
    #expect(restored.name(id: "one", fallback: "Zulu") == "本地名称")
}
