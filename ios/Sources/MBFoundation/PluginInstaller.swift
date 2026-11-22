import Foundation
import ZIPFoundation

public struct InstalledPlugin: Identifiable, Sendable {
    public let manifest: PluginManifest
    public let directory: URL
    public let dataDirectory: URL
    public var repository: String? = nil
    var revision: String? = nil
    public var id: String { manifest.id }
}
public struct PendingPlugin: Identifiable, Sendable {
    public let id: UUID
    public let manifest: PluginManifest
    let directory: URL
    var repository: String? = nil
    var revision: String? = nil
    public var releaseNotes: String? = nil
}

/// 安装先在临时目录检查，确认后原子替换代码，账号与设置留在独立数据目录。
public actor PluginInstaller {
    private struct Origin: Codable { let repository: String?; let revision: String? }
    private struct Release: Sendable {
        let revision: String
        let address: URL
        let notes: String?
    }
    private lazy var preferenceCache = PluginPreferenceCache(root: root)
    private lazy var discoveryCache = DiscoveryPreferenceCache(root: root.appendingPathComponent("discovery"))
    private let root: URL
    private let fetch: @Sendable (URLRequest, Int) async throws -> (Data, URLResponse)
    public init(directory: URL? = nil) {
        root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MBMobile/plugins")
        fetch = { try await NetworkClient.shared.data(for: $0, maximumBytes: $1) }
    }
    init(directory: URL, fetch: @escaping @Sendable (URLRequest, Int) async throws -> (Data, URLResponse)) {
        root = directory; self.fetch = fetch
    }
    private func migratePreferences(_ writer: String) throws -> PluginPreferences {
        var document = PluginPreferences()
        let presentationFile = root.appendingPathComponent("presentation.json")
        if FileManager.default.fileExists(atPath: presentationFile.path) {
            let old = try JSONDecoder().decode(PluginPresentation.self, from: Data(contentsOf: presentationFile))
            for id in old.names.keys.sorted() { document = try document.withName(id: id, name: old.names[id]!, writer: writer) }
            if !old.order.isEmpty { document = try document.withOrder(old.order, writer: writer) }
        }
        let disabledFile = root.appendingPathComponent("disabled.json")
        let disabled = FileManager.default.fileExists(atPath: disabledFile.path) ? try JSONDecoder().decode(Set<String>.self, from: Data(contentsOf: disabledFile)) : []
        for id in disabled.sorted() {
            document = try document.withEnabled(id: id, enabled: false, writer: writer)
        }
        return document
    }
    public func pluginPreferences() throws -> PluginPreferences { try preferenceCache.load(migrate: migratePreferences) }
    public func mergePluginPreferences(_ remote: PluginPreferences) throws -> PluginPreferences {
        try Task.checkCancellation()
        return try preferenceCache.update(migrate: migratePreferences) { current, _ in try current.merged(with: remote) }
    }
    public func discoveryPreferences() throws -> DiscoveryPreferences { try discoveryCache.load { _ in DiscoveryPreferences() } }
    public func mergeDiscoveryPreferences(_ remote: DiscoveryPreferences) throws -> DiscoveryPreferences {
        try Task.checkCancellation()
        return try discoveryCache.update(migrate: { _ in DiscoveryPreferences() }) { current, _ in try current.merged(with: remote) }
    }
    public func saveDiscovery(_ plugin: String, before: DiscoveryPreferences.Selection, draft: DiscoveryPreferences.Selection) throws -> DiscoveryPreferences {
        try discoveryCache.update(migrate: { _ in DiscoveryPreferences() }) { current, writer in try current.edited(plugin: plugin, before: before, draft: draft, writer: writer) }
    }
    public func presentation() throws -> PluginPresentation {
        let preferences = try pluginPreferences()
        var result = PluginPresentation()
        result.names = preferences.names.compactMapValues { $0.value.isEmpty ? nil : $0.value }
        result.order = preferences.order?.value ?? []
        return result
    }
    public func rename(_ id: String, to rawName: String) throws {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        _ = try preferenceCache.update(migrate: migratePreferences) { current, writer in
            if current.names[id]?.value == name { return current }
            return try current.withName(id: id, name: name, writer: writer)
        }
    }
    public func saveOrder(_ ids: [String]) throws {
        _ = try preferenceCache.update(migrate: migratePreferences) { current, writer in
            let order = preservingMissingPluginOrder(current.order?.value ?? [], visible: ids)
            return current.order?.value == order ? current : try current.withOrder(order, writer: writer)
        }
    }
    public func savePresentation(_ value: PluginPresentation) throws {
        _ = try preferenceCache.update(migrate: migratePreferences) { current, writer in
            var next = current
            for id in Set(current.names.keys).union(value.names.keys).sorted() {
                let name = value.names[id] ?? ""
                if (current.names[id]?.value ?? "") != name { next = try next.withName(id: id, name: name, writer: writer) }
            }
            if (current.order?.value ?? []) != value.order { next = try next.withOrder(value.order, writer: writer) }
            return next
        }
    }
    public func installed() throws -> [InstalledPlugin] {
        let folder = root.appendingPathComponent("installed")
        guard FileManager.default.fileExists(atPath: folder.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { !$0.lastPathComponent.hasPrefix(".") }.map { directory in
            let manifest = try readManifest(directory)
            guard directory.lastPathComponent == manifest.id else { throw PluginFailure.invalid("Plugin folder ID mismatch") }
            let data = root.appendingPathComponent("data/" + manifest.id)
            let originFile = directory.appendingPathComponent(".mb-origin.json")
            let origin: Origin
            if let bytes = try? Data(contentsOf: originFile) { origin = try JSONDecoder().decode(Origin.self, from: bytes) }
            else { origin = Origin(repository: try? String(contentsOf: data.appendingPathComponent("repository.txt"), encoding: .utf8), revision: nil) }
            return InstalledPlugin(manifest: manifest, directory: directory, dataDirectory: data,
                                   repository: origin.repository.flatMap(Self.normalizedRepository), revision: origin.revision)
        }.sorted { $0.manifest.displayName.localizedStandardCompare($1.manifest.displayName) == .orderedAscending }
    }
    public func prepare(_ input: URL) throws -> PendingPlugin {
        let id = UUID()
        let staging = root.appendingPathComponent("pending/" + id.uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            let values = try input.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw PluginFailure.invalid("Symbolic links are not supported") }
            if values.isDirectory == true { try copyDirectory(input, to: staging) }
            else {
                guard (try input.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 64 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds 64 MB") }
                try unpack(input, into: staging)
            }
            let directory = try findRoot(staging)
            let manifest = try readManifest(directory)
            return PendingPlugin(id: id, manifest: manifest, directory: directory)
        } catch { try? FileManager.default.removeItem(at: staging); throw error }
    }
    public static func repository(fromInstallURL url: URL) -> String? {
        guard ["mb", "mygo-music"].contains(url.scheme?.lowercased() ?? ""), url.host == "install-plugin",
              let values = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              values.filter({ $0.name == "repo" }).count == 1,
              let repo = values.first(where: { $0.name == "repo" })?.value,
              repo.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil,
              !repo.contains("..") else { return nil }
        return repo
    }
    public func prepareGitHub(_ input: String) async throws -> PendingPlugin {
        guard let repo = Self.normalizedRepository(input) else { throw PluginFailure.invalid("Use owner/repository or a GitHub repository URL") }
        return try await prepareGitHub(repo, release: latest(repo))
    }

    static func normalizedRepository(_ input: String) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        var repo = text
        if text.contains("://") {
            guard let url = URL(string: text), url.scheme == "https", url.host == "github.com",
                  url.user == nil, url.password == nil, url.port == nil, url.query == nil, url.fragment == nil else { return nil }
            let parts = url.path.split(separator: "/")
            guard parts.count == 2 else { return nil }
            repo = parts.joined(separator: "/")
        }
        if repo.hasSuffix(".git") { repo = String(repo.dropLast(4)) }
        guard repo.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil, !repo.contains("..") else { return nil }
        return repo
    }

    /// 用默认分支的固定提交下载，避免旧 Release、相同版本号或移动的 HEAD 漏掉更新。
    private func latest(_ repo: String) async throws -> Release {
        let address = URL(string: "https://api.github.com/repos/" + repo + "/commits/HEAD")!
        var request = URLRequest(url: address)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await fetch(request, 2 * 1_024 * 1_024)
        guard let response = response as? HTTPURLResponse else { throw NetworkFailure.response }
        guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
        let value = try JSONDecoder().decode(PluginJSON.self, from: data)
        guard let sha = value["sha"].string, sha.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil,
              let url = URL(string: "https://api.github.com/repos/" + repo + "/zipball/" + sha) else { throw NetworkFailure.response }
        return Release(revision: sha, address: url, notes: value["commit"]["message"].string)
    }

    private func prepareGitHub(_ repo: String, release: Release) async throws -> PendingPlugin {
        var request = URLRequest(url: release.address)
        request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
        let (bytes, result) = try await fetch(request, 64 * 1_024 * 1_024)
        guard let response = result as? HTTPURLResponse else { throw NetworkFailure.response }
        guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
        guard bytes.count <= 64 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds 64 MB") }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
        defer { try? FileManager.default.removeItem(at: file) }
        try bytes.write(to: file)
        var pending = try prepare(file); pending.repository = repo; pending.revision = release.revision
        pending.releaseNotes = release.notes
        return pending
    }

    /// 来源和 ID 都相同才覆盖；版本号相同但提交不同也属于更新。
    static func isSameRevision(_ pending: PendingPlugin, as plugin: InstalledPlugin) -> Bool {
        guard let current = plugin.revision, let next = pending.revision else { return false }
        return current == next && plugin.repository.flatMap(normalizedRepository)?.lowercased() == pending.repository.flatMap(normalizedRepository)?.lowercased()
    }

    static func assetIndex(_ names: [String]) -> Int? {
        var general: Int?
        for (index, name) in names.enumerated() {
            let name = name.lowercased()
            guard name.hasSuffix(".zip") else { continue }
            if name.contains("ios") && name.contains("arm64") { return index }
            if !["darwin", "macos", "windows", "linux", "android", "ios"].contains(where: name.contains), general == nil { general = index }
        }
        return general
    }
    public func install(_ pending: PendingPlugin) throws -> InstalledPlugin {
        try install(pending, requireSameRepository: false)
    }

    func install(_ pending: PendingPlugin, requireSameRepository: Bool) throws -> InstalledPlugin {
        let manifest = try readManifest(pending.directory)
        guard manifest.id == pending.manifest.id else { throw PluginFailure.invalid("Plugin changed during installation") }
        let folder = root.appendingPathComponent("installed"), destination = folder.appendingPathComponent(manifest.id)
        let data = root.appendingPathComponent("data/" + manifest.id)
        if requireSameRepository, let previous = try installed().first(where: { $0.id.caseInsensitiveCompare(manifest.id) == .orderedSame }) {
            guard previous.id == manifest.id, let repository = pending.repository,
                  previous.repository?.caseInsensitiveCompare(repository) == .orderedSame else {
                throw PluginFailure.invalid(AppLocalization.text("该插件 ID 已被其他来源占用"))
            }
        }
        let existing = try installed()
        if let previous = existing.first(where: { $0.id == manifest.id }), previous.manifest.displayName != manifest.displayName {
            var display = try presentation()
            display.order = display.sorted(existing).map(\.id)
            try savePresentation(display)
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        let origin = Origin(repository: pending.repository, revision: pending.revision)
        try JSONEncoder().encode(origin).write(to: pending.directory.appendingPathComponent(".mb-origin.json"), options: .atomic)
        if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: pending.directory) }
        else { try FileManager.default.moveItem(at: pending.directory, to: destination) }
        discard(pending)
        return InstalledPlugin(manifest: manifest, directory: destination, dataDirectory: data, repository: origin.repository, revision: origin.revision)
    }
    public func discard(_ pending: PendingPlugin) { try? FileManager.default.removeItem(at: root.appendingPathComponent("pending/" + pending.id.uuidString)) }
    public func remove(_ plugin: InstalledPlugin) throws { try FileManager.default.removeItem(at: plugin.directory) }
    static func isNewer(_ version: String, than current: String) -> Bool {
        let version = version.hasPrefix("v") ? String(version.dropFirst()) : version
        let current = current.hasPrefix("v") ? String(current.dropFirst()) : current
        return version.compare(current, options: .numeric) == .orderedDescending
    }
    public func disabled() throws -> Set<String> { Set(try pluginPreferences().enabled.filter { !$0.value.value }.keys) }
    public func setEnabled(_ id: String, enabled: Bool) throws {
        _ = try preferenceCache.update(migrate: migratePreferences) { current, writer in
            if current.enabled[id]?.value == enabled { return current }
            return try current.withEnabled(id: id, enabled: enabled, writer: writer)
        }
    }
    public func settings(for plugin: InstalledPlugin) throws -> [String: String] {
        let file = plugin.dataDirectory.appendingPathComponent("settings.json")
        return FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode([String: String].self, from: Data(contentsOf: file)) : [:]
    }
    public func saveSettings(_ settings: [String: String], for plugin: InstalledPlugin) throws {
        try FileManager.default.createDirectory(at: plugin.dataDirectory, withIntermediateDirectories: true)
        let bytes = try JSONEncoder().encode(settings), file = plugin.dataDirectory.appendingPathComponent("settings.json")
        #if os(iOS)
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try bytes.write(to: file, options: .atomic)
        #endif
    }
    private func readManifest(_ directory: URL) throws -> PluginManifest {
        let bytes = try Data(contentsOf: directory.appendingPathComponent("plugin.json"))
        guard bytes.count <= 1_024 * 1_024 else { throw PluginFailure.invalid("Manifest too large") }
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: bytes)
        try manifest.validate()
        let entry = directory.appendingPathComponent(manifest.entry).resolvingSymlinksInPath()
        guard entry.path.hasPrefix(directory.resolvingSymlinksInPath().path + "/"), FileManager.default.fileExists(atPath: entry.path) else { throw PluginFailure.invalid("Missing plugin entry") }
        return manifest
    }
    private func findRoot(_ folder: URL) throws -> URL {
        if FileManager.default.fileExists(atPath: folder.appendingPathComponent("plugin.json").path) { return folder }
        let folders = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { !$0.lastPathComponent.hasPrefix(".") && $0.lastPathComponent != "__MACOSX" }
        guard folders.count == 1, FileManager.default.fileExists(atPath: folders[0].appendingPathComponent("plugin.json").path) else { throw PluginFailure.invalid("Archive must contain one plugin folder with plugin.json") }
        return folders[0]
    }
    private func copyDirectory(_ input: URL, to folder: URL) throws {
        let input = input.resolvingSymlinksInPath()
        guard let enumerator = FileManager.default.enumerator(at: input, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey], options: [.skipsHiddenFiles]) else { throw CocoaError(.fileReadNoPermission) }
        var count = 0, bytes = 0
        for case let file as URL in enumerator {
            try Task.checkCancellation(); count += 1
            let values = try file.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isSymbolicLink != true else { throw PluginFailure.invalid("Symbolic links are not supported") }
            bytes += values.fileSize ?? 0
            guard count <= 10_000, bytes <= 128 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin directory exceeds limits") }
            let relative = String(file.resolvingSymlinksInPath().path.dropFirst(input.path.count + 1)), target = folder.appendingPathComponent(relative)
            if values.isDirectory == true { try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true) }
            else { try FileManager.default.copyItem(at: file, to: target) }
        }
    }
    private func unpack(_ input: URL, into folder: URL) throws {
        let archive = try Archive(url: input, accessMode: .read)
        var count = 0, bytes: UInt64 = 0
        for entry in archive {
            try Task.checkCancellation(); count += 1
            let path = entry.path, components = path.split(separator: "/", omittingEmptySubsequences: false)
            guard !path.hasPrefix("/"), !path.contains("\\"), !components.contains(".."), !path.contains("\0"), entry.type != .symlink else { throw PluginFailure.invalid("Unsafe archive entry") }
            bytes += entry.uncompressedSize
            guard count <= 10_000, bytes <= 128 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds limits") }
            if components.contains(where: { $0.hasPrefix(".") || $0 == "__MACOSX" }) { continue }
            let target = folder.appendingPathComponent(path)
            guard target.standardizedFileURL.path.hasPrefix(folder.standardizedFileURL.path + "/") else { throw PluginFailure.invalid("Archive path outside destination") }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            let checksum = try archive.extract(entry, to: target)
            guard checksum == entry.checksum else { throw PluginFailure.invalid("Archive checksum mismatch") }
        }
    }
}
