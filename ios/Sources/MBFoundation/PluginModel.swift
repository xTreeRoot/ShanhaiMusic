import Foundation
import Observation

public enum PluginPreparation: Equatable {
    case checking
    case failed(String)
}

@MainActor @Observable public final class PluginModel {
    public let ui = PluginUIService()
    public private(set) var installed: [InstalledPlugin] = []
    public private(set) var presentation = PluginPresentation()
    public private(set) var sources: [String: PluginSource] = [:]
    public private(set) var pending: PendingPlugin?
    public private(set) var preparation: PluginPreparation?
    public var busy: Bool { installing || updatingAll || preparation == .checking || starsBusy || starsEntries != nil }
    public private(set) var errorMessage: String?
    public private(set) var revision = 0
    public private(set) var disabled: Set<String> = []
    public private(set) var updates: [String: String] = [:]
    public private(set) var updatingAll = false
    public private(set) var updateStatus: String?
    public private(set) var updateIssues: [String: String] = [:]
    public private(set) var starsEntries: [PluginStarsEntry]?
    public private(set) var starsBusy = false
    public private(set) var starsStatus: String?
    public private(set) var starsFinished = false
    @ObservationIgnored private let installer: PluginInstaller
    @ObservationIgnored private let githubPreparation: (PluginInstaller, String) async throws -> PendingPlugin
    @ObservationIgnored private let starsRepositories: (String) async throws -> [String]
    private var installing = false
    public convenience init(installer: PluginInstaller = PluginInstaller()) {
        self.init(installer: installer) { try await $0.prepareGitHub($1) }
    }
    package convenience init(installer: PluginInstaller, githubPreparation: @escaping (PluginInstaller, String) async throws -> PendingPlugin) {
        self.init(installer: installer, starsRepositories: PluginStars.repositories, githubPreparation: githubPreparation)
    }
    init(installer: PluginInstaller, starsRepositories: @escaping (String) async throws -> [String], githubPreparation: @escaping (PluginInstaller, String) async throws -> PendingPlugin) {
        self.installer = installer; self.githubPreparation = githubPreparation
        self.starsRepositories = starsRepositories
    }
    public func reportError(_ error: any Error) { errorMessage = error.localizedDescription }
    public func restore() async {
        do {
            presentation = try await installer.presentation()
            installed = presentation.sorted(try await installer.installed())
            disabled = try await installer.disabled()
            sources = sources.filter { !disabled.contains($0.key) }
            for plugin in installed where sources[plugin.id] == nil && !disabled.contains(plugin.id) { sources[plugin.id] = try await source(plugin) }
            revision += 1
        } catch { errorMessage = error.localizedDescription }
    }
    public func pluginPreferences() async throws -> PluginPreferences { try await installer.pluginPreferences() }
    public func mergePluginPreferences(_ remote: PluginPreferences) async throws -> PluginPreferences {
        let previous = try await installer.pluginPreferences()
        let merged = try await installer.mergePluginPreferences(remote)
        if previous != merged { await restore() }
        return merged
    }
    public func prepare(_ url: URL) async {
        await prepare {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            return try await installer.prepare(url)
        }
    }
    public func displayName(_ plugin: InstalledPlugin) -> String {
        presentation.name(id: plugin.id, fallback: plugin.manifest.displayName)
    }
    public func rename(_ plugin: InstalledPlugin, to name: String) async {
        guard !busy else { return }
        do {
            try await installer.rename(plugin.id, to: name)
            await restore()
        } catch { reportError(error) }
    }
    public func move(from offsets: IndexSet, to destination: Int) async {
        guard !busy, (0...installed.count).contains(destination), offsets.allSatisfy({ installed.indices.contains($0) }) else { return }
        var ids = installed.map(\.id)
        let moved = offsets.sorted().map { ids[$0] }
        for index in offsets.sorted(by: >) { ids.remove(at: index) }
        ids.insert(contentsOf: moved, at: destination - offsets.filter { $0 < destination }.count)
        do {
            try await installer.saveOrder(ids)
            await restore()
        } catch { reportError(error) }
    }
    public func prepareGitHub(_ repo: String) async {
        await prepare { try await githubPreparation(installer, repo) }
    }
    private func prepare(using operation: () async throws -> PendingPlugin?) async {
        guard !busy, pending == nil else { return }
        preparation = .checking; errorMessage = nil
        do {
            pending = try await operation()
            preparation = nil
        } catch {
            preparation = .failed(error.localizedDescription)
        }
    }
    public func dismissPreparationError() {
        if case .failed = preparation { preparation = nil }
    }
    public func install() async {
        guard let pending, !busy else { return }; installing = true; errorMessage = nil
        defer { installing = false }
        do { try await apply(pending); self.pending = nil; await restore() }
        catch { errorMessage = error.localizedDescription }
    }
    public func discard() async { if let pending { await installer.discard(pending) }; pending = nil }
    public func setEnabled(_ plugin: InstalledPlugin, enabled: Bool) async {
        guard !busy else { return }
        do { try await installer.setEnabled(plugin.id, enabled: enabled); await restore() }
        catch { errorMessage = error.localizedDescription }
    }
    public func update(_ plugin: InstalledPlugin) async {
        guard plugin.repository != nil else { return }
        let current = installed.first(where: { $0.id == plugin.id }) ?? plugin
        await prepare {
            let update = try await prepareUpdate(current)
            if update == nil {
                updates[current.id] = nil
                updateIssues[current.id] = AppLocalization.text("已是最新版本")
            }
            return update
        }
    }

