import CryptoKit
import Foundation
@testable import MBFoundation
import Testing
import ZIPFoundation

private let backupRecovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
private actor BackupObjects {
    var objects: [String: Data] = [:]
    func reply(_ request: URLRequest) throws -> (Data, URLResponse) {
        let url = try #require(request.url)
        #expect(request.timeoutInterval == 90)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer " + backupRecovery)
        var body = Data(), status = 200
        switch request.httpMethod {
        case "PUT": objects[url.path] = request.httpBody; status = 201
        case "GET": body = try #require(objects[url.path])
        case "DELETE": objects.removeValue(forKey: url.path); status = 204
        default: throw PlatformDataFailure.invalid
        }
        return (body, try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)))
    }
    func count() -> Int { objects.count }
    func tamper() { for (key, _) in objects { objects[key] = Data("{}".utf8); break } }
}

@Test func deviceBackupTransferAuthenticatesMultipleChunksAndCleansObjects() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let input = root.appendingPathComponent("source.zip"), output = root.appendingPathComponent("restored.zip")
    let plain = Data(repeating: 73, count: (2 << 20) + 123)
    try plain.write(to: input)
    let fixture = BackupObjects()
    let client = PlatformDataClient { request, _ in try await fixture.reply(request) }
    let node = PlatformNode(origin: try PlatformDataClient.origin("fixture.test"), grant: .init(device: "AAECAwQFBgcICQoLDA0ODw", space: "test-space",
        token: backupRecovery, recovery: backupRecovery, key: backupRecovery, expires: .max))
    let transfer = NodeDeviceBackupTransfer(node: node, client: client)
    let ref = try await transfer.upload(file: input, platform: "ios", plugins: 2)
    #expect(await fixture.count() == 3)
    let owner = node.grant.device.utf8.map { String(format: "%02x", $0) }.joined()
    try await transfer.download(owner: owner, reference: ref, to: output)
    #expect(try Data(contentsOf: output) == plain)
    try await transfer.delete(reference: ref)
    #expect(await fixture.count() == 0)
    let second = try await transfer.upload(file: input, platform: "ios", plugins: 2)
    await fixture.tamper()
    await #expect(throws: (any Error).self) { try await transfer.download(owner: owner, reference: second, to: root.appendingPathComponent("bad.zip")) }
}

