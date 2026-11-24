import CryptoKit
import Foundation
import Observation

struct PlatformDataEnvelope: Codable, Equatable, Sendable {
    let format: Int
    let nonce: String
    let ciphertext: String
}

struct PlatformProfile: Codable, Equatable, Sendable {
    let accountID: String
    let displayName: String
    enum CodingKeys: String, CodingKey { case accountID = "account_id", displayName = "display_name" }
    var valid: Bool { (1...1024).contains(accountID.utf8.count) && (1...512).contains(displayName.utf8.count) }
}

enum PlatformDataFailure: LocalizedError {
    case address, invalid, otherAccount, keyChanged, http(Int, String?)
    var errorDescription: String? {
        switch self {
        case .address: "请输入 HTTPS 节点地址，不带路径。"
        case .invalid: "节点返回的数据无效，未覆盖本地资料。"
        case .otherAccount: "云端资料属于另一个账号，未覆盖。"
        case .keyChanged: "节点密钥已变化，请核实地址，再解除旧绑定并重新接入。"
        case .http(401, _): "节点授权失效，请重新接入。"
        case .http(403, _): "节点拒绝访问，请核对密码或设备授权。"
        case .http(404, _): "节点不支持此同步功能。"
        case .http(410, _): "云端资料已删除，未重新创建。"
        case .http(412, _): "云端资料已变更，未覆盖。"
        case .http(let status, _): "MB Server HTTP \(status)"
        }
    }
}

enum PlatformDataCodec {
    static let limit = 128 << 10
    static func identifier(_ value: String) -> Bool {
        value.range(of: #"\A[a-z0-9][a-z0-9_-]{0,63}\z"#, options: .regularExpression) != nil
    }
    static func decode(_ value: String, size: Int? = nil) -> Data? {
        let base = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard let decoded = Data(base64Encoded: base + String(repeating: "=", count: (4 - base.count % 4) % 4)),
              size == nil || decoded.count == size, encode(decoded) == value else { return nil }
        return decoded
    }
    static func encode(_ value: Data) -> String {
        value.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func key(_ recovery: String, space: String, purpose: String = "platform-data") throws -> SymmetricKey {
        guard identifier(space), let input = decode(recovery, size: 32) else { throw PlatformDataFailure.invalid }
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: input), salt: Data("mb-sync-v1\n\(space)".utf8),
                                      info: Data(purpose.utf8), outputByteCount: 32)
    }
    private static func aad(_ space: String, _ platform: String, _ key: String, _ format: Int) throws -> Data {
        guard identifier(platform), identifier(key), (1...65_535).contains(format) else { throw PlatformDataFailure.invalid }
        return Data("mb-data-v1\n\(space)\n\(platform)\n\(key)\n\(format)".utf8)
    }
    static func seal(_ plain: Data, recovery: String, space: String, platform: String, key name: String) throws -> PlatformDataEnvelope {
        let sealed = try AES.GCM.seal(plain, using: key(recovery, space: space), authenticating: aad(space, platform, name, 1))
        return PlatformDataEnvelope(format: 1, nonce: encode(Data(sealed.nonce)), ciphertext: encode(sealed.ciphertext + sealed.tag))
    }
    static func open(_ envelope: PlatformDataEnvelope, recovery: String, space: String, platform: String, key name: String) throws -> Data {
        guard envelope.ciphertext.utf8.count <= limit, let bytes = decode(envelope.ciphertext), bytes.count >= 16,
              let nonce = decode(envelope.nonce, size: 12) else { throw PlatformDataFailure.invalid }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
        return try AES.GCM.open(box, using: key(recovery, space: space), authenticating: aad(space, platform, name, envelope.format))
    }
}

struct PlatformNode: Codable, Equatable, Sendable {
    let origin: URL
    let grant: Grant
    struct Grant: Codable, Equatable, Sendable {
        let device: String
        let space: String
        let token: String
        let recovery: String
        let key: String
        let expires: Int64
        enum CodingKeys: String, CodingKey {
            case device = "device_id", space = "space_id", token, recovery = "recovery_key", key = "catalog_public_key", expires = "expires_at"
        }
        var valid: Bool {
            PlatformDataCodec.identifier(space) && PlatformDataCodec.decode(device, size: 16) != nil &&
            [token, recovery, key].allSatisfy { PlatformDataCodec.decode($0, size: 32) != nil }
        }
    }
    var active: Bool { grant.expires > Int64(Date().timeIntervalSince1970) }
}

struct PlatformDataClient: Sendable {
    typealias Transport = @Sendable (URLRequest, Int) async throws -> (Data, URLResponse)
    var transport: Transport = { try await NetworkClient.shared.data(for: $0, maximumBytes: $1, followRedirects: false) }

