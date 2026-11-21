import Foundation
import Observation

public enum AppUpdates {
    public static func latest() async throws -> AppRelease {
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/xTreeRoot/ShanhaiMusic/releases?per_page=100")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 4 << 20,
            acceptingBody: { $0 == 200 }, permitting: { $0.scheme == "https" && $0.host == "api.github.com" })
        guard let http = response as? HTTPURLResponse else { throw NetworkFailure.response }
        guard http.statusCode == 200 else { throw NetworkFailure.http(http.statusCode) }
        return try AppRelease.latest(in: data)
    }

    public static func download(_ release: AppRelease, progress: @escaping @Sendable (Double) -> Void) async throws -> URL {
        var request = URLRequest(url: release.archive)
        request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
        let file = try await NetworkClient.shared.downloadVerifiedFile(for: request, size: release.size, sha256: release.sha256,
            progress: progress, permitting: { url in
                url.scheme == "https" && url.user == nil && url.password == nil && url.port == nil &&
                    ["github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"].contains(url.host ?? "")
            })
        defer { try? FileManager.default.removeItem(at: file) }
        try Task.checkCancellation()
        let directory = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("MBUpdates", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent("MB-iOS-v\(release.version)-build\(release.build)-unsigned.ipa")
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileManager.default.moveItem(at: file, to: target)
        return target
    }
}

@MainActor @Observable public final class AppUpdateModel {
    public let currentVersion: String
    public let currentBuild: Int
    public private(set) var release: AppRelease?
    public private(set) var checked = false
    public private(set) var checking = false
    public private(set) var downloading = false
    public private(set) var progress = 0.0
    public private(set) var file: URL?
    public private(set) var error: String?
    public var busy: Bool { checking || downloading }
    @ObservationIgnored private let fetch: @Sendable () async throws -> AppRelease
    @ObservationIgnored private let receive: @Sendable (AppRelease, @escaping @Sendable (Double) -> Void) async throws -> URL
    @ObservationIgnored private var generation = UUID()

    public convenience init() {
        self.init(version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0",
            build: Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0,
            fetch: AppUpdates.latest, receive: AppUpdates.download)
    }
    package init(version: String, build: Int, fetch: @escaping @Sendable () async throws -> AppRelease,
                 receive: @escaping @Sendable (AppRelease, @escaping @Sendable (Double) -> Void) async throws -> URL) {
        currentVersion = version; currentBuild = build; self.fetch = fetch; self.receive = receive
    }
    public func check() async {
        guard !busy else { return }
        let operation = UUID(); generation = operation
        checking = true; checked = false; error = nil
        defer { if generation == operation { checking = false } }
        do {
            let latest = try await fetch()
            try Task.checkCancellation()
            guard generation == operation else { return }
            if let file { try? FileManager.default.removeItem(at: file) }
            file = nil; checked = true
            release = latest.isNewer(than: currentVersion, build: currentBuild) ? latest : nil
        } catch is CancellationError {} catch {
            if generation == operation { self.error = error.localizedDescription }
        }
    }
    /// 主动检查遵守用户的永久关闭偏好，手动 check() 不受此开关影响。
    public func checkAutomatically(enabled: Bool) async -> Bool {
        guard enabled, !busy else { return false }
        await check()
        return !Task.isCancelled && error == nil && release != nil
    }
    public func download() async {
        guard !busy, let release else { return }
        let operation = UUID(); generation = operation
        downloading = true; progress = 0; error = nil
        defer { if generation == operation { downloading = false } }
        do {
            let downloaded = try await receive(release) { [weak self] value in
                Task { @MainActor in
                    guard let self, self.generation == operation else { return }
                    self.progress = value
                }
            }
            guard generation == operation, !Task.isCancelled else {
                try? FileManager.default.removeItem(at: downloaded); return
            }
            file = downloaded; progress = 1
        } catch is CancellationError {} catch {
            if generation == operation { self.error = error.localizedDescription }
        }
    }
    public func cancel() {
        generation = UUID(); checking = false; downloading = false
    }
}
