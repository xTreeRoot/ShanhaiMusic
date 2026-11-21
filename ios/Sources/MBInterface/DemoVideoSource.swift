#if DEBUG
import Foundation
import MBFoundation

/// 显式 UI 回归才启用的本地媒体源，用真实 AVPlayerLayer 验证返回、小窗交接和安全区。
struct DemoVideoSource: VideoSource, PlaybackSource {
    let file: URL
    let displayName = "本地视频回归"
    static func fixture() -> Self? {
        guard let path = ProcessInfo.processInfo.environment["MB_VIDEO_UI_FIXTURE"],
              FileManager.default.fileExists(atPath: path) else { return nil }
        return Self(file: URL(fileURLWithPath: path))
    }
    func search(query: String, page: Int) async throws -> TrackPage { try await DemoSource().search(query: query, page: page) }
    func video(trackID: String) async throws -> MediaResource { MediaResource(url: file, headers: [:], fileExtension: "mp4") }
    func captions(trackID: String) async throws -> CaptionTimeline { DemoCatalog.timeline }
    func audio(trackID: String) async throws -> MediaResource {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("video-ui-\(UUID()).wav")
        var bytes = Data()
        func append<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) } }
        let count: UInt32 = 88_200 * 30
        bytes.append(Data("RIFF".utf8)); append(count + 36); bytes.append(Data("WAVEfmt ".utf8))
        append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(44_100)); append(UInt32(88_200)); append(UInt16(2)); append(UInt16(16))
        bytes.append(Data("data".utf8)); append(count); bytes.append(Data(repeating: 0, count: Int(count)))
        try bytes.write(to: file, options: .atomic)
        return MediaResource(url: file, headers: [:], fileExtension: "wav")
    }
}
#endif
