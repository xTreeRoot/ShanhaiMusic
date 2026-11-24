import AVFoundation
import CryptoKit
import Foundation
@testable import MBFoundation
import Testing

private func cencFixture() throws -> Data {
    try Data(contentsOf: #require(Bundle.module.url(forResource: "Fixtures/cenc-aac", withExtension: "m4a")))
}

@Test func cencAudioRejectsInvalidKeysAndTruncatedContainers() throws {
    for key in ["", String(repeating: "z", count: 32), "0011"] { #expect(throws: PluginFailure.self) { try CENCAudio.key(key) } }
    let key = try CENCAudio.key("00112233445566778899aabbccddeeff"), input = try cencFixture()
    for size in [0, 4, 16, input.count - 1] { #expect(throws: PluginFailure.self) { try CENCAudio.decode(Data(input.prefix(size)), key: key) } }
}

@Test func cencAudioRestoresNativeAACAndKeepsDuration() async throws {
    let clear = try CENCAudio.decode(cencFixture(), key: CENCAudio.key("00112233445566778899aabbccddeeff"))
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
    defer { try? FileManager.default.removeItem(at: file) }
    try clear.write(to: file)
    let asset = AVURLAsset(url: file)
    #expect(try await asset.load(.isPlayable))
    let original = AVURLAsset(url: try #require(Bundle.module.url(forResource: "Fixtures/cenc-aac", withExtension: "m4a")))
    #expect(abs(try await asset.load(.duration).seconds - original.load(.duration).seconds) < 0.001)
    let track = try #require(await asset.loadTracks(withMediaType: .audio).first)
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
    reader.add(output); #expect(reader.startReading())
    var samples = 0
    while let buffer = output.copyNextSampleBuffer() { samples += CMSampleBufferGetNumSamples(buffer) }
    #expect(reader.status == .completed)
    #expect(samples > 6_000)
}

private func cencWord(_ value: Int, width: Int = 4) -> Data {
    Data((0..<width).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) })
}
private func cencBox(_ type: String, _ body: Data) -> Data { cencWord(body.count + 8) + Data(type.utf8) + body }

@Test func cencAudioSupportsInterleavedTextAndClearSubsamples() throws {
    let key = try CENCAudio.key("2b7e151628aed2a6abf7158809cf4f3c")
    let iv = Data((0xf0...0xff).map(UInt8.init))
    let cipher = try CENCAudio.key("874d6191b620e3261bef6864990db6ce")
    let plain = try CENCAudio.key("6bc1bee22e409f96e93d7e117393172a")
    let text = Data("keep text".utf8), prefix = Data([7, 8, 9])
    let payload = text + cipher + text + prefix + cipher
    let mdat = cencBox("mdat", payload), base = 8
    let stsz = cencBox("stsz", cencWord(0) + cencWord(0) + cencWord(2) + cencWord(16) + cencWord(19))
    let stco = cencBox("stco", cencWord(0) + cencWord(2) + cencWord(base + text.count) + cencWord(base + text.count * 2 + 16))
    let stsc = cencBox("stsc", [0, 1, 1, 1, 1].reduce(Data()) { $0 + cencWord($1) })
    let sub0 = iv + cencWord(1, width: 2) + cencWord(0, width: 2) + cencWord(16)
    let sub1 = iv + cencWord(1, width: 2) + cencWord(3, width: 2) + cencWord(16)
    let senc = cencBox("senc", cencWord(2) + cencWord(2) + sub0 + sub1)
    let tenc = cencBox("tenc", cencWord(0) + Data([0, 0, 1, 16]) + Data(repeating: 0, count: 16))
    let sinf = cencBox("sinf", cencBox("frma", Data("mp4a".utf8)) + cencBox("schm", cencWord(0) + Data("cenc".utf8) + cencWord(0)) + cencBox("schi", tenc))
    let stsd = cencBox("stsd", cencWord(0) + cencWord(1) + cencBox("enca", Data(repeating: 0, count: 28) + sinf))
    let stbl = cencBox("stbl", stsd + stsz + stco + stsc + senc)
    let hdlr = cencBox("hdlr", cencWord(0) + cencWord(0) + Data("soun".utf8))
    let track = cencBox("trak", cencBox("mdia", hdlr + cencBox("minf", stbl)))
    let decoded = try CENCAudio.decode(mdat + cencBox("moov", track), key: key)
    #expect(Data(decoded[base..<mdat.count]) == text + plain + text + prefix + plain)
    var invalid = mdat + cencBox("moov", track)
    // 一个错误的顶层长度不能越过文件边界。
    invalid.replaceSubrange(0..<4, with: cencWord(Int(UInt32.max)))
    #expect(throws: PluginFailure.self) { try CENCAudio.decode(invalid, key: key) }
}

@Test func cencResourceChecksPermissionsAndPreservesNetworkHeaders() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try """
    export function stream({id}) {
      return {url:'https://' + id + '/audio',type:'m4a',headers:{'X-Fixture':'test'},encryption:{scheme:'cenc',key:'00112233445566778899aabbccddeeff'}};
    }
    """.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"cenc-fixture","name":"Fixture","version":"1.0.0","permissions":{"network":["audio.example.test"]}}"#.utf8))
    let fixture = try cencFixture()
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, transport: { request, _ in
        #expect(request.value(forHTTPHeaderField: "X-Fixture") == "test")
        return (fixture, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    })
    let source = PluginSource(manifest: manifest, runtime: runtime)
    await #expect(throws: PluginFailure.self) { try await source.audio(trackID: "outside.example.test") }
    let resource = try await source.audio(trackID: "audio.example.test")
    #expect(resource.url.isFileURL)
    #expect(resource.headers.isEmpty)
    #expect(try Data(contentsOf: resource.url) == CENCAudio.decode(fixture, key: CENCAudio.key("00112233445566778899aabbccddeeff")))
}

