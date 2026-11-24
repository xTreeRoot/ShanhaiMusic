import Foundation
@testable import MBFoundation
import Testing

private actor SegmentFixture {
    private(set) var ranges: [String] = []
    var changed = false
    let total = MediaSegmentReader.block * 8
    func mutate() { changed = true }
    func read(_ request: URLRequest) throws -> (Data, URLResponse) {
        let header = try #require(request.value(forHTTPHeaderField: "Range"))
        ranges.append(header)
        let interval = header.dropFirst(6).split(separator: "-").compactMap { Int64($0) }
        #expect(request.value(forHTTPHeaderField: "Accept-Encoding") == "identity")
        if ranges.count > 1 { #expect(request.value(forHTTPHeaderField: "If-Range") == "\"first\"") }
        let status = changed ? 200 : 206
        let url = try #require(request.url)
        let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: [
            "Content-Range": "bytes \(interval[0])-\(interval[1])/\(total)", "ETag": "\"first\""
        ]))
        return (Data(repeating: 0x37, count: Int(interval[1] - interval[0] + 1)), response)
    }
}

@Test @MainActor func mediaSegmentsGoToTargetWithoutFetchingPrefixAndDiscardChangedResponse() async throws {
    let fixture = SegmentFixture()
    let url = try #require(URL(string: "https://fixture.test/movie.mp4"))
    let reader = MediaSegmentReader(resource: MediaResource(url: url, headers: [:], fileExtension: "mp4"), transport: { try await fixture.read($0) })
    #expect(try await reader.read(offset: 0, end: 4) == Data(repeating: 0x37, count: 4))
    let target = 6 * MediaSegmentReader.block
    #expect(try await reader.read(offset: target + 1, end: target + 5).count == 4)
    #expect(try await reader.read(offset: target + 2, end: target + 8).count == 6)
    #expect(await fixture.ranges == ["bytes=0-262143", "bytes=1572864-1835007"])
    await fixture.mutate()
    await #expect(throws: (any Error).self) { try await reader.read(offset: 5 * MediaSegmentReader.block, end: target) }
}

@Test @MainActor func preloadedPrefixIsReusedWithoutAnotherRangeRequest() async throws {
    let fixture = SegmentFixture()
    let resource = MediaResource(url: URL(string: "https://fixture.test/next.m4a")!, headers: [:], fileExtension: "m4a")
    let reader = MediaSegmentReader(resource: resource, transport: { try await fixture.read($0) })
    #expect(try await reader.read(offset: 0, end: MediaSegmentReader.block).count == 262_144)
    #expect(try await reader.read(offset: 0, end: 2).count == 2)
    #expect(try await reader.read(offset: 90_000, end: 100_000).count == 10_000)
    #expect(await fixture.ranges == ["bytes=0-262143"])
}

private actor StreamGate {
    var count = 0
    var continuation: CheckedContinuation<Void, Never>?
    var released = false
    func begin() { count += 1 }
    func wait() async { if !released { await withCheckedContinuation { continuation = $0 } } }
    func release() { released = true; continuation?.resume(); continuation = nil }
}

@Test @MainActor func mediaDeliversValidatedPrefixBeforeWholeBlockAndSharesInflightRead() async throws {
    let gate = StreamGate()
    let resource = MediaResource(url: URL(string: "https://fixture.test/stream.mp4")!, headers: [:], fileExtension: "mp4")
    let reader = MediaSegmentReader(resource: resource, streaming: { request, response, chunk in
        await gate.begin()
        try await response(HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
            headerFields: ["Content-Range": "bytes 0-262143/524288", "ETag": "\"one\""])!)
        try await chunk(Data(repeating: 7, count: 16 << 10))
        await gate.wait()
        try await chunk(Data(repeating: 8, count: 240 << 10))
    })
    defer { reader.cancel() }
    #expect(try await reader.read(offset: 0, end: MediaSegmentReader.block).count == 16 << 10)
    #expect(try await reader.read(offset: 1, end: 4) == Data(repeating: 7, count: 3))
    #expect(await gate.count == 1)
    let tail = Task { try await reader.read(offset: 16 << 10, end: MediaSegmentReader.block) }
    await gate.release()
    #expect(try await tail.value.count == 240 << 10)
    #expect(await gate.count == 1)
}

@Test @MainActor func mediaRejectsInvalidRangeBeforeDeliveringAndCancelsBlockedRead() async throws {
    let resource = MediaResource(url: URL(string: "https://fixture.test/broken.mp4")!, headers: [:], fileExtension: "mp4")
    let wrong = MediaSegmentReader(resource: resource, streaming: { request, response, chunk in
        try await response(HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
            headerFields: ["Content-Range": "bytes 1-262144/524288"])!)
        try await chunk(Data(repeating: 9, count: 4))
    })
    await #expect(throws: (any Error).self) { try await wrong.read(offset: 0, end: 4) }
    wrong.cancel()
    let waiting = MediaSegmentReader(resource: resource, streaming: { _, _, _ in try await Task.sleep(for: .seconds(10)) })
    let task = Task { try await waiting.read(offset: 0, end: 4) }
    await Task.yield(); waiting.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
}
