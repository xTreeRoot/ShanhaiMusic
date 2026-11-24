import CryptoKit
import Foundation

public struct NodeCloudRecord: Decodable, Sendable, Identifiable {
    public let kind: String
    public let platform: String
    public let key: String?
    public let count: Int
    public var id: String { "\(kind)/\(platform)/\(key ?? "")" }
}

struct NodeSessionPayload: Codable, Sendable {
    let accountID: String
    let label: String?
    let session: PluginJSON
    enum CodingKeys: String, CodingKey { case accountID = "account_id", label = "account_label", session }

    var valid: Bool {
        if case .object = session { return !accountID.isEmpty && accountID.utf8.count <= 1024 }
        return false
    }

    func arguments(format: Int, account: PluginJSON? = nil) -> PluginJSON {
        var fields: [String: PluginJSON] = ["account_id": .string(accountID), "session": session, "format": .number(Double(format))]
        if let label { fields["account_label"] = .string(label) }
        if let account { fields["account"] = account }
        return .object(fields)
    }
}

enum NodeSessionFailure: LocalizedError {
    case multiple, unverified, untrustedPlugin
    var errorDescription: String? {
        switch self {
        case .multiple: "云端有多个账号，没自动选择。请在本机登录你要用的账号。"
        case .unverified: "插件没有返回与本机账号匹配的会话。"
        case .untrustedPlugin: "请先安装与该节点签名目录一致的插件版本，再同步登录。"
        }
    }
}