    private func prepareUpdate(_ plugin: InstalledPlugin) async throws -> PendingPlugin? {
        guard let repo = plugin.repository.flatMap(PluginInstaller.normalizedRepository) else { return nil }
        let next = try await githubPreparation(installer, repo)
        guard next.manifest.id == plugin.id,
              next.repository.flatMap(PluginInstaller.normalizedRepository)?.caseInsensitiveCompare(repo) == .orderedSame else {
            await installer.discard(next)
            throw PluginFailure.invalid(AppLocalization.text("更新包的插件 ID 或来源不匹配"))
        }
        if PluginInstaller.isSameRevision(next, as: plugin) { await installer.discard(next); return nil }
        return next
    }

    public func updateAll() async {
        guard !busy, pending == nil else { return }
        let candidates = installed.filter { $0.repository != nil }
        guard !candidates.isEmpty else { return }
        updatingAll = true; updateStatus = nil; errorMessage = nil; updateIssues = [:]
        defer { updatingAll = false }
        var updated = 0, unchanged = 0, review = 0, failed = 0
        for plugin in candidates where !Task.isCancelled {
            do {
                guard let next = try await prepareUpdate(plugin) else { updates[plugin.id] = nil; unchanged += 1; continue }
                if Self.needsReview(from: plugin.manifest, to: next.manifest) {
                    updates[plugin.id] = next.manifest.version ?? next.revision.map { String($0.prefix(12)) } ?? ""
                    updateIssues[plugin.id] = AppLocalization.text("新增权限，请单独打开此更新确认")
                    await installer.discard(next)
                    review += 1
                    continue
                }
                do { try await apply(next) }
                catch { await installer.discard(next); throw error }
                updated += 1
            } catch {
                if Task.isCancelled { break }
                updateIssues[plugin.id] = error.localizedDescription
                failed += 1
            }
        }
        if updated > 0 { await restore() }
        updateStatus = AppLocalization.text("已更新 %lld 个 · 已是最新 %lld 个 · 需确认权限 %lld 个 · 失败 %lld 个", arguments: [updated, unchanged, review, failed])
    }

