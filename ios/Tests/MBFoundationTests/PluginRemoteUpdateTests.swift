import Foundation
@testable import MBFoundation
import Testing
import ZIPFoundation

@Test(arguments: [false, true]) func githubInstallationUsesUniversalReleaseOrPinnedDefaultBranch(_ universal: Bool) async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let sha = String(repeating: "b", count: 40)
    let zip = root.appendingPathComponent("remote.zip")
    do {
        let archive = try Archive(url: zip, accessMode: .create)
        for (path, content) in ["repo/plugin.json": #"{"id":"fixture","name":"Remote","version":"0.4.0"}"#, "repo/index.js": "// latest"] {
            let bytes = Data(content.utf8)
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(bytes.count), provider: { start, count in bytes.subdata(in: Int(start)..<min(bytes.count, Int(start) + count)) })
        }
    }
    let bytes = try Data(contentsOf: zip)
    let installer = PluginInstaller(directory: root.appendingPathComponent("plugins")) { request, _ in
        let url = try #require(request.url)
        let body: Data
        switch url.path {
        case "/repos/Owner/Fixture/releases/latest":
            let asset = universal ? "plugin-universal.zip" : "old-plugin.zip"
            body = Data("""
            {"tag_name":"v0.4.0","body":"统一发行包","assets":[{"name":"\(asset)","id":42}]}
            """.utf8)
        case "/Owner/Fixture/releases/download/v0.4.0/plugin-universal.zip" where universal: body = bytes
        case "/repos/Owner/Fixture/commits/HEAD" where !universal: body = Data("{\"sha\":\"\(sha)\",\"commit\":{\"message\":\"修复推荐切换与分页\"}}".utf8)
        case "/repos/Owner/Fixture/zipball/" + sha where !universal: body = bytes
        default: Issue.record("Unexpected request: \(url.path)"); throw NetworkFailure.response
        }
        return (body, HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
    let pending = try await installer.prepareGitHub("https://github.com/Owner/Fixture.git")
    #expect(pending.repository == "Owner/Fixture")
    #expect(pending.releaseNotes == (universal ? "统一发行包" : "修复推荐切换与分页"))
    #expect(pending.revision == (universal ? "asset-42" : sha) && pending.manifest.version == "0.4.0")
}

@Test @MainActor func updateAllComparesRemoteRevisionKeepsDisabledDataAndContinuesAfterFailure() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root.appendingPathComponent("plugins"))
    let fixture = root.appendingPathComponent("fixture")
    try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
    func write(_ id: String, network: [String] = []) throws {
        try JSONSerialization.data(withJSONObject: ["id": id, "name": id, "version": "1.0.0", "permissions": ["network": network]])
            .write(to: fixture.appendingPathComponent("plugin.json"))
        try Data("// remote code".utf8).write(to: fixture.appendingPathComponent("index.js"))
    }
    for id in ["changed", "same", "offline", "permissions", "wrong"] {
        try write(id)
        var pending = try await installer.prepare(fixture)
        pending.repository = "https://github.com/owner/" + id
        pending.revision = String(repeating: "a", count: 40)
        let plugin = try await installer.install(pending)
        try Data("private fixture".utf8).write(to: plugin.dataDirectory.appendingPathComponent("storage.json"))
    }
    try await installer.setEnabled("changed", enabled: false)
    let model = PluginModel(installer: installer, githubPreparation: { installer, repo in
        let id = String(repo.split(separator: "/").last!)
        if id == "offline" { throw NetworkFailure.http(503) }
        try write(id == "wrong" ? "unrelated" : id, network: id == "permissions" ? ["new.test"] : [])
        var pending = try await installer.prepare(fixture)
        pending.repository = repo
        pending.revision = String(repeating: id == "same" ? "a" : "b", count: 40)
        pending.releaseNotes = "Fixed refresh and pagination"
        return pending
    })
    await model.restore()
    await model.updateAll()
    #expect(model.installed.first { $0.id == "changed" }?.revision == String(repeating: "b", count: 40))
    #expect(model.installed.first { $0.id == "same" }?.revision == String(repeating: "a", count: 40))
    #expect(model.disabled.contains("changed") && model.sources["changed"] == nil)
    #expect(model.updates["permissions"] == "1.0.0")
    #expect(model.updateNotes["changed"] == "Fixed refresh and pagination")
    #expect(model.updateIssues["offline"] != nil && model.updateIssues["wrong"] != nil)
    #expect(model.installed.count == 5)
    for plugin in model.installed {
        #expect(try Data(contentsOf: plugin.dataDirectory.appendingPathComponent("storage.json")) == Data("private fixture".utf8))
    }
    #expect(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("plugins/pending").path).isEmpty)
}
