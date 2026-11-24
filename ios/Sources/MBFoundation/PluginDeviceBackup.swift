import Foundation
import ZIPFoundation

extension PluginInstaller {
    func restoreDevicePluginInfo(_ manifest: DeviceArchiveManifest, from stage: URL, id: String) throws {
        guard let item = manifest.plugins.first(where: { $0.id == id }) else { throw PlatformDataFailure.invalid }
        var candidate = try prepare(stage.appendingPathComponent("plugins/\(id)/package.zip"))
        defer { discard(candidate) }
        guard candidate.manifest.id == id else { throw PlatformDataFailure.invalid }
        if let repository = item.repository, !repository.isEmpty {
            guard let normalized = Self.normalizedRepository(repository) else { throw PlatformDataFailure.invalid }
            candidate.repository = normalized
        }
        candidate.revision = item.revision
        _ = try DeviceArchiveFile.tree(stage.appendingPathComponent("plugins/\(id)/data"), prefix: "data")
        try Task.checkCancellation()
        let current = try installed().first(where: { $0.id == id }) ?? install(candidate)
        let values = item.settings.filter { key, _ in current.manifest.settings?.contains(where: { $0.key == key }) == true }
        try Self.replaceBackupData(stage.appendingPathComponent("plugins/\(id)/data"), to: current.dataDirectory, settings: values)
    }

    /// Prepare a complete sibling directory before replacing the selected plugin's data.
    private static func replaceBackupData(_ source: URL, to target: URL, settings: [String: String]) throws {
        let fm = FileManager.default, parent = target.deletingLastPathComponent()
        guard (try? target.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true,
              (try? parent.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true else { throw PlatformDataFailure.invalid }
        try fm.createDirectory(at: parent, withIntermediateDirectories: true)
        let next = parent.appendingPathComponent(".plugin-restore-" + UUID().uuidString)
        let previous = parent.appendingPathComponent(".plugin-previous-" + UUID().uuidString)
        try fm.createDirectory(at: next, withIntermediateDirectories: false)
        defer { try? fm.removeItem(at: next) }
        try copyMissingBackupData(source, to: next)
        try JSONEncoder().encode(settings).write(to: next.appendingPathComponent("settings.json"), options: .atomic)
        try Task.checkCancellation()
        let existed = fm.fileExists(atPath: target.path)
        if existed { try fm.moveItem(at: target, to: previous) }
        do { try fm.moveItem(at: next, to: target) }
        catch { if existed { try fm.moveItem(at: previous, to: target) }; throw error }
        if existed { try fm.removeItem(at: previous) }
    }

    func deviceBackupPlugins(to directory: URL) throws -> ([DeviceArchivePlugin], [DeviceArchiveEntry]) {
        let display = try presentation(), disabled = try disabled()
        var records: [DeviceArchivePlugin] = [], entries: [DeviceArchiveEntry] = []
        for item in display.sorted(try installed()) {
            try Task.checkCancellation()
            let file = directory.appendingPathComponent(item.id + ".zip")
            let archive = try Archive(url: file, accessMode: .create)
            for entry in try DeviceArchiveFile.tree(item.directory, prefix: "code") {
                let relative = String(entry.name.dropFirst(5))
                if [".origin.json", ".mb-origin.json"].contains(relative) { continue }
                try archive.addEntry(with: relative, fileURL: entry.file, compressionMethod: .deflate)
            }
            records.append(DeviceArchivePlugin(id: item.id, repository: item.repository, revision: item.revision,
                name: display.names[item.id], enabled: !disabled.contains(item.id), settings: try settings(for: item)))
            entries.append(DeviceArchiveEntry(name: "plugins/\(item.id)/package.zip", file: file))
            entries += try DeviceArchiveFile.tree(item.dataDirectory, prefix: "plugins/\(item.id)/data")
        }
        return (records, entries)
    }

    func restoreDevicePlugins(_ manifest: DeviceArchiveManifest, from stage: URL) throws {
        var candidates: [PendingPlugin] = []
        defer { for candidate in candidates { discard(candidate) } }
        // Validate all packages before installing any of them.
        for item in manifest.plugins {
            var candidate = try prepare(stage.appendingPathComponent("plugins/\(item.id)/package.zip"))
            candidates.append(candidate)
            guard candidate.manifest.id == item.id else { throw PlatformDataFailure.invalid }
            if let repository = item.repository, !repository.isEmpty {
                guard let normalized = Self.normalizedRepository(repository) else { throw PlatformDataFailure.invalid }
                candidate.repository = normalized
            }
            candidate.revision = item.revision
            candidates[candidates.count - 1] = candidate
        }
        let current = try installed()
        for (index, item) in manifest.plugins.enumerated() {
            try Task.checkCancellation()
            let installed = try current.first(where: { $0.id == item.id }) ?? install(candidates[index])
            try Self.copyMissingBackupData(stage.appendingPathComponent("plugins/\(item.id)/data"), to: installed.dataDirectory)
            var values = try settings(for: installed)
            for field in installed.manifest.settings ?? [] { if let value = item.settings[field.key] { values[field.key] = value } }
            try saveSettings(values, for: installed)
        }
        if let preferences = manifest.pluginPreferences { _ = try mergePluginPreferences(preferences) }
        if let preferences = manifest.discoveryPreferences { _ = try mergeDiscoveryPreferences(preferences) }
        for item in manifest.plugins {
            try setEnabled(item.id, enabled: item.enabled)
            if let name = item.name { try rename(item.id, to: name) }
        }
        let remaining = current.map(\.id).filter { id in !manifest.plugins.contains { $0.id == id } }
        try saveOrder(manifest.plugins.map(\.id) + remaining)
    }

    private static func copyMissingBackupData(_ source: URL, to target: URL) throws {
        for entry in try DeviceArchiveFile.tree(source, prefix: "data") {
            let relative = String(entry.name.dropFirst(5)), output = target.appendingPathComponent(relative)
            guard output.resolvingSymlinksInPath().path.hasPrefix(target.resolvingSymlinksInPath().path + "/") else { throw PlatformDataFailure.invalid }
            if FileManager.default.fileExists(atPath: output.path) { continue }
            try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: entry.file, to: output)
            #if os(iOS)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: output.path)
            #endif
        }
    }
}