@Test @MainActor func cencPreparedLocalAudioCanPreloadAndBeSavedWithoutRemovingPlaybackCache() async throws {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
    defer { try? FileManager.default.removeItem(at: file) }
    try CENCAudio.decode(cencFixture(), key: CENCAudio.key("00112233445566778899aabbccddeeff")).write(to: file)
    let resource = MediaResource(url: file, headers: [:], fileExtension: "m4a"), engine = NativeAudioEngine()
    let prepared = try await engine.prepare(resource: resource)
    try await engine.load(prepared: prepared, at: 0)
    #expect(engine.snapshot().durationMilliseconds > 0)
    engine.stop()
    let downloaded = try await NetworkClient.shared.download(resource)
    #expect(downloaded != file)
    #expect(try Data(contentsOf: downloaded) == Data(contentsOf: file))
    try FileManager.default.removeItem(at: downloaded)
    #expect(FileManager.default.fileExists(atPath: file.path))
}

@Test func cencAudioChecksEveryRedirectAgainstPluginPermissions() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"redirect-fixture","permissions":{"network":["audio.example.test","*.edge.example.test"]}}"#.utf8))
    let fixture = try cencFixture()
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        if url.host == "audio.example.test" {
            let host = url.path == "/denied" ? "outside.example.test" : "one.edge.example.test"
            return (Data(), HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: ["Location": "https://" + host + "/" + UUID().uuidString + "?auth=private-fixture-value"])!)
        }
        #expect(url.host == "one.edge.example.test")
        return (fixture, HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    })
    let encryption = PluginJSON.object(["scheme": .string("cenc"), "key": .string("00112233445566778899aabbccddeeff")])
    do {
        _ = try await runtime.prepareCENCAudio(url: URL(string: "https://audio.example.test/denied")!, headers: [:], encryption: encryption)
        Issue.record("Undeclared redirect host was accepted")
    } catch PluginFailure.invalid(let reason) {
        #expect(reason.contains("outside.example.test"))
        #expect(!reason.contains("private-fixture-value"))
        #expect(!reason.contains("https://"))
    }
    await #expect(throws: PluginFailure.self) {
        try await runtime.prepareCENCAudio(url: URL(string: "https://user:private-fixture-value@audio.example.test/audio")!, headers: [:], encryption: encryption)
    }
    let resource = try await runtime.prepareCENCAudio(url: URL(string: "https://audio.example.test/" + UUID().uuidString)!, headers: [:], encryption: encryption)
    #expect(resource.isFileURL)
    #expect(try Data(contentsOf: resource) == CENCAudio.decode(fixture, key: CENCAudio.key("00112233445566778899aabbccddeeff")))
}

