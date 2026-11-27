#if DEBUG
import Foundation
import MBFoundation

/// 显式设备回归：前两条无音源，第三条用本地静音 WAV 验证真实引擎自动续播。
struct DemoPlaybackFailures: PlaybackSource {
    let displayName = "播放失败回归"
    func search(query: String, page: Int) async throws -> TrackPage { try await DemoSource().search(query: query, page: page) }
    func captions(trackID: String) async throws -> CaptionTimeline { CaptionTimeline(lines: []) }
    func audio(trackID: String) async throws -> MediaResource {
        if trackID != "rain" { throw PluginFailure.playback(.noSource, "Fixture unavailable") }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("failure-ui-\(UUID()).wav")
        let sampleBytes: UInt32 = 44_100 * 2 * 30
        var bytes = Data()
        func append<T: FixedWidthInteger>(_ value: T) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) }
        }
        bytes.append(Data("RIFF".utf8)); append(sampleBytes + 36); bytes.append(Data("WAVEfmt ".utf8))
        append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(44_100)); append(UInt32(88_200))
        append(UInt16(2)); append(UInt16(16)); bytes.append(Data("data".utf8)); append(sampleBytes)
        bytes.append(Data(repeating: 0, count: Int(sampleBytes)))
        try bytes.write(to: file, options: .atomic)
        return MediaResource(url: file, headers: [:], fileExtension: "wav")
    }
}
#endif
