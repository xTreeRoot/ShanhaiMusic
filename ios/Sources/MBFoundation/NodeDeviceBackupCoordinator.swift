import CryptoKit
import Foundation

struct DeviceBackupOperation: Sendable { let directory: URL; let archive: URL; let plugins: Int }
struct DeviceBackupRestored: Sendable {
    let preferences: AppPreferences?
    let library: DeviceArchiveLibrary
    let session: PlaybackSession?
}

actor NodeDeviceBackupCoordinator {
    private let archive = DeviceArchiveFile()
    private let root: URL
    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile/device-backups")
    }
    func operationDirectory() throws -> URL {
        let directory = root.appendingPathComponent("operation-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
    func remove(_ directory: URL) {
        guard directory.deletingLastPathComponent().standardizedFileURL == root.standardizedFileURL,
              directory.lastPathComponent.hasPrefix("operation-") else { return }
        try? FileManager.default.removeItem(at: directory)
    }

    func make(preferences: AppPreferences, plugins: PluginInstaller, media: MediaLibrary, library: LocalLibraryStore) async throws -> DeviceBackupOperation {
        let directory = try operationDirectory()
        do {
            let (records, packages) = try await plugins.deviceBackupPlugins(to: directory)
            var manifest = DeviceArchiveManifest(platform: "ios", plugins: records)
            manifest.pluginPreferences = try await plugins.pluginPreferences()
            manifest.discoveryPreferences = try await plugins.discoveryPreferences()
            let local = try await library.snapshot()
            var entries = packages
            var known = Set<String>()
            for (item, file) in try await media.deviceBackupFiles() {
                let digest = try Self.digest(file)
                let asset = "media/" + digest + "." + file.pathExtension.lowercased()
                manifest.library.tracks.append(DeviceArchiveTrack(item.track, asset: asset, kind: item.kind.rawValue))
                known.insert(item.track.id)
                if !entries.contains(where: { $0.name == asset }) { entries.append(DeviceArchiveEntry(name: asset, file: file)) }
            }
            for item in local.resources ?? [] where !known.contains(item.id) { manifest.library.tracks.append(DeviceArchiveTrack(item)); known.insert(item.id) }
            manifest.library.favorites = local.favorites; manifest.library.recent = local.recent
            manifest.library.playlists = local.playlists.map { DeviceArchivePlaylist(id: $0.id, name: $0.name, tracks: $0.trackIDs) }
            manifest.library.searchHistory = preferences.searchHistory
            if let session = try await AppPersistence.shared.loadSession() {
                manifest.library.queue = DeviceArchiveQueue(tracks: session.queue.map { DeviceArchiveTrack($0) }, index: session.index,
                    position: session.positionMilliseconds, shuffled: session.shuffled ?? false, repeat: (session.repeatMode ?? .off).rawValue)
            }
            let file = directory.appendingPathComponent("preferences.json")
            try JSONEncoder().encode(preferences).write(to: file, options: .atomic)
            entries.append(DeviceArchiveEntry(name: "native/ios/preferences.json", file: file))
            let output = directory.appendingPathComponent("device.zip")
            try await archive.write(manifest, entries: entries, to: output, scratch: directory)
            return DeviceBackupOperation(directory: directory, archive: output, plugins: records.count)
        } catch { remove(directory); throw error }
    }

    static func digest(_ file: URL) throws -> String {
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var hash = SHA256()
        while let data = try input.read(upToCount: 1 << 20), !data.isEmpty { try Task.checkCancellation(); hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }

    func apply(archive file: URL, in directory: URL, plugins: PluginInstaller, media: MediaLibrary,
               library: LocalLibraryStore, stillCurrent: @Sendable () async -> Bool) async throws -> DeviceBackupRestored {
        let stage = directory.appendingPathComponent("content")
        let manifest = try await archive.extract(file, to: stage)
        var preferences: AppPreferences?
        if manifest.platform == "ios" {
            preferences = try JSONDecoder().decode(AppPreferences.self, from: Data(contentsOf: stage.appendingPathComponent("native/ios/preferences.json")))
        }
        try Task.checkCancellation()
        guard await stillCurrent() else { throw CancellationError() }
        try await plugins.restoreDevicePlugins(manifest, from: stage, overwrite: true)
        var tracks: [String: Track] = [:]
        for item in manifest.library.tracks {
            try Task.checkCancellation()
            guard await stillCurrent() else { throw CancellationError() }
            var track = item.metadata()
            if let asset = item.asset {
                let file = stage.appendingPathComponent(asset)
                if item.source == "local" || item.kind == "imported" { track = item.metadata(localID: try Self.digest(file)) }
                if try await !media.list().contains(where: { $0.track.id == track.id }) { try await media.saveDownload(file: file, track: track) }
            }
            tracks[item.key] = track
        }
        guard await stillCurrent() else { throw CancellationError() }
        _ = try await library.restoreDeviceBackup(manifest.library, tracks: tracks, overwrite: true)
        var session: PlaybackSession?
        if let queue = manifest.library.queue, !queue.tracks.isEmpty {
            let values = queue.tracks.map { tracks[$0.key] ?? $0.metadata() }
            session = PlaybackSession(queue: values, index: max(0, min(queue.index, values.count - 1)), positionMilliseconds: queue.position,
                shuffled: queue.shuffled, repeatMode: RepeatMode(rawValue: queue.repeat) ?? .off)
        }
        return DeviceBackupRestored(preferences: preferences, library: manifest.library, session: session)
    }
}

extension PlatformDataModel {
    public func backupCurrentDevice(preferences: PreferencesModel, plugins: PluginModel, downloads: DownloadModel,
                                    library: LocalLibraryModel, accounts: [String: String]) async throws -> (synced: Int, unconfirmed: Int) {
        guard let node, node.active else { throw PlatformDataFailure.http(401, nil) }
        await preferences.flush()
        let coordinator = NodeDeviceBackupCoordinator()
        let operation = try await coordinator.make(preferences: preferences.values, plugins: plugins.installer, media: downloads.library, library: library.store)
        do {
            let transfer = NodeDeviceBackupTransfer(node: node, client: client)
            let devices = try await settingsDevices()
            var previous: NodeDeviceSettings?
            if let current = devices.first(where: { $0.current && $0.updated_at > 0 }) { previous = try await readDeviceSettings(key: current.key) }
            var doc = NodeDeviceSettings(); doc.app = preferences.values.deviceSettings; doc.accounts = accounts
            for plugin in plugins.installed {
                let fields = plugin.manifest.settings ?? []
                let values = try await plugins.settings(for: plugin).filter { key, value in
                    fields.contains { $0.key == key && $0.acceptsPortableValue(value) }
                }
                if !values.isEmpty { doc.plugins[plugin.id] = values }
            }
            doc.backup = try await transfer.upload(file: operation.archive, platform: "ios", plugins: operation.plugins)
            doc.previousBackup = previous?.backup
            try Task.checkCancellation()
            guard self.node == node else { throw CancellationError() }
            // Preserve uploaded objects on an ambiguous settings response: the
            // node may already have committed this reference before disconnect.
            try await saveDeviceSettings(doc)
            if let obsolete = previous?.previousBackup { try? await transfer.delete(reference: obsolete) }
            await coordinator.remove(operation.directory)
            var synced = 0, unconfirmed = 0
            for (id, source) in plugins.sources {
                guard self.node == node, !Task.isCancelled else { break }
                let account = AccountModel(source: source, platformID: id)
                await account.restore()
                guard account.canUseCloud, account.account != nil else { continue }
                let revision = account.revision
                let stillCurrent: @MainActor @Sendable () -> Bool = {
                    self.node == node && plugins.sources[id] === source && account.revision == revision && !Task.isCancelled
                }
                do {
                    let digest = try await account.verifyCloudPlugin(client: client, node: node)
                    guard stillCurrent(), let payload = try await account.exportCloudSignIn(digest: digest), stillCurrent()
                    else { unconfirmed += 1; continue }
                    try await client.publishSession(node, platform: id, payload: payload, stillCurrent: stillCurrent)
                    if stillCurrent() { synced += 1 } else { unconfirmed += 1 }
                } catch { unconfirmed += 1 }
            }
            await refreshCloudStatus()
            return (synced, unconfirmed)
        } catch { await coordinator.remove(operation.directory); throw error }
    }

    public func restoreCurrentDevice(key: String, settings: NodeDeviceSettings, preferences: PreferencesModel, plugins: PluginModel,
                                     downloads: DownloadModel, library: LocalLibraryModel, playback: PlaybackModel) async throws {
        guard let node, node.active, let reference = settings.backup else { throw PlatformDataFailure.invalid }
        let coordinator = NodeDeviceBackupCoordinator(), transfer = NodeDeviceBackupTransfer(node: node, client: client)
        let directory = try await coordinator.operationDirectory()
        var retired = false
        do {
            let file = directory.appendingPathComponent("device.zip")
            try await transfer.download(owner: key, reference: reference, to: file)
            try Task.checkCancellation()
            guard self.node == node else { throw CancellationError() }
            for id in Array(plugins.sources.keys) { await plugins.retireBackupPlugin(id) }
            retired = true
            let restored = try await coordinator.apply(archive: file, in: directory, plugins: plugins.installer,
                media: downloads.library, library: library.store, stillCurrent: { @MainActor [weak self] in self?.node == node })
            guard self.node == node else { throw CancellationError() }
            if let full = restored.preferences { preferences.values = full }
            _ = preferences.values.restoreDeviceSettings(settings.app)
            preferences.values.searchHistory = restored.library.searchHistory
            await preferences.flush()
            try await plugins.reloadRestoredDevicePlugins()
            await downloads.refresh(); await library.restore(); library.updateTracks(downloads.items)
            playback.clear()
            if let session = restored.session { await playback.restoreDeviceBackup(session) }
            await coordinator.remove(directory)
        } catch {
            if retired { try? await plugins.reloadRestoredDevicePlugins() }
            await coordinator.remove(directory)
            throw error
        }
    }
}
