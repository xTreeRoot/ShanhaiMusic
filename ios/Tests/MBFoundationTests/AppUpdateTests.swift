import CryptoKit
import Foundation
@testable import MBFoundation
import Testing

private func releaseJSON(version: String = "0.1.0", build: Int = 27) -> [String: Any] {
    let tag = "mb-ios-v\(version)-build\(build)"
    let name = "MB-iOS-v\(version)-build\(build)-unsigned.ipa"
    return ["tag_name": tag, "html_url": "https://github.com/xTreeRoot/MB/releases/tag/\(tag)", "body": "Fixture notes",
            "draft": false, "prerelease": false, "assets": [["name": name, "browser_download_url": "https://github.com/xTreeRoot/MB/releases/download/\(tag)/\(name)",
                "size": 3, "digest": "sha256:" + String(repeating: "a", count: 64)]]]
}
private func updateRelease(version: String = "0.1.0", build: Int = 27) throws -> AppRelease {
    try AppRelease.latest(in: JSONSerialization.data(withJSONObject: [releaseJSON(version: version, build: build)]))
}

@Test func appReleaseSelectsIOSBuildNumericallyAmongMixedPlatformReleases() throws {
    var android = releaseJSON(version: "9.0.0"); android["tag_name"] = "mb-android-v9.0.0"
    var draft = releaseJSON(build: 100); draft["draft"] = true
    let data = try JSONSerialization.data(withJSONObject: [releaseJSON(build: 9), android, releaseJSON(build: 27), draft])
    let release = try AppRelease.latest(in: data)
    #expect(release.build == 27)
    #expect(release.isNewer(than: "0.1.0", build: 26))
    #expect(!release.isNewer(than: "0.1.0", build: 27))
    #expect(!release.isNewer(than: "0.1.1", build: 1))
    #expect(try updateRelease(version: "0.1.10").isNewer(than: "0.1.9", build: 100))
}

@Test func appReleaseRejectsWrongOriginsDigestsSizesAndDuplicateAssets() throws {
    for (key, value): (String, Any) in [("digest", "aaaa"), ("size", 0), ("size", VerifiedDownload.maximumBytes + 1),
        ("browser_download_url", "https://github.com.evil.test/xTreeRoot/MB/releases/a.ipa"),
        ("browser_download_url", "http://github.com/xTreeRoot/MB/releases/a.ipa"),
        ("browser_download_url", "https://github.com/other/MB/releases/a.ipa")] {
        var release = releaseJSON()
        var assets = try #require(release["assets"] as? [[String: Any]])
        assets[0][key] = value; release["assets"] = assets
        let data = try JSONSerialization.data(withJSONObject: [release])
        #expect(throws: AppUpdateFailure.self) { try AppRelease.latest(in: data) }
    }
    var release = releaseJSON()
    let assets = try #require(release["assets"] as? [[String: Any]])
    release["assets"] = assets + assets
    let data = try JSONSerialization.data(withJSONObject: [release])
    #expect(throws: AppUpdateFailure.self) { try AppRelease.latest(in: data) }
    #expect(throws: (any Error).self) { try AppRelease.latest(in: Data("<html>error</html>".utf8)) }
}

@Test func updateDownloadVerifiesChunkedBytesAndRejectsTruncationCorruptionAndExcess() throws {
    let bytes = Data((0..<130_000).map { UInt8($0 % 251) })
    let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    var valid = try VerifiedDownload(size: bytes.count, sha256: hash)
    try valid.consume(bytes.prefix(64 << 10)); try valid.consume(bytes.dropFirst(64 << 10)); try valid.finish()
    #expect(valid.written == bytes.count)
    var truncated = try VerifiedDownload(size: bytes.count, sha256: hash)
    try truncated.consume(bytes.dropLast())
    #expect(throws: AppUpdateFailure.self) { try truncated.finish() }
    var oversized = try VerifiedDownload(size: bytes.count - 1, sha256: hash)
    #expect(throws: AppUpdateFailure.self) { try oversized.consume(bytes) }
    #expect(oversized.written == 0)
    var wrong = try VerifiedDownload(size: bytes.count, sha256: String(repeating: "a", count: 64))
    try wrong.consume(bytes)
    #expect(throws: AppUpdateFailure.self) { try wrong.finish() }
}

@MainActor @Test func appUpdateModelChecksDownloadsAndRetainsOnlyVerifiedHandoffFile() async throws {
    let release = try updateRelease()
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".ipa")
    try Data("fixture".utf8).write(to: file)
    defer { try? FileManager.default.removeItem(at: file) }
    let model = AppUpdateModel(version: "0.1.0", build: 26, fetch: { release }, receive: { _, progress in progress(1); return file })
    await model.check()
    #expect(model.release == release && model.checked && !model.busy)
    await model.download()
    #expect(model.file == file && model.progress == 1 && !model.busy)
    let current = AppUpdateModel(version: "0.1.0", build: 27, fetch: { release }, receive: { _, _ in file })
    await current.check()
    #expect(current.checked && current.release == nil)
}

