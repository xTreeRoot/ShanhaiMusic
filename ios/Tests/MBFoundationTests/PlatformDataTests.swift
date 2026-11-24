import CryptoKit
import Foundation
@testable import MBFoundation
import Testing

private let recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

@Test func nodeSessionOpensDesktopVectorAndRejectsOtherPlugin() throws {
    let node = PlatformNode(origin: try PlatformDataClient.origin("fixture.test"),
                            grant: .init(device: "AAECAwQFBgcICQoLDA0ODw", space: "test-space", token: recovery, recovery: recovery, key: recovery, expires: .max))
    let ref = "rQgGydt1-xJIAjpM4GXEO4Q0GyBkb4Sro0a5saijxaY"
    let wire = PlatformDataEnvelope(format: 1, nonce: "AAECAwQFBgcICQoL",
        ciphertext: "4pKzDTCYmD-RlfPCsaqP3vVTdyYeYH7RZNXWMsAplG6Y_w3pJB2uUQ-IuPszwsAhbCxTVArDavI3up94c68j9YUzB1KJ1Tnm")
    let read = try NodeSessionCodec.open(wire, node: node, platform: "example", ref: ref)
    #expect(read.accountID == "user-123")
    #expect(read.session["demo"].string == "synthetic")
    #expect(try NodeSessionCodec.reference(read.accountID, node: node, platform: "example") == ref)
    #expect(throws: (any Error).self) { try NodeSessionCodec.open(wire, node: node, platform: "other", ref: ref) }
    let resealed = try NodeSessionCodec.seal(read, format: 1, node: node, platform: "example", ref: ref)
    #expect(resealed.nonce != wire.nonce)
    #expect(try NodeSessionCodec.open(resealed, node: node, platform: "example", ref: ref).accountID == read.accountID)
}

@Test func nodeSessionCatalogRejectsTamperingAndSameIDReplacement() async throws {
    let signingKey = Curve25519.Signing.PrivateKey()
    let origin = try PlatformDataClient.origin("fixture.test")
    let node = PlatformNode(origin: origin, grant: .init(device: "AAECAwQFBgcICQoLDA0ODw", space: "test-space",
        token: recovery, recovery: recovery, key: PlatformDataCodec.encode(signingKey.publicKey.rawRepresentation), expires: .max))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false)
    defer { try? FileManager.default.removeItem(at: folder) }
    try Data("{}".utf8).write(to: folder.appendingPathComponent("plugin.json"))
    try Data("example".utf8).write(to: folder.appendingPathComponent("index.js"))
    try Data("metadata".utf8).write(to: folder.appendingPathComponent(".mb-origin.json"))
    let digest = try NodePluginContent.digest(folder)
    #expect(digest == "390039247f1da0ab6446e475561ec319f02d20fe8fca759719d02e75358c4bee")
    let payload = Data("""
        {"protocol":1,"revision":1,"plugins":[{"id":"example","version":"1.0.0","content_sha256":"\(digest)"}]}
        """.utf8)
    let signature = try signingKey.signature(for: payload)
    let valid = try JSONEncoder().encode(["payload": PlatformDataCodec.encode(payload), "signature": PlatformDataCodec.encode(signature)])
    let forged = try JSONEncoder().encode(["payload": PlatformDataCodec.encode(payload + Data(" ".utf8)), "signature": PlatformDataCodec.encode(signature)])
    let client = PlatformDataClient { request, _ in
        #expect(request.url?.path == "/v1/catalog")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        return (valid, try #require(HTTPURLResponse(url: origin, statusCode: 200, httpVersion: nil, headerFields: nil)))
    }
    try await client.verifySessionPlugin(node, id: "example", version: "1.0.0", digest: digest)
    await #expect(throws: NodeSessionFailure.untrustedPlugin) {
        try await client.verifySessionPlugin(node, id: "example", version: "1.0.0", digest: String(repeating: "b", count: 64))
    }
    let modified = PlatformDataClient { _, _ in
        (forged, try #require(HTTPURLResponse(url: origin, statusCode: 200, httpVersion: nil, headerFields: nil)))
    }
    await #expect(throws: NodeSessionFailure.untrustedPlugin) {
        try await modified.verifySessionPlugin(node, id: "example", version: "1.0.0", digest: digest)
    }
}

