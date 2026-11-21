import CryptoKit
import Foundation

public struct AppRelease: Sendable, Equatable {
    public let tag: String
    public let version: String
    public let build: Int
    public let notes: String
    public let page: URL
    public let archive: URL
    public let size: Int
    public let sha256: String

    public func isNewer(than version: String, build: Int) -> Bool {
        let comparison = Self.compareVersions(self.version, version)
        return comparison > 0 || (comparison == 0 && self.build > build)
    }

    static func compareVersions(_ left: String, _ right: String) -> Int {
        let a = left.split(separator: ".").compactMap { Int($0) }
        let b = right.split(separator: ".").compactMap { Int($0) }
        for index in 0..<max(a.count, b.count) {
            let x = index < a.count ? a[index] : 0
            let y = index < b.count ? b[index] : 0
            if x != y { return x > y ? 1 : -1 }
        }
        return 0
    }

    package static func latest(in data: Data) throws -> Self {
        struct Asset: Decodable { let name: String; let browser_download_url: String; let size: Int; let digest: String? }
        struct Release: Decodable { let tag_name: String; let html_url: String; let body: String?; let draft: Bool; let prerelease: Bool; let assets: [Asset] }
        let releases = try JSONDecoder().decode([Release].self, from: data)
        let candidates: [Self] = releases.compactMap { entry in
            guard !entry.draft, !entry.prerelease,
                  let match = entry.tag_name.wholeMatch(of: /mb-ios-v([0-9]+\.[0-9]+\.[0-9]+)-build([0-9]+)/),
                  let build = Int(match.2), build > 0,
                  match.1.split(separator: ".").allSatisfy({ Int($0) != nil }) else { return nil }
            let version = String(match.1)
            let name = "MB-iOS-v\(version)-build\(build)-unsigned.ipa"
            let assets = entry.assets.filter { $0.name == name }
            guard assets.count == 1, let asset = assets.first,
                  let archive = URL(string: asset.browser_download_url), let page = URL(string: entry.html_url),
                  Self.official(archive), Self.official(page),
                  ["MB", "ShanhaiMusic"].contains(where: { archive.path == "/xTreeRoot/\($0)/releases/download/\(entry.tag_name)/\(name)" }),
                  ["MB", "ShanhaiMusic"].contains(where: { page.path == "/xTreeRoot/\($0)/releases/tag/\(entry.tag_name)" }),
                  let digest = asset.digest, digest.wholeMatch(of: /sha256:[0-9a-f]{64}/) != nil,
                  (1...VerifiedDownload.maximumBytes).contains(asset.size) else { return nil }
            return Self(tag: entry.tag_name, version: version, build: build, notes: entry.body ?? "", page: page,
                        archive: archive, size: asset.size, sha256: String(digest.dropFirst(7)))
        }
        guard let latest = candidates.max(by: { $1.isNewer(than: $0.version, build: $0.build) }) else {
            throw AppUpdateFailure.unavailable
        }
        return latest
    }

    private static func official(_ url: URL) -> Bool {
        url.scheme == "https" && url.host == "github.com" && url.user == nil && url.password == nil && url.port == nil && url.query == nil && url.fragment == nil
    }
}

public enum AppUpdateFailure: LocalizedError {
    case unavailable, archive
    public var errorDescription: String? {
        switch self {
        case .unavailable: AppLocalization.text("远程尚无可用的 iOS 更新包")
        case .archive: AppLocalization.text("更新包校验失败，请重新下载")
        }
    }
}

/// 在写入前计数与计算摘要，阻止超量、截断和损坏文件进入分享安装流程。
struct VerifiedDownload {
    static let maximumBytes = 256 << 20
    let expectedSize: Int
    let expectedHash: String
    private(set) var written = 0
    private var digest = SHA256()

    init(size: Int, sha256: String) throws {
        guard (1...Self.maximumBytes).contains(size), sha256.wholeMatch(of: /[0-9a-f]{64}/) != nil else { throw AppUpdateFailure.archive }
        expectedSize = size; expectedHash = sha256
    }
    mutating func consume(_ bytes: Data) throws {
        guard bytes.count <= expectedSize - written else { throw AppUpdateFailure.archive }
        digest.update(data: bytes); written += bytes.count
    }
    mutating func finish() throws {
        guard written == expectedSize, digest.finalize().map({ String(format: "%02x", $0) }).joined() == expectedHash else { throw AppUpdateFailure.archive }
    }
}