    static func origin(_ input: String) throws -> URL {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var parts = URLComponents(string: text.contains("://") ? text : "https://" + text),
              parts.scheme?.lowercased() == "https", let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.percentEncodedPath.isEmpty || parts.percentEncodedPath == "/",
              parts.port.map({ (1...65_535).contains($0) }) ?? true else { throw PlatformDataFailure.address }
        parts.scheme = "https"
        parts.host = host.hasSuffix(".") ? String(host.dropLast()) : host
        parts.path = ""
        if parts.port == 443 { parts.port = nil }
        guard !host.contains("%"), let url = parts.url else { throw PlatformDataFailure.address }
        return url
    }

    func request(_ origin: URL, _ path: String, method: String, expected: Int, token: String? = nil,
                         revision: String? = nil, body: Data? = nil, maximumBytes: Int = PlatformDataCodec.limit) async throws -> (Data, HTTPURLResponse) {
        guard var parts = URLComponents(url: origin, resolvingAgainstBaseURL: false) else { throw PlatformDataFailure.address }
        parts.path = path
        guard let url = parts.url else { throw PlatformDataFailure.address }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        request.httpMethod = method
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let revision { request.setValue(revision, forHTTPHeaderField: revision == "*" ? "If-None-Match" : "If-Match") }
        if let body { request.httpBody = body; request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, result) = try await transport(request, maximumBytes)
        guard let response = result as? HTTPURLResponse else { throw PlatformDataFailure.invalid }
        guard response.statusCode == expected else {
            let kind = response.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("application/json") == true && data.count < 1024
                ? (try? JSONDecoder().decode([String: String].self, from: data)["error"]) : nil
            throw PlatformDataFailure.http(response.statusCode, kind)
        }
        return (data, response)
    }

    func join(_ origin: URL, password: String) async throws -> PlatformNode {
        let body = try JSONEncoder().encode(["password": password, "name": "MB iOS", "platform": "ios"])
        guard body.count <= PlatformDataCodec.limit else { throw PlatformDataFailure.invalid }
        let (data, _) = try await request(origin, "/v1/join", method: "POST", expected: 201, body: body)
        let grant = try JSONDecoder().decode(PlatformNode.Grant.self, from: data)
        let node = PlatformNode(origin: origin, grant: grant)
        guard grant.valid, node.active else { throw PlatformDataFailure.invalid }
        return node
    }

    private func path(_ platform: String, _ key: String) throws -> String {
        guard PlatformDataCodec.identifier(platform), PlatformDataCodec.identifier(key) else { throw PlatformDataFailure.invalid }
        return "/v1/data/\(platform)/\(key)"
    }
    func tag(_ response: HTTPURLResponse) throws -> String {
        guard let tag = response.value(forHTTPHeaderField: "ETag"), tag.first == "\"", tag.last == "\"",
              PlatformDataCodec.decode(String(tag.dropFirst().dropLast()), size: 16) != nil else { throw PlatformDataFailure.invalid }
        return tag
    }
    func get(_ node: PlatformNode, platform: String, key: String) async throws -> (PlatformDataEnvelope, String) {
        let (data, response) = try await request(node.origin, path(platform, key), method: "GET", expected: 200, token: node.grant.token)
        return (try JSONDecoder().decode(PlatformDataEnvelope.self, from: data), try tag(response))
    }
    func change(_ node: PlatformNode, platform: String, key: String, revision: String, envelope: PlatformDataEnvelope?) async throws {
        let body = try envelope.map { try JSONEncoder().encode($0) }
        let validTag = revision.hasPrefix("\"") && revision.hasSuffix("\"") && PlatformDataCodec.decode(String(revision.dropFirst().dropLast()), size: 16) != nil
        guard validTag || revision == "*" && envelope != nil, body.map({ $0.count <= PlatformDataCodec.limit }) ?? true else { throw PlatformDataFailure.invalid }
        let (_, response) = try await request(node.origin, path(platform, key), method: envelope == nil ? "DELETE" : "PUT",
                                             expected: revision == "*" ? 201 : 204, token: node.grant.token, revision: revision, body: body)
        _ = try tag(response)
    }

