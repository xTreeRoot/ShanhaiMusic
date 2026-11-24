import Foundation

public struct PlaybackSession: Codable, Sendable {
    public let queue: [Track]
    public let index: Int
    public let positionMilliseconds: Int64
    public let captions: [CaptionLine]?
    public let shuffled: Bool?
    public let repeatMode: RepeatMode?
    public let playOrder: [Int]?
    public let station: PlaybackStation?
    public init(queue: [Track], index: Int, positionMilliseconds: Int64, captions: [CaptionLine]? = nil,
                shuffled: Bool? = nil, repeatMode: RepeatMode? = nil, playOrder: [Int]? = nil, station: PlaybackStation? = nil) {
        self.queue = queue
        self.index = index
        self.positionMilliseconds = positionMilliseconds
        self.captions = captions
        self.shuffled = shuffled; self.repeatMode = repeatMode; self.playOrder = playOrder
        self.station = station
    }
}

public protocol PlaybackSessionStore: Sendable {
    func loadSession() async throws -> PlaybackSession?
    func saveSession(_ session: PlaybackSession) async throws
}

public enum PlaybackDiagnosticEvent: String, Codable, Sendable {
    case launch, sceneActive, sceneInactive, sceneBackground
    case carAudio, otherAudio
    case audioLoading, audioPlaying, audioPaused, audioFailed, videoFailed
}

/// 数据仅在本 App 的沙盒，不读取桌面会话，也不保存临时播放地址。
public actor AppPersistence: PlaybackSessionStore, PlaybackModeStore {
    private struct ProxyHosts: Codable { let hosts: [String]; let version: Int? }
    struct PlaybackDiagnostic: Codable {
        let timestamp: String
        let build: String?
        let event: PlaybackDiagnosticEvent
    }
    public static let shared = AppPersistence()
    private let directory: URL
    public init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile", isDirectory: true)
    }

    public func loadSession() throws -> PlaybackSession? { try load(PlaybackSession.self, name: "playback.json") }
    public func saveSession(_ session: PlaybackSession) throws { try save(session, name: "playback.json") }

    public nonisolated func logPlayback(_ event: PlaybackDiagnosticEvent) {
        let timestamp = Date.now
        Task(priority: .utility) { try? await writePlaybackDiagnostic(event, at: timestamp) }
    }

    func writePlaybackDiagnostic(_ event: PlaybackDiagnosticEvent, at timestamp: Date) throws {
        let name = "playback-diagnostics.json"
        var recent = (try? load([PlaybackDiagnostic].self, name: name)) ?? []
        recent.append(PlaybackDiagnostic(timestamp: timestamp.ISO8601Format(.init(includingFractionalSeconds: true)),
            build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String, event: event))
        try save(Array(recent.sorted { $0.timestamp < $1.timestamp }.suffix(128)), name: name)
    }

    public func loadProxyHosts() throws -> [String]? {
        let file = "proxy-hosts.json"
        do {
            guard let saved = try load(ProxyHosts.self, name: file) else { return nil }
            guard saved.version == nil else { return saved.hosts }
            let hosts = upgradedOAuthHosts(saved.hosts)
            try saveProxyHosts(hosts)
            return hosts
        }
        catch is DecodingError {
            guard let legacy = try load([String].self, name: file) else { return nil }
            let hosts = upgradedOAuthHosts(legacy.isEmpty ? legacy : legacy + ProxyPolicy.githubHosts.filter { !legacy.contains($0) })
            try saveProxyHosts(hosts)
            return hosts
        }
    }
    public func saveProxyHosts(_ hosts: [String]) throws { try save(ProxyHosts(hosts: hosts, version: 1), name: "proxy-hosts.json") }
    /// 只给旧 Google OAuth 白名单补充新账号接口；后续手动删掉时不再补回。
    private func upgradedOAuthHosts(_ hosts: [String]) -> [String] {
        hosts.contains("oauth2.googleapis.com") && !hosts.contains("youtube.googleapis.com") ? hosts + ["youtube.googleapis.com"] : hosts
    }
    func loadPlatformNode() throws -> PlatformNode? { try load(PlatformNode.self, name: "platform-node.json") }
    func isCloudSignInBlocked(_ platform: String) throws -> Bool {
        try load(Set<String>.self, name: "cloud-signin-blocked.json")?.contains(platform) ?? false
    }
    func setCloudSignInBlocked(_ platform: String, blocked: Bool) throws {
        let name = "cloud-signin-blocked.json"
        var platforms = try load(Set<String>.self, name: name) ?? []
        if blocked { platforms.insert(platform) } else { platforms.remove(platform) }
        try save(platforms, name: name)
    }
    func savePlatformNode(_ node: PlatformNode?) throws {
        let name = "platform-node.json"
        if let node { try save(node, name: name) }
        else {
            let file = directory.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        }
    }
    func cachePlatformProfile(_ profile: PlatformProfile, for node: PlatformNode, platform: String) throws {
        try Task.checkCancellation()
        guard try loadPlatformNode() == node, PlatformDataCodec.identifier(platform) else { return }
        let name = "platform-profiles.json"
        let existing = try load(PlatformProfiles.self, name: name)
        let space = node.grant.space
        var profiles = existing?.origin == node.origin && existing?.space == space && existing?.key == node.grant.key ? existing?.profiles ?? [:] : [:]
        guard profiles[platform] != profile else { return }
        profiles[platform] = profile
        try save(PlatformProfiles(origin: node.origin, space: space, key: node.grant.key, profiles: profiles), name: name)
    }
    private struct PlatformProfiles: Codable {
        let origin: URL
        let space: String
        let key: String
        let profiles: [String: PlatformProfile]
    }
    public func loadPlaybackMode() throws -> PlaybackMode? { try load(PlaybackMode.self, name: "playback-mode.json") }
    public func savePlaybackMode(_ mode: PlaybackMode) throws { try save(mode, name: "playback-mode.json") }
    public func loadPreferences() throws -> AppPreferences? { try load(AppPreferences.self, name: "preferences.json") }
    public func savePreferences(_ settings: AppPreferences) throws { try save(settings, name: "preferences.json") }

    private func load<T: Decodable>(_ type: T.Type, name: String) throws -> T? {
        let url = directory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }

    private func save<T: Encodable>(_ value: T, name: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        #if os(iOS)
        try JSONEncoder().encode(value).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        #endif
        var resourceURL = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try resourceURL.setResourceValues(values)
    }
}