@Test func blankDeviceRestoresPluginPackagePrivateDataPresentationAndMediaReferences() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let source = root.appendingPathComponent("source")
    try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
    try Data(#"{"id":"fixture","name":"Fixture","version":"1.0.0","settings":[{"key":"mode","type":"string","label":"Mode"}]}"#.utf8).write(to: source.appendingPathComponent("plugin.json"))
    try Data("export function search(){return {tracks:[]};}".utf8).write(to: source.appendingPathComponent("index.js"))
    let original = PluginInstaller(directory: root.appendingPathComponent("original"))
    let installed = try await original.install(original.prepare(source))
    try await original.saveSettings(["mode": "complete"], for: installed)
    try Data(#"{"session":"synthetic"}"#.utf8).write(to: installed.dataDirectory.appendingPathComponent("storage.json"))
    try await original.rename(installed.id, to: "我的插件")
    try await original.setEnabled(installed.id, enabled: false)
    let (records, entries) = try await original.deviceBackupPlugins(to: root)
    #expect(entries.contains { $0.name == "plugins/fixture/data/storage.json" }, "Entries: \(entries.map(\.name))")
    var manifest = DeviceArchiveManifest(platform: "android", plugins: records)
    manifest.pluginPreferences = try await original.pluginPreferences()
    let mediaFile = root.appendingPathComponent("sample.mp3")
    try Data("synthetic media".utf8).write(to: mediaFile)
    let local = Track(sourceID: "local", sourceTrackID: "old-path-id", title: "原标题", artist: "艺人", durationMilliseconds: 1234)
    manifest.library.tracks = [DeviceArchiveTrack(local, asset: "media/sample.mp3", kind: "imported")]
    manifest.library.favorites = [local.id]
    manifest.library.playlists = [DeviceArchivePlaylist(id: "playlist", name: "收藏歌单", tracks: [local.id])]
    manifest.library.queue = DeviceArchiveQueue(tracks: [DeviceArchiveTrack(local)], index: 0, position: 321, shuffled: false, repeat: "one")
    let file = root.appendingPathComponent("device.zip")
    try await DeviceArchiveFile().write(manifest, entries: entries + [DeviceArchiveEntry(name: "media/sample.mp3", file: mediaFile)], to: file, scratch: root)
    let target = PluginInstaller(directory: root.appendingPathComponent("target"))
    let media = MediaLibrary(directory: root.appendingPathComponent("media"))
    let library = LocalLibraryStore(directory: root.appendingPathComponent("library"))
    let operation = root.appendingPathComponent("operation")
    try FileManager.default.createDirectory(at: operation, withIntermediateDirectories: true)
    let denied = root.appendingPathComponent("denied")
    try FileManager.default.createDirectory(at: denied, withIntermediateDirectories: true)
    await #expect(throws: CancellationError.self) {
        try await NodeDeviceBackupCoordinator(root: root).apply(archive: file, in: denied, plugins: target, media: media, library: library, stillCurrent: { false })
    }
    #expect(try await target.installed().isEmpty)
    let result = try await NodeDeviceBackupCoordinator(root: root).apply(archive: file, in: operation, plugins: target, media: media, library: library, stillCurrent: { true })
    let plugin = try #require(await target.installed().first)
    #expect(try await target.settings(for: plugin)["mode"] == "complete")
    #expect(try String(contentsOf: plugin.dataDirectory.appendingPathComponent("storage.json"), encoding: .utf8) == #"{"session":"synthetic"}"#)
    #expect(try await target.disabled().contains("fixture"))
    #expect(try await target.presentation().names["fixture"] == "我的插件")
    let saved = try #require(await media.list().first)
    #expect(saved.track.title == "原标题" && saved.track.localFileID != nil)
    let state = try await library.snapshot()
    #expect(state.favorites == [saved.track.id] && state.playlists.first?.trackIDs == [saved.track.id])
    #expect(result.session?.queue.first?.id == saved.track.id && result.session?.repeatMode == .one && result.session?.positionMilliseconds == 321)
    // 再次恢复不覆盖当前机器已有账号，也不重复安装。
    try Data(#"{"session":"current"}"#.utf8).write(to: plugin.dataDirectory.appendingPathComponent("storage.json"))
    try await target.restoreDevicePlugins(manifest, from: operation.appendingPathComponent("content"))
    #expect(try await target.installed().count == 1)
    #expect(try String(contentsOf: plugin.dataDirectory.appendingPathComponent("storage.json"), encoding: .utf8) == #"{"session":"current"}"#)
}

@Test func deviceArchiveRejectsTraversalAndMissingPackage() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    for (index, path) in ["../escape", "/absolute", "plugins\\fixture"].enumerated() {
        let file = root.appendingPathComponent("bad-\(index).zip")
        do {
            let zip = try Archive(url: file, accessMode: .create)
            let data = Data("bad".utf8)
            try zip.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count), provider: { position, size in data.subdata(in: Int(position)..<min(data.count, Int(position) + size)) })
        }
        await #expect(throws: (any Error).self) { try await DeviceArchiveFile().extract(file, to: root.appendingPathComponent("stage-\(index)")) }
    }
    let manifest = DeviceArchiveManifest(platform: "android", plugins: [DeviceArchivePlugin(id: "fixture", enabled: true, settings: [:])])
    let missing = root.appendingPathComponent("missing.zip")
    try await DeviceArchiveFile().write(manifest, entries: [], to: missing, scratch: root)
    await #expect(throws: (any Error).self) { try await DeviceArchiveFile().extract(missing, to: root.appendingPathComponent("missing-stage")) }
    #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("missing-stage").path))
}
