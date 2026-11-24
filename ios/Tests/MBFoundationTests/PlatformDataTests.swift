import Foundation
@testable import MBFoundation
import Testing

private let recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

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