    func sync(_ node: PlatformNode, platform: String, local: PlatformProfile, publish: Bool) async throws -> PlatformProfile {
        struct Payload: Codable { let value: PlatformProfile }
        func read() async throws -> (PlatformDataEnvelope, String) {
            for attempt in 0..<2 {
                try Task.checkCancellation()
                do { return try await get(node, platform: platform, key: "profile") }
                catch where Self.retryable(error) { try await Task.sleep(for: .milliseconds(250 * (attempt + 1))) }
            }
            try Task.checkCancellation()
            return try await get(node, platform: platform, key: "profile")
        }
        let revision: String
        do {
            let (envelope, tag) = try await read()
            guard envelope.format == 1 else { throw PlatformDataFailure.invalid }
            let data = try PlatformDataCodec.open(envelope, recovery: node.grant.recovery, space: node.grant.space, platform: platform, key: "profile")
            let profile = try JSONDecoder().decode(Payload.self, from: data).value
            guard profile.valid else { throw PlatformDataFailure.invalid }
            guard profile.accountID == local.accountID else { throw PlatformDataFailure.otherAccount }
            if !publish || profile == local { return profile }
            revision = tag
        } catch PlatformDataFailure.http(404, "not_found") {
            revision = "*"
        }
        let envelope = try PlatformDataCodec.seal(JSONEncoder().encode(Payload(value: local)), recovery: node.grant.recovery,
                                                  space: node.grant.space, platform: platform, key: "profile")
        try Task.checkCancellation()
        do { try await change(node, platform: platform, key: "profile", revision: revision, envelope: envelope) }
        catch {
            let isConflict: Bool
            if case PlatformDataFailure.http(412, _) = error { isConflict = true } else { isConflict = false }
            guard Self.retryable(error) || isConflict, let observed = try? await read(), observed.0 == envelope else { throw error }
        }
        return local
    }

    private static func retryable(_ error: any Error) -> Bool {
        if case PlatformDataFailure.http(let status, _) = error { return (500...599).contains(status) }
        guard let url = error as? URLError else { return false }
        return [.timedOut, .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost, .dnsLookupFailed].contains(url.code)
    }
}

@MainActor @Observable public final class PlatformDataModel {
    public var address = ""
    public var password = ""
    public private(set) var busy = false
    public private(set) var syncing = false
    public private(set) var message: String?
    public private(set) var revision = 0
    public var linked: Bool { node != nil }
    public var authorized: Bool { node?.active == true }
    @ObservationIgnored let client = PlatformDataClient()
    @ObservationIgnored let persistence: AppPersistence
    @ObservationIgnored private var profileTask: Task<Void, Never>?
    private(set) var node: PlatformNode?
    public private(set) var cloudRecords: [NodeCloudRecord]?
    public private(set) var cloudStatus: String?
    public private(set) var sessionStatus: String?
    public private(set) var sessionPlatform: String?
    public private(set) var sessionBusy = false
    @ObservationIgnored private var sessionOperation: (UUID, ObjectIdentifier, Int, Int, Bool)?

    public init(persistence: AppPersistence = .shared) { self.persistence = persistence }

    public func restore() async {
        let generation = revision
        do {
            let stored = try await persistence.loadPlatformNode()
            guard generation == revision, !Task.isCancelled, !busy else { return }
            node = stored
            address = stored?.origin.absoluteString ?? ""
            revision += 1
        } catch { if generation == revision { message = error.localizedDescription } }
    }

    public func join() async {
        guard !busy, !password.isEmpty else { return }
        busy = true; message = nil
        cancelProfileSync()
        defer { busy = false }
        do {
            let origin = try PlatformDataClient.origin(address)
            let joined = try await client.join(origin, password: password)
            try Task.checkCancellation()
            if let node, node.origin == origin, node.grant.key != joined.grant.key { throw PlatformDataFailure.keyChanged }
            try await persistence.savePlatformNode(joined)
            node = joined; address = origin.absoluteString; password = ""; revision += 1
            cloudRecords = nil; cloudStatus = nil; sessionStatus = nil
            message = "已接入节点，本机可以同步。"
        } catch { if !Task.isCancelled { message = error.localizedDescription } }
    }

    public func unlink() async {
        guard !busy else { return }
        busy = true; message = nil
        cancelProfileSync()
        defer { busy = false }
        do {
            try await persistence.savePlatformNode(nil); node = nil; revision += 1
            cloudRecords = nil; cloudStatus = nil; sessionStatus = nil
            message = "本机已解绑，现有登录和云端记录未变更。"
        }
        catch { message = error.localizedDescription }
    }

    public func cancelProfileSync() { profileTask?.cancel(); profileTask = nil; syncing = false }

    public func refreshCloudStatus() async {
        guard let node, node.active, !busy else { return }
        let generation = revision
        cloudRecords = nil; cloudStatus = "正在查询云端记录…"
        do {
            let records = try await client.overview(node)
            guard revision == generation, !Task.isCancelled else { return }
            cloudRecords = records; cloudStatus = nil
        } catch {
            if revision == generation, !Task.isCancelled { cloudStatus = error.localizedDescription }
        }
    }

