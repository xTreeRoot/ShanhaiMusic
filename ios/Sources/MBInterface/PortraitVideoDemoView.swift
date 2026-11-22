#if DEBUG
import MBFoundation
import SwiftUI

/// 显式验收入口，只使用本地示例，发布构建不包含此页面。
public struct PortraitVideoDemoView: View {
    @State private var playback: PlaybackModel
    @State private var video: VideoModel
    @State private var quality = "auto"
    @State private var interactions: DemoSource?
    @State private var library = LocalLibraryModel(store: LocalLibraryStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
    public init() {
        let fixture = DemoVideoSource.fixture()
        let playback = PlaybackModel(source: fixture, engine: NativeAudioEngine())
        _video = State(initialValue: VideoModel(source: fixture))
        let tracks = ["竖屏示例一", "竖屏示例二"].enumerated().map { index, title in
            var track = Track(sourceID: "preview", sourceTrackID: String(index), title: title, artist: "本地界面验收")
            track.videoWidth = 1080; track.videoHeight = 1920; track.shortVideo = true
            return track
        }
        playback.select(tracks, index: 0, autoplay: false)
        _playback = State(initialValue: playback)
    }
    public var body: some View {
        VideoPageView(video: video, playback: playback, interactionSource: interactions, accountRevision: 0, openMusic: {}, saveResource: { _ in }, menu: { _ in AnyView(Text("示例歌单")) })
            .environment(\.videoQualityPreference, $quality)
            .task {
                video.updateTrack(playback.queue.current); video.watchCurrent()
                // 模拟播放页先出现，路由随后才返回当前资源的互动数据源。
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                interactions = DemoSource()
            }
            .onChange(of: playback.positionMilliseconds) { _, value in video.sync(positionMilliseconds: value, playing: playback.state == .playing) }
            .onChange(of: playback.state) { _, value in video.sync(positionMilliseconds: playback.positionMilliseconds, playing: value == .playing) }
            .onChange(of: playback.selectionRevision) { _, _ in video.updateTrack(playback.queue.current); video.watchCurrent() }
    }
}
#endif
