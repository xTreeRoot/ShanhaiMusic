import CryptoKit
import Foundation

public struct NodeDeviceBackupReference: Codable, Equatable, Sendable {
    public let index: String
    public let platform: String
    public let created: Int64
    public let bytes: Int64
    public let sha256: String
    public let plugins: Int
    var valid: Bool {
        Self.validKey(index) && sha256.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil &&
        bytes > 0 && bytes <= 16 << 30 && ["desktop", "ios", "android"].contains(platform) && plugins >= 0
    }
    static func validKey(_ key: String) -> Bool {
        key.range(of: #"\A[a-f0-9]{32}\z"#, options: .regularExpression) != nil
    }
}

/// 逐块读写暂存 ZIP；只有完整解密、长度和摘要通过后才允许导入。
actor NodeDeviceBackupTransfer {
    static let chunkBytes = 2 << 20
    static let objectLimit = 4 << 20
    private let node: PlatformNode
    private let client: PlatformDataClient
    private var owner: String { node.grant.device.utf8.map { String(format: "%02x", $0) }.joined() }
    private struct Chunk: Codable { let data: String }
    private struct Payload: Codable { let value: Chunk }
    private struct Index: Codable {
        let version: Int
        let parts: [String]
        let bytes: Int64
        let sha256: String
    }
    init(node: PlatformNode, client: PlatformDataClient) { self.node = node; self.client = client }

    private func path(_ owner: String, _ key: String) throws -> String {
        guard PlatformDataCodec.identifier(owner), NodeDeviceBackupReference.validKey(key) else { throw PlatformDataFailure.invalid }
        return "/v1/backups/\(owner)/\(key)"
    }

    private func put(_ bytes: Data) async throws -> String {
        guard bytes.count <= Self.chunkBytes else { throw PlatformDataFailure.invalid }
        let key = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let payload = try JSONEncoder().encode(Payload(value: Chunk(data: PlatformDataCodec.encode(bytes))))
        let envelope = try PlatformDataCodec.seal(payload, recovery: node.grant.recovery, space: node.grant.space,
                                                platform: "backup-" + owner, key: key)
        let body = try JSONEncoder().encode(envelope)
        guard body.count <= Self.objectLimit else { throw PlatformDataFailure.invalid }
        do {
            _ = try await client.request(node.origin, path(owner, key), method: "PUT", expected: 201, token: node.grant.token,
                                         body: body, maximumBytes: 1024, timeout: 90)
        } catch {
            await Task.detached { await self.delete(keys: [key]) }.value
            throw error
        }
        return key
    }

    private func get(owner: String, key: String) async throws -> Data {
        let (body, _) = try await client.request(node.origin, path(owner, key), method: "GET", expected: 200,
                                                token: node.grant.token, maximumBytes: Self.objectLimit, timeout: 90)
        let envelope = try JSONDecoder().decode(PlatformDataEnvelope.self, from: body)
        guard envelope.format == 1 else { throw PlatformDataFailure.invalid }
        let plain = try PlatformDataCodec.open(envelope, recovery: node.grant.recovery, space: node.grant.space,
                                              platform: "backup-" + owner, key: key, maximumBytes: Self.objectLimit)
        let chunk = try JSONDecoder().decode(Payload.self, from: plain).value
        guard let bytes = PlatformDataCodec.decode(chunk.data), bytes.count <= Self.chunkBytes else { throw PlatformDataFailure.invalid }
        return bytes
    }

    func upload(file: URL, platform: String, plugins: Int) async throws -> NodeDeviceBackupReference {
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var keys: [String] = [], bytes: Int64 = 0, hash = SHA256()
        do {
            while true {
                try Task.checkCancellation()
                let data = try input.read(upToCount: Self.chunkBytes) ?? Data()
                if data.isEmpty { break }
                bytes += Int64(data.count)
                guard bytes <= 16 << 30 else { throw PlatformDataFailure.invalid }
                hash.update(data: data)
                keys.append(try await put(data))
            }
            guard bytes > 0 else { throw PlatformDataFailure.invalid }
            let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
            let index = Index(version: 1, parts: keys, bytes: bytes, sha256: digest)
            let key = try await put(JSONEncoder().encode(index))
            keys.append(key)
            let ref = NodeDeviceBackupReference(index: key, platform: platform, created: Int64(Date().timeIntervalSince1970),
                                               bytes: bytes, sha256: digest, plugins: plugins)
            guard ref.valid else { throw PlatformDataFailure.invalid }
            return ref
        } catch {
            let uploaded = keys
            await Task.detached { await self.delete(keys: uploaded) }.value
            throw error
        }
    }

    private func readIndex(owner: String, reference: NodeDeviceBackupReference) async throws -> Index {
        guard reference.valid else { throw PlatformDataFailure.invalid }
        let value = try JSONDecoder().decode(Index.self, from: await get(owner: owner, key: reference.index))
        guard value.version == 1, value.bytes == reference.bytes, value.sha256 == reference.sha256,
              value.parts.count == Int((reference.bytes + Int64(Self.chunkBytes) - 1) / Int64(Self.chunkBytes)),
              Set(value.parts).count == value.parts.count, value.parts.allSatisfy(NodeDeviceBackupReference.validKey),
              !value.parts.contains(reference.index) else { throw PlatformDataFailure.invalid }
        return value
    }

    func download(owner: String, reference: NodeDeviceBackupReference, to file: URL) async throws {
        let index = try await readIndex(owner: owner, reference: reference)
        guard FileManager.default.createFile(atPath: file.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let output = try FileHandle(forWritingTo: file)
        defer { try? output.close() }
        var bytes: Int64 = 0, hash = SHA256()
        for key in index.parts {
            try Task.checkCancellation()
            let data = try await get(owner: owner, key: key)
            guard data.count == min(Self.chunkBytes, Int(reference.bytes - bytes)) else { throw PlatformDataFailure.invalid }
            try output.write(contentsOf: data); hash.update(data: data); bytes += Int64(data.count)
        }
        guard bytes == reference.bytes, hash.finalize().map({ String(format: "%02x", $0) }).joined() == reference.sha256 else {
            throw PlatformDataFailure.invalid
        }
        try output.synchronize()
    }

    private func delete(keys: [String]) async {
        for key in keys {
            _ = try? await client.request(node.origin, path(owner, key), method: "DELETE", expected: 204,
                                          token: node.grant.token, maximumBytes: 1024, timeout: 90)
        }
    }

    func delete(reference: NodeDeviceBackupReference) async throws {
        let value = try await readIndex(owner: owner, reference: reference)
        await delete(keys: value.parts + [reference.index])
    }
}
