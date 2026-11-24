import Foundation
@testable import MBFoundation
import Testing

private actor CacheCounter {
    var calls = 0
    func next() -> Data { calls += 1; return Data(String(calls).utf8) }
}
private actor CacheGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var observer: CheckedContinuation<Void, Never>?
    func pause() async { await withCheckedContinuation { continuation = $0; observer?.resume(); observer = nil } }
    func wait() async { if continuation == nil { await withCheckedContinuation { observer = $0 } } }
    func release() { continuation?.resume(); continuation = nil }
}
private func cacheDirectory() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("mb-cache-test-" + UUID().uuidString) }

@Test func responseCachePersistsAcrossInstancesAndSeparatesKeys() async throws {
    let root = cacheDirectory(), counter = CacheCounter()
    defer { try? FileManager.default.removeItem(at: root) }
    let first = ResponseCache(directory: root)
    #expect(try await first.data(key: "platform:a", lifetime: 3600) { await counter.next() } == Data("1".utf8))
    let reopened = ResponseCache(directory: root)
    #expect(try await reopened.data(key: "platform:a", lifetime: 3600) { await counter.next() } == Data("1".utf8))
    #expect(try await reopened.data(key: "platform:b", lifetime: 3600) { await counter.next() } == Data("2".utf8))
    #expect(await counter.calls == 2)
    await reopened.invalidate()
    #expect(try await reopened.data(key: "platform:a", lifetime: 3600) { await counter.next() } == Data("3".utf8))
}

@Test func responseCacheCoalescesConcurrentLoadsAndInvalidationRejectsLateResult() async throws {
    let cache = ResponseCache(), counter = CacheCounter(), gate = CacheGate()
    let first = Task { try await cache.data(key: "cover", lifetime: 3600) { await gate.pause(); return await counter.next() } }
    await gate.wait()
    let second = Task { try await cache.data(key: "cover", lifetime: 3600) { await counter.next() } }
    await gate.release()
    #expect(try await first.value == second.value)
    #expect(await counter.calls == 1)
    let lateGate = CacheGate()
    let late = Task { try await cache.data(key: "late", lifetime: 3600) { await lateGate.pause(); return Data("old".utf8) } }
    await lateGate.wait()
    await cache.invalidate()
    await lateGate.release()
    await #expect(throws: CancellationError.self) { try await late.value }
    #expect(try await cache.data(key: "late", lifetime: 3600) { Data("new".utf8) } == Data("new".utf8))
}

@Test func responseCacheRefreshFailureKeepsOldDataAndBudgetEvictsOldFiles() async throws {
    let root = cacheDirectory()
    defer { try? FileManager.default.removeItem(at: root) }
    let cache = ResponseCache(directory: root, maximumBytes: 300)
    let body = Data(repeating: 65, count: 100)
    _ = try await cache.data(key: "one", lifetime: 3600) { body }
    #expect(try await cache.data(key: "one", lifetime: 0) { throw URLError(.notConnectedToInternet) } == body)
    _ = try await cache.data(key: "two", lifetime: 3600) { body }
    let total = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey])
        .reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    #expect(total <= 300)
    _ = try await cache.data(key: "one", lifetime: 3600) { Data("replacement".utf8) }
    let files = try FileManager.default.contentsOfDirectory(atPath: root.path)
    #expect(files.allSatisfy { $0.count == 64 })
}

@Test func artworkCacheReusesDiskAndSeparatesPlatformAndCredentialHeaders() async throws {
    let root = cacheDirectory(), counter = CacheCounter()
    defer { try? FileManager.default.removeItem(at: root) }
    let png = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))
    let transport: ArtworkCache.Transport = { request in
        _ = await counter.next()
        return (png, try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Cache-Control": "no-store"])))
    }
    let url = URL(string: "https://image.example.test/cover")!
    let cache = ArtworkCache(directory: root, transport: transport)
    _ = try await cache.data(url: url, sourceID: "one", headers: ["Cookie": "fixture-a"])
    let reopened = ArtworkCache(directory: root, transport: transport)
    _ = try await reopened.data(url: url, sourceID: "one", headers: ["cookie": "fixture-a"])
    #expect(await counter.calls == 1)
    _ = try await reopened.data(url: url, sourceID: "one", headers: ["Cookie": "fixture-b"])
    _ = try await reopened.data(url: url, sourceID: "two", headers: ["Cookie": "fixture-a"])
    #expect(await counter.calls == 3)
    let names = try FileManager.default.contentsOfDirectory(atPath: root.path)
    #expect(names.allSatisfy { !$0.contains("fixture") && !$0.contains("Cookie") })
}

@Test func artworkCacheDoesNotPersistErrorPages() async throws {
    let root = cacheDirectory(), counter = CacheCounter()
    defer { try? FileManager.default.removeItem(at: root) }
    let cache = ArtworkCache(directory: root) { request in
        _ = await counter.next()
        return (Data("<html>login</html>".utf8), try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)))
    }
    for _ in 0..<2 {
        await #expect(throws: NetworkFailure.self) { try await cache.data(url: URL(string: "https://image.example.test/cover")!, sourceID: "one", headers: [:]) }
    }
    #expect(await counter.calls == 2)
}