    public func syncSignIn(_ account: AccountModel, platform: String, publish: Bool, manual: Bool = false) async {
        guard let node, node.active, !busy, account.canUseCloud else { return }
        let generation = revision
        let accountRevision = account.revision
        let owner = ObjectIdentifier(account)
        let operation = UUID()
        if let current = sessionOperation, current.1 == owner, current.2 == generation, current.3 == accountRevision && current.4 { return }
        sessionOperation = (operation, owner, generation, accountRevision, manual)
        let stillCurrent: @MainActor @Sendable () -> Bool = {
            self.sessionOperation?.0 == operation && self.revision == generation && !self.busy && account.revision == accountRevision && !Task.isCancelled
        }
        sessionBusy = true
        sessionPlatform = platform
        sessionStatus = publish ? "正在同步登录会话…" : "正在查找云端登录…"
        var restoredAccount = false
        defer {
            if sessionOperation?.0 == operation {
                sessionOperation = nil; sessionBusy = false
                if Task.isCancelled && !restoredAccount { sessionStatus = nil }
            }
        }
        do {
            if publish {
                let digest = try await account.verifyCloudPlugin(client: client, node: node)
                guard stillCurrent() else { return }
                guard let exported = try await account.exportCloudSignIn(digest: digest), stillCurrent() else { return }
                try await client.publishSession(node, platform: platform, payload: exported, stillCurrent: stillCurrent)
                if stillCurrent() { account.cloudUploadCompleted(); sessionStatus = "登录会话已存到节点。" }
            } else {
                if try await persistence.isCloudSignInBlocked(platform) {
                    if manual, stillCurrent() { sessionStatus = "本机已主动退出，请先在本机登录。" }
                    else if stillCurrent() { sessionStatus = nil }
                    return
                }
                let payload = try await client.sessionToRestore(node, platform: platform)
                guard stillCurrent() else { return }
                if let payload {
                    let digest = try await account.verifyCloudPlugin(client: client, node: node)
                    guard stillCurrent() else { return }
                    let restored = try await account.restoreCloudSignIn(payload, digest: digest, stillCurrent: stillCurrent)
                    restoredAccount = restored != nil
                    if sessionOperation?.0 == operation, revision == generation, restored != nil || stillCurrent() {
                        sessionStatus = restored.map { "本机已登录：\($0.name)。" } ?? "平台未确认此会话，请在本机重新登录。"
                    }
                } else { sessionStatus = manual ? "云端没有可恢复的登录会话。" : nil }
            }
        } catch { if stillCurrent() { sessionStatus = error.localizedDescription } }
    }

    /// 插件缓存与平台资料共用当前绑定；回调必须与本地编辑串行，并在落盘前检查取消。
    public func syncPluginPreferences(load: @MainActor () async throws -> PluginPreferences,
                                     merge: @MainActor (PluginPreferences) async throws -> PluginPreferences) async {
        guard !busy, let node, node.active else { return }
        let generation = revision
        do {
            try await client.syncPluginPreferences(node, load: load, merge: merge, stillCurrent: { generation == self.revision && !self.busy })
        } catch is CancellationError { }
        catch { if generation == revision, !Task.isCancelled { message = error.localizedDescription } }
    }

    public func syncDiscoveryPreferences(load: @MainActor () async throws -> DiscoveryPreferences,
                                         merge: @MainActor (DiscoveryPreferences) async throws -> DiscoveryPreferences) async {
        guard !busy, let node, node.active else { return }
        let generation = revision
        do { try await client.syncDiscoveryPreferences(node, load: load, merge: merge, stillCurrent: { generation == self.revision && !self.busy }) }
        catch is CancellationError { }
        catch { if generation == revision, !Task.isCancelled { message = error.localizedDescription } }
    }

    public func sync(_ account: SourceAccount?, platform: String, publish: Bool = false) {
        cancelProfileSync()
        guard !busy, let node, node.active, let account, PlatformDataCodec.identifier(platform) else { return }
        let profile = PlatformProfile(accountID: account.id, displayName: account.name)
        guard profile.valid else { return }
        let generation = revision
        if publish { message = nil }
        syncing = true
        profileTask = Task {
            defer { if generation == revision, !Task.isCancelled { syncing = false } }
            do {
                let synced = try await client.sync(node, platform: platform, local: profile, publish: publish)
                guard generation == revision, !Task.isCancelled else { return }
                try await persistence.cachePlatformProfile(synced, for: node, platform: platform)
                if generation == revision, !Task.isCancelled, publish { message = "账号资料已同步，未上传登录会话。" }
            } catch { if generation == revision, !Task.isCancelled { message = error.localizedDescription } }
        }
    }
}
