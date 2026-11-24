import Foundation

public struct BackupPluginChoice: Identifiable, Sendable {
    public let id: String
    public let name: String
}

extension NodeDeviceBackupCoordinator {
    func pluginChoices(file: URL, directory: URL) async throws -> [BackupPluginChoice] {
        let manifest = try await DeviceArchiveFile().extract(file, to: directory.appendingPathComponent("content"))
        return manifest.plugins.map { BackupPluginChoice(id: $0.id, name: $0.name ?? $0.id) }
    }

    func applyPlugin(file: URL, directory: URL, installer: PluginInstaller, id: String,
                     stillCurrent: @Sendable () async -> Bool) async throws {
        let stage = directory.appendingPathComponent("content")
        let manifest = try await DeviceArchiveFile().extract(file, to: stage)
        try Task.checkCancellation()
        guard await stillCurrent() else { throw CancellationError() }
        try await installer.restoreDevicePluginInfo(manifest, from: stage, id: id)
    }
}

extension PlatformDataModel {
    public func backupPluginChoices(key: String, settings: NodeDeviceSettings) async throws -> [BackupPluginChoice] {
        guard let node, node.active, let reference = settings.backup else { throw PlatformDataFailure.invalid }
        let coordinator = NodeDeviceBackupCoordinator()
        let directory = try await coordinator.operationDirectory()
        do {
            let file = directory.appendingPathComponent("device.zip")
            try await NodeDeviceBackupTransfer(node: node, client: client).download(owner: key, reference: reference, to: file)
            try Task.checkCancellation()
            guard self.node == node else { throw CancellationError() }
            let choices = try await coordinator.pluginChoices(file: file, directory: directory)
            await coordinator.remove(directory)
            return choices
        } catch { await coordinator.remove(directory); throw error }
    }

    public func restorePluginInformation(key: String, settings: NodeDeviceSettings, id: String, plugins: PluginModel) async throws {
        guard let node, node.active, let reference = settings.backup else { throw PlatformDataFailure.invalid }
        let coordinator = NodeDeviceBackupCoordinator()
        let directory = try await coordinator.operationDirectory()
        do {
            let file = directory.appendingPathComponent("device.zip")
            try await NodeDeviceBackupTransfer(node: node, client: client).download(owner: key, reference: reference, to: file)
            await plugins.retireBackupPlugin(id)
            try await coordinator.applyPlugin(file: file, directory: directory, installer: plugins.installer, id: id,
                stillCurrent: { @MainActor [weak self] in self?.node == node })
            guard self.node == node else { throw CancellationError() }
            try await plugins.reloadRestoredPlugin(id)
            await coordinator.remove(directory)
        } catch { try? await plugins.reloadRestoredPlugin(id); await coordinator.remove(directory); throw error }
    }
}