@Test func platformDataVectorBindsCiphertextToSpacePlatformAndKey() throws {
    let sealed = PlatformDataEnvelope(format: 1, nonce: "AAECAwQFBgcICQoL", ciphertext: "1CiWbBgaQJw8-59H-Td2k9vAIU8_zVxQKtQ5h2qz8sxykNcXpd4_KjnyCXHP5GHzDDuqEw")
    let key = try PlatformDataCodec.key(recovery, space: "test-space")
    #expect(key.withUnsafeBytes { PlatformDataCodec.encode(Data($0)) } == "jMZa0IZgOPiLSkJz1EqCZDIcV_iuXtq_XuV857DCPm4")
    let plain = try PlatformDataCodec.open(sealed, recovery: recovery, space: "test-space", platform: "soda", key: "profile")
    #expect(String(decoding: plain, as: UTF8.self) == #"{"value":{"display_name":"Fixture"}}"#)
    #expect(throws: (any Error).self) { try PlatformDataCodec.open(sealed, recovery: recovery, space: "test-space", platform: "soda", key: "other") }
    let outbound = try PlatformDataCodec.seal(plain, recovery: recovery, space: "test-space", platform: "soda", key: "profile")
    #expect(outbound.nonce != sealed.nonce)
    #expect(try PlatformDataCodec.open(outbound, recovery: recovery, space: "test-space", platform: "soda", key: "profile") == plain)
}

private actor PlatformNodeFixture {
    let origin: URL
    private let tag = "\"AAECAwQFBgcICQoLDA0ODw\""
    var current: Data?
    private(set) var writes = 0
    var rejectWrites = false
    var missingCode = "not_found"
    init(origin: URL) { self.origin = origin }
    func reply(_ request: URLRequest) throws -> (Data, URLResponse) {
        let url = try #require(request.url)
        #expect(url.path == "/v1/data/soda/profile")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer " + recovery)
        var status = 200
        var body = Data()
        if request.httpMethod == "GET" {
            if let current { body = current }
            else { status = 404; body = try JSONEncoder().encode(["error": missingCode]) }
        } else {
            writes += 1
            if current == nil { #expect(request.value(forHTTPHeaderField: "If-None-Match") == "*"); status = 201 }
            else { #expect(request.value(forHTTPHeaderField: "If-Match") == tag); status = 204 }
            if rejectWrites { status = 412 } else { current = request.httpBody }
        }
        let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: ["ETag": tag, "Content-Type": "application/json"]))
        return (body, response)
    }
    func simulateStaleWrites() { rejectWrites = true }
    func simulateOldNode() { missingCode = "unsupported" }
    func enableData() { missingCode = "not_found" }
}

@Test func platformDataConditionalUpdateNeverOverwritesAnotherAccountOrConflict() async throws {
    let origin = try PlatformDataClient.origin(" https://fixture.test:443/ ")
    #expect(origin.absoluteString == "https://fixture.test")
    for invalid in ["http://fixture.test", "https://fixture.test/path", "https://name@fixture.test", "https://fixture.test/?secret=bad"] {
        #expect(throws: (any Error).self) { try PlatformDataClient.origin(invalid) }
    }
    let fixture = PlatformNodeFixture(origin: origin)
    let node = PlatformNode(origin: origin, grant: .init(device: "AAECAwQFBgcICQoLDA0ODw", space: "test-space", token: recovery, recovery: recovery, key: recovery, expires: .max))
    let client = PlatformDataClient { request, _ in try await fixture.reply(request) }
    let local = PlatformProfile(accountID: "listener", displayName: "Fixture")
    await fixture.simulateOldNode()
    await #expect(throws: (any Error).self) { try await client.sync(node, platform: "soda", local: local, publish: true) }
    #expect(await fixture.writes == 0)
    await fixture.enableData()
    #expect(try await client.sync(node, platform: "soda", local: local, publish: false) == local)
    let renamed = PlatformProfile(accountID: "listener", displayName: "Desktop")
    #expect(try await client.sync(node, platform: "soda", local: renamed, publish: true) == renamed)
    #expect(try await client.sync(node, platform: "soda", local: local, publish: false) == renamed)
    await #expect(throws: (any Error).self) { try await client.sync(node, platform: "soda", local: .init(accountID: "other", displayName: "Other"), publish: true) }
    #expect(await fixture.writes == 2)
    await fixture.simulateStaleWrites()
    await #expect(throws: (any Error).self) { try await client.sync(node, platform: "soda", local: local, publish: true) }
    #expect(await fixture.writes == 3)
    #expect(try await client.sync(node, platform: "soda", local: local, publish: false) == renamed)
}