enum NodePluginContent {
    static func digest(_ root: URL) throws -> String {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey]
        let path = root.standardizedFileURL.path + "/"
        var enumerationFailed = false
        guard let entries = FileManager.default.enumerator(at: root, includingPropertiesForKeys: Array(keys), errorHandler: { _, _ in
            enumerationFailed = true; return false
        }) else { throw NodeSessionFailure.untrustedPlugin }
        var remaining = 32 << 20
        var files: [String: SHA256.Digest] = [:]
        for case let file as URL in entries {
            let full = file.standardizedFileURL.path
            guard full.hasPrefix(path) else { throw NodeSessionFailure.untrustedPlugin }
            let name = String(full.dropFirst(path.count))
            if name == ".origin.json" || name == ".mb-origin.json" { continue }
            let info = try file.resourceValues(forKeys: keys)
            if info.isDirectory == true, info.isSymbolicLink != true { continue }
            guard files.count < 1024, name.range(of: #"\A[A-Za-z0-9_./@+ -]+\z"#, options: .regularExpression) != nil,
                  info.isSymbolicLink != true, info.isRegularFile == true, let size = info.fileSize, size <= remaining else {
                throw NodeSessionFailure.untrustedPlugin
            }
            let data = try Data(contentsOf: file)
            guard data.count == size else { throw NodeSessionFailure.untrustedPlugin }
            remaining -= size
            files[name] = SHA256.hash(data: data)
        }
        guard !enumerationFailed, !files.isEmpty else { throw NodeSessionFailure.untrustedPlugin }
        var digest = SHA256()
        for name in files.keys.sorted() {
            digest.update(data: Data((name + "\0").utf8))
            if let bytes = files[name] { digest.update(data: Data(bytes)) }
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

enum NodeSessionCodec {
    static func reference(_ account: String, node: PlatformNode, platform: String) throws -> String {
        guard PlatformDataCodec.identifier(platform) else { throw PlatformDataFailure.invalid }
        let key = try PlatformDataCodec.key(node.grant.recovery, space: node.grant.space, purpose: "account-ref")
        return PlatformDataCodec.encode(Data(HMAC<SHA256>.authenticationCode(for: Data("\(platform)\n\(account)".utf8), using: key)))
    }

    private static func context(_ node: PlatformNode, _ platform: String, _ ref: String, _ format: Int) throws -> (SymmetricKey, Data) {
        guard PlatformDataCodec.identifier(platform), PlatformDataCodec.decode(ref, size: 32) != nil,
              (1...65_535).contains(format) else { throw PlatformDataFailure.invalid }
        let key = try PlatformDataCodec.key(node.grant.recovery, space: node.grant.space, purpose: "session")
        return (key, Data("mb-session-v1\n\(node.grant.space)\n\(platform)\n\(ref)\n\(format)".utf8))
    }

    static func seal(_ payload: NodeSessionPayload, format: Int, node: PlatformNode, platform: String, ref: String) throws -> PlatformDataEnvelope {
        let (key, aad) = try context(node, platform, ref, format)
        let box = try AES.GCM.seal(JSONEncoder().encode(payload), using: key, authenticating: aad)
        return PlatformDataEnvelope(format: format, nonce: PlatformDataCodec.encode(Data(box.nonce)),
                                    ciphertext: PlatformDataCodec.encode(box.ciphertext + box.tag))
    }

    static func open(_ envelope: PlatformDataEnvelope, node: PlatformNode, platform: String, ref: String) throws -> NodeSessionPayload {
        let (key, aad) = try context(node, platform, ref, envelope.format)
        guard let nonce = PlatformDataCodec.decode(envelope.nonce, size: 12),
              let bytes = PlatformDataCodec.decode(envelope.ciphertext), bytes.count > 16 else { throw PlatformDataFailure.invalid }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
        let payload = try JSONDecoder().decode(NodeSessionPayload.self, from: AES.GCM.open(box, using: key, authenticating: aad))
        guard payload.valid, try reference(payload.accountID, node: node, platform: platform) == ref else { throw PlatformDataFailure.invalid }
        return payload
    }
}

extension PlatformDataClient {
    func verifySessionPlugin(_ node: PlatformNode, id: String, version: String, digest: String) async throws {
        struct Signed: Decodable { let payload: String; let signature: String }
        struct Catalog: Decodable {
            struct Item: Decodable { let id: String; let version: String; let content_sha256: String? }
            let `protocol`: Int; let revision: Int64; let plugins: [Item]
        }
        let (data, _) = try await request(node.origin, "/v1/catalog", method: "GET", expected: 200, maximumBytes: 1 << 20)
        guard let signed = try? JSONDecoder().decode(Signed.self, from: data),
              let key = PlatformDataCodec.decode(node.grant.key, size: 32),
              let payload = PlatformDataCodec.decode(signed.payload), let signature = PlatformDataCodec.decode(signed.signature, size: 64),
              try Curve25519.Signing.PublicKey(rawRepresentation: key).isValidSignature(signature, for: payload),
              let catalog = try? JSONDecoder().decode(Catalog.self, from: payload), catalog.protocol == 1, catalog.revision > 0,
              Set(catalog.plugins.map(\.id)).count == catalog.plugins.count,
              catalog.plugins.contains(where: { $0.id == id && $0.version == version && $0.content_sha256 == digest }) else {
            throw NodeSessionFailure.untrustedPlugin
        }
    }

    private struct SessionRecord: Decodable {
        let ref: String
        let etag: String
        let format: Int
        let deleted: Bool
        enum CodingKeys: String, CodingKey { case ref = "account_ref", etag, format, deleted }
    }

    func overview(_ node: PlatformNode) async throws -> [NodeCloudRecord] {
        struct Result: Decodable { let records: [NodeCloudRecord] }
        let (data, _) = try await request(node.origin, "/v1/sync/overview", method: "GET", expected: 200, token: node.grant.token)
        let records = try JSONDecoder().decode(Result.self, from: data).records
        guard Set(records.map(\.id)).count == records.count, records.allSatisfy({
            PlatformDataCodec.identifier($0.platform) && $0.count > 0 &&
            ($0.kind == "sessions" && $0.key == nil || $0.kind == "data" && $0.count == 1 && $0.key.map(PlatformDataCodec.identifier) == true)
        }) else { throw PlatformDataFailure.invalid }
        return records
    }

    private func sessions(_ node: PlatformNode, platform: String) async throws -> [SessionRecord] {
        struct Result: Decodable { let sessions: [SessionRecord] }
        guard PlatformDataCodec.identifier(platform) else { throw PlatformDataFailure.invalid }
        let (data, _) = try await request(node.origin, "/v1/sessions/\(platform)", method: "GET", expected: 200, token: node.grant.token)
        let entries = try JSONDecoder().decode(Result.self, from: data).sessions
        guard Set(entries.map(\.ref)).count == entries.count, entries.allSatisfy({
            PlatformDataCodec.decode($0.ref, size: 32) != nil && $0.etag.count == 24 && $0.etag.first == "\"" && $0.etag.last == "\"" &&
            PlatformDataCodec.decode(String($0.etag.dropFirst().dropLast()), size: 16) != nil && ($0.deleted ? $0.format == 0 : (1...65_535).contains($0.format))
        }) else { throw PlatformDataFailure.invalid }
        return entries
    }

    func sessionToRestore(_ node: PlatformNode, platform: String) async throws -> (Int, NodeSessionPayload)? {
        let entries = try await sessions(node, platform: platform).filter { !$0.deleted }
        guard let entry = entries.first else { return nil }
        guard entries.count == 1 else { throw NodeSessionFailure.multiple }
        let (data, response) = try await request(node.origin, "/v1/sessions/\(platform)/\(entry.ref)", method: "GET", expected: 200, token: node.grant.token)
        let envelope = try JSONDecoder().decode(PlatformDataEnvelope.self, from: data)
        guard try tag(response) == entry.etag, envelope.format == entry.format else { throw PlatformDataFailure.http(412, nil) }
        return (entry.format, try NodeSessionCodec.open(envelope, node: node, platform: platform, ref: entry.ref))
    }

    func publishSession(_ node: PlatformNode, platform: String, payload: (Int, NodeSessionPayload),
                        stillCurrent: @MainActor () -> Bool) async throws {
        let ref = try NodeSessionCodec.reference(payload.1.accountID, node: node, platform: platform)
        let entry = try await sessions(node, platform: platform).first { $0.ref == ref }
        guard entry?.deleted != true else { throw PlatformDataFailure.http(410, nil) }
        let envelope = try NodeSessionCodec.seal(payload.1, format: payload.0, node: node, platform: platform, ref: ref)
        let body = try JSONEncoder().encode(envelope)
        guard body.count <= PlatformDataCodec.limit else { throw PlatformDataFailure.invalid }
        guard await stillCurrent() else { throw CancellationError() }
        let (_, response) = try await request(node.origin, "/v1/sessions/\(platform)/\(ref)", method: "PUT",
            expected: entry == nil ? 201 : 204, token: node.grant.token, revision: entry?.etag ?? "*", body: body)
        _ = try tag(response)
    }
}