/// 仅显式传入本地授权样本时运行；样本、密钥和解码结果都不进入仓库。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_CENC_INPUT"] != nil))
func cencAuthorizedLocalSampleCanDecodeThroughItsEnd() async throws {
    let env = ProcessInfo.processInfo.environment
    let input = try Data(contentsOf: URL(fileURLWithPath: #require(env["MB_CENC_INPUT"])))
    let key = try String(contentsOfFile: #require(env["MB_CENC_KEY_FILE"]), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    let decoded = try CENCAudio.decode(input, key: CENCAudio.key(key))
    let file = URL(fileURLWithPath: try #require(env["MB_CENC_OUTPUT"]))
    try decoded.write(to: file, options: .atomic)
    let asset = AVURLAsset(url: file), expected = Double(env["MB_CENC_DURATION"] ?? "0") ?? 0
    let duration = try await asset.load(.duration).seconds
    #expect(abs(duration - expected) < 0.1)
    let track = try #require(await asset.loadTracks(withMediaType: .audio).first)
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
    reader.add(output); reader.timeRange = CMTimeRange(start: CMTime(seconds: max(0, duration - 5), preferredTimescale: 44100), duration: CMTime(seconds: 5, preferredTimescale: 44100))
    #expect(reader.startReading())
    var samples = 0
    while let buffer = output.copyNextSampleBuffer() { samples += CMSampleBufferGetNumSamples(buffer) }
    #expect(reader.status == .completed)
    #expect(samples > 100_000)
}

/// 显式提供本地授权资源时，覆盖真实宿主的权限、网络、容器准备与原生解码链路。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_CENC_RESOURCE"] != nil || ProcessInfo.processInfo.environment["MB_CENC_PLUGIN"] != nil))
func cencAuthorizedResourceCanLoadThroughProductionTransport() async throws {
    let env = ProcessInfo.processInfo.environment
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    let directory: URL, trackID: String
    if let plugin = env["MB_CENC_PLUGIN"] {
        directory = URL(fileURLWithPath: plugin)
        let target = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: URL(fileURLWithPath: #require(env["MB_CENC_TRACK"]))))
        trackID = try #require(target["sourceTrackID"].string)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("data"), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: URL(fileURLWithPath: #require(env["MB_CENC_STORAGE"])), to: root.appendingPathComponent("data/storage.json"))
    } else {
        directory = root; trackID = "authorized-fixture"
        let bytes = try Data(contentsOf: URL(fileURLWithPath: #require(env["MB_CENC_RESOURCE"])))
        let value = try JSONDecoder().decode(PluginJSON.self, from: bytes)
        try ("export function stream() { return " + value.encoded() + "; }").write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    }
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: URL(fileURLWithPath: #require(env["MB_CENC_MANIFEST"]))))
    let runtime = try PluginRuntime(directory: directory, dataDirectory: root.appendingPathComponent("data"), manifest: manifest)
    let resource = try await PluginSource(manifest: manifest, runtime: runtime).audio(trackID: trackID)
    #expect(resource.url.isFileURL)
    let asset = AVURLAsset(url: resource.url)
    #expect(try await asset.load(.isPlayable))
    #expect(try await asset.load(.duration).seconds > 0)
}
