import Foundation

public struct SettingsDevice: Decodable, Identifiable, Sendable {
    public let key: String
    public let name: String
    public let platform: String
    public let current: Bool
    public let updated_at: Int64
    public var id: String { key }
}

public struct NodeDeviceSettings: Codable, Sendable {
    public var version = 1
    public var app: [String: String] = [:]
    public var plugins: [String: [String: String]] = [:]
    public var accounts: [String: String] = [:]
    public var backup: NodeDeviceBackupReference?
    public var previousBackup: NodeDeviceBackupReference?
    enum CodingKeys: String, CodingKey {
        case version, app, plugins, accounts, backup
        case previousBackup = "previous_backup"
    }
    public init() {}
}

private struct SettingsEnvelope: Codable { let value: NodeDeviceSettings }
private let deviceNamespace = "device-settings"

extension PlatformDataModel {
    public func settingsDevices() async throws -> [SettingsDevice] {
        guard let node, node.active else { throw PlatformDataFailure.http(401,nil) }
        struct Result: Decodable { let devices: [SettingsDevice] }
        let (data, _) = try await client.request(node.origin, "/v1/sync/devices", method: "GET", expected: 200, token: node.grant.token)
        return try JSONDecoder().decode(Result.self, from: data).devices
    }

    public func saveDeviceSettings(_ snapshot: NodeDeviceSettings) async throws {
        guard let node, node.active else { throw PlatformDataFailure.http(401,nil) }
        let key = node.grant.device.utf8.map { String(format: "%02x", $0) }.joined()
        let tag: String
        do { tag = try await client.get(node, platform: deviceNamespace, key: key).1 }
        catch PlatformDataFailure.http(404, "not_found") { tag = "*" }
        let envelope = try PlatformDataCodec.seal(JSONEncoder().encode(SettingsEnvelope(value: snapshot)), recovery: node.grant.recovery,
            space: node.grant.space, platform: deviceNamespace, key: key)
        try Task.checkCancellation()
        guard self.node == node else { throw CancellationError() }
        try await client.change(node, platform: deviceNamespace, key: key, revision: tag, envelope: envelope)
    }

    public func readDeviceSettings(key: String) async throws -> NodeDeviceSettings {
        guard let node, node.active else { throw PlatformDataFailure.http(401,nil) }
        let (envelope, _) = try await client.get(node, platform: deviceNamespace, key: key)
        guard envelope.format == 1 else { throw PlatformDataFailure.invalid }
        let data = try PlatformDataCodec.open(envelope, recovery: node.grant.recovery, space: node.grant.space, platform: deviceNamespace, key: key)
        let doc = try JSONDecoder().decode(SettingsEnvelope.self, from: data).value
        guard doc.version == 1 else { throw PlatformDataFailure.invalid }
        return doc
    }
}

extension AppPreferences {
    private static var deviceFlags: [String: WritableKeyPath<Self, Bool>] { [
        "smoke": \.smoke, "fog": \.fog, "growFromCover": \.growFromCover, "lyricsStage": \.lyricsStage,
        "hotComments": \.hotComments, "stageSparks": \.stageSparks, "nowPlayingLyrics": \.nowPlayingLyrics,
        "pauseOtherSourcesOnSwitch": \.pauseOtherSourcesOnSwitch, "inAppVideoWindow": \.inAppVideoWindow,
        "systemVideoWindow": \.systemVideoWindow, "automaticUpdateChecks": \.automaticUpdateChecks
    ] }
    public var deviceSettings: [String: String] {
        var values = Self.deviceFlags.mapValues { String(self[keyPath: $0]) }
        values["appearance"] = appearance.rawValue; values["quality"] = quality.rawValue; values["language"] = language.rawValue
        return values
    }
    public mutating func restoreDeviceSettings(_ values: [String: String]) -> Int {
        var count = 0
        for (key, value) in values {
            if let path = Self.deviceFlags[key], let flag = Bool(value) { self[keyPath: path] = flag }
            else if key == "appearance", let setting = AppAppearance(rawValue: value) { appearance = setting }
            else if key == "quality", let setting = AudioQuality(rawValue: value) { quality = setting }
            else if key == "language", let setting = AppLanguage(rawValue: value) { language = setting }
            else { continue }
            count += 1
        }
        return count
    }
}

extension PluginManifest.Setting {
    public func acceptsPortableValue(_ value: String) -> Bool {
        switch type {
        case "boolean": value == "true" || value == "false"
        case "select": !value.isEmpty && options?.contains { $0.value == value } == true
        default: false
        }
    }
}