    public func reviewStars(_ address: String) async {
        guard !busy, pending == nil else { return }
        starsBusy = true; starsStatus = nil
        defer { starsBusy = false }
        var entries: [PluginStarsEntry] = []
        do {
            let repositories = try await starsRepositories(address)
            let existing = try await installer.installed()
            var claimed: Set<String> = ["local"]
            for repo in repositories {
                try Task.checkCancellation()
                do {
                    let pending = try await githubPreparation(installer, repo)
                    let id = pending.manifest.id
                    let previous = existing.first { $0.id.caseInsensitiveCompare(id) == .orderedSame }
                    let sameSource = existing.first { $0.repository?.caseInsensitiveCompare(repo) == .orderedSame }
                    if sameSource != nil && sameSource?.id != id {
                        await installer.discard(pending)
                        throw PluginFailure.invalid(AppLocalization.text("远程仓库的插件 ID 与已安装版本不匹配"))
                    }
                    if claimed.contains(id.lowercased()) || (previous != nil &&
                        (previous?.id != id || previous?.repository?.caseInsensitiveCompare(repo) != .orderedSame)) {
                        await installer.discard(pending)
                        throw PluginFailure.invalid(AppLocalization.text("该插件 ID 已被内置插件或其他来源占用"))
                    }
                    claimed.insert(id.lowercased())
                    if let previous, PluginInstaller.isSameRevision(pending, as: previous) {
                        await installer.discard(pending)
                        entries.append(PluginStarsEntry(repository: repo, issue: AppLocalization.text("已是最新版本"), pending: nil))
                        continue
                    }
                    entries.append(PluginStarsEntry(repository: repo, issue: nil, pending: pending))
                } catch {
                    entries.append(PluginStarsEntry(repository: repo, issue: error.localizedDescription, pending: nil))
                }
            }
            try Task.checkCancellation()
            starsEntries = entries
            if entries.isEmpty { starsStatus = AppLocalization.text("列表里没有找到插件仓库") }
        } catch {
            for entry in entries { if let pending = entry.pending { await installer.discard(pending) } }
            if !Task.isCancelled { starsStatus = error.localizedDescription }
        }
    }

    public func applyStars() async {
        guard !starsBusy, !starsFinished, let entries = starsEntries, entries.contains(where: { $0.pending != nil }) else { return }
        starsBusy = true
        defer { starsBusy = false }
        var applied = 0
        var issues: [PluginStarsEntry] = []
        for entry in entries {
            guard let pending = entry.pending else { issues.append(entry); continue }
            do {
                try await apply(pending, requireSameRepository: true)
                applied += 1
            } catch {
                await installer.discard(pending)
                issues.append(PluginStarsEntry(repository: entry.repository, issue: error.localizedDescription, pending: nil))
            }
        }
        await restore()
        starsEntries = issues
        starsFinished = true
        starsStatus = AppLocalization.text("已导入或覆盖 %lld 个 · 失败或跳过 %lld 个", arguments: [applied, issues.count])
    }

    public func discardStars() async {
        guard !starsBusy else { return }
        let entries = starsEntries ?? []
        starsEntries = nil; starsStatus = nil; starsFinished = false
        for entry in entries { if let pending = entry.pending { await installer.discard(pending) } }
    }

    static func needsReview(from installed: PluginManifest, to update: PluginManifest) -> Bool {
        let granted = Set(installed.permissions?.network ?? [])
        return !granted.contains("*") && !Set(update.permissions?.network ?? []).isSubset(of: granted)
    }

    private func apply(_ pending: PendingPlugin, requireSameRepository: Bool = false) async throws {
        let plugin = try await installer.install(pending, requireSameRepository: requireSameRepository)
        sources[plugin.id] = nil
        if !disabled.contains(plugin.id) { sources[plugin.id] = try await source(plugin) }
        updates[plugin.id] = nil; updateIssues[plugin.id] = nil
    }

    @discardableResult public func remove(_ plugin: InstalledPlugin) async -> Bool {
        guard !busy, pending == nil else { return false }
        installing = true; errorMessage = nil
        defer { installing = false }
        do {
            try await installer.remove(plugin)
            sources[plugin.id] = nil; installed.removeAll { $0.id == plugin.id }; revision += 1
            updates[plugin.id] = nil; updateIssues[plugin.id] = nil
            return true
        } catch { errorMessage = error.localizedDescription; return false }
    }
    public func settings(for plugin: InstalledPlugin) async throws -> [String: String] { try await installer.settings(for: plugin) }
    public func saveSettings(_ settings: [String: String], for plugin: InstalledPlugin) async {
        do { try await installer.saveSettings(settings, for: plugin); await sources[plugin.id]?.runtime.updateSettings(settings); errorMessage = nil }
        catch { errorMessage = error.localizedDescription }
    }
    private func source(_ plugin: InstalledPlugin) async throws -> PluginSource {
        try LegacyPluginStorage.migrate(plugin)
        let settings = try await installer.settings(for: plugin)
        return PluginSource(manifest: plugin.manifest, runtime: try PluginRuntime(directory: plugin.directory, dataDirectory: plugin.dataDirectory, manifest: plugin.manifest, settings: settings, webSignIn: { [ui] request in try await ui.show(request) }, oauthSignIn: { [ui] request in try await ui.authorize(request) }, openURL: { [ui] url, title in await ui.open(url, title: title) }))
    }
}