private actor UpdateGate<Value: Sendable> {
    var pending: CheckedContinuation<Value, Never>?
    var started: CheckedContinuation<Void, Never>?
    func value() async -> Value {
        await withCheckedContinuation { pending = $0; started?.resume(); started = nil }
    }
    func waitForRequest() async {
        if pending != nil { return }
        await withCheckedContinuation { started = $0 }
    }
    func resolve(_ value: Value) { pending?.resume(returning: value); pending = nil }
}

@MainActor @Test func cancellingAppCheckIgnoresLateRemoteResponse() async throws {
    let gate = UpdateGate<AppRelease>()
    let model = AppUpdateModel(version: "0.1.0", build: 26, fetch: { await gate.value() }, receive: { _, _ in URL(filePath: "/unused") })
    let check = Task { await model.check() }
    await gate.waitForRequest(); model.cancel()
    await gate.resolve(try updateRelease()); await check.value
    #expect(!model.checked && model.release == nil && !model.busy && model.error == nil)
}

@MainActor @Test func cancellingAppDownloadDeletesLateHandoffFile() async throws {
    let gate = UpdateGate<URL>()
    let release = try updateRelease()
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".ipa")
    try Data("fixture".utf8).write(to: file)
    defer { try? FileManager.default.removeItem(at: file) }
    let model = AppUpdateModel(version: "0.1.0", build: 26, fetch: { release }, receive: { _, _ in await gate.value() })
    await model.check()
    let download = Task { await model.download() }
    await gate.waitForRequest(); model.cancel()
    await gate.resolve(file); await download.value
    #expect(model.file == nil && !model.busy && !FileManager.default.fileExists(atPath: file.path))
}

@MainActor @Test func appUpdateFailureKeepsRetryAvailableWithoutClaimingLatest() async {
    let model = AppUpdateModel(version: "0.1.0", build: 26, fetch: { throw NetworkFailure.http(503) }, receive: { _, _ in URL(filePath: "/unused") })
    await model.check()
    #expect(!model.checked && !model.busy && model.error != nil)
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_LIVE_APP_UPDATE"] == "1"))
func officialAppReleaseCanDownloadAndVerifyItsPublishedIPA() async throws {
    let release = try await AppUpdates.latest()
    let file = try await AppUpdates.download(release) { _ in }
    defer { try? FileManager.default.removeItem(at: file) }
    #expect(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize == release.size)
    print("Verified official iOS release: \(release.tag), \(release.size) bytes")
}

@Test func renamedRepositorySupportsExactOldAndNewPathsOnly() throws {
    for repo in ["MB", "ShanhaiMusic", "ShanhaiMusic-evil"] {
        var release = releaseJSON(build: 29)
        let tag = "mb-ios-v0.1.0-build29", name = "MB-iOS-v0.1.0-build29-unsigned.ipa"
        release["html_url"] = "https://github.com/xTreeRoot/\(repo)/releases/tag/\(tag)"
        var assets = try #require(release["assets"] as? [[String: Any]])
        assets[0]["browser_download_url"] = "https://github.com/xTreeRoot/\(repo)/releases/download/\(tag)/\(name)"; release["assets"] = assets
        let data = try JSONSerialization.data(withJSONObject: [release])
        if repo.hasSuffix("-evil") { #expect(throws: AppUpdateFailure.self) { try AppRelease.latest(in: data) } }
        else { #expect(try AppRelease.latest(in: data).build == 29) }
    }
}

private actor AutomaticUpdateRequests {
    var count = 0
    func fetch(_ release: AppRelease) -> AppRelease { count += 1; return release }
}

@MainActor @Test func disabledAutomaticChecksSendNoRequestButManualCheckStillWorks() async throws {
    let requests = AutomaticUpdateRequests(), release = try updateRelease()
    let model = AppUpdateModel(version: "0.1.0", build: 26, fetch: { await requests.fetch(release) }, receive: { _, _ in URL(filePath: "/unused") })
    #expect(await model.checkAutomatically(enabled: false) == false)
    #expect(await requests.count == 0)
    #expect(await model.checkAutomatically(enabled: true))
    #expect(await requests.count == 1)
    #expect(await model.checkAutomatically(enabled: false) == false)
    #expect(await requests.count == 1)
    await model.check()
    #expect(await requests.count == 2 && model.release == release)
}
