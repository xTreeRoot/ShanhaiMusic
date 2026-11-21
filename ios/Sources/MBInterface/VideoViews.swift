import AVFoundation
import MBFoundation
import SwiftUI

struct VideoPageView: View {
    let video: VideoModel
    let playback: PlaybackModel
    let interactionSource: (any InteractionSource)?
    let accountRevision: Int
    let openMusic: () -> Void
    var browse: (() -> Void)? = nil
    var saveResource: (Track) -> Void = { _ in }
    var menu: (Track) -> AnyView = { _ in AnyView(EmptyView()) }
    @State private var fullScreen = false
    @State private var chrome = VideoChromeState()

    var body: some View {
        Group {
        if video.aspectRatio < 1, let track = playback.queue.current {
            PortraitVideoView(track: track, video: video, playback: playback, interactionSource: interactionSource, openMusic: openMusic, browse: browse, saveResource: saveResource, menu: menu)
        } else {
        GeometryReader { geometry in
            let sideBySide = geometry.size.width >= 800 && geometry.size.width > geometry.size.height
            let layout = sideBySide ? AnyLayout(HStackLayout(alignment: .top, spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                picture.frame(width: sideBySide ? geometry.size.width * 0.57 : geometry.size.width)
                if let track = playback.queue.current {
                    VideoDetailsView(track: track, video: video, playback: playback, interactionSource: interactionSource)
                        .id("\(track.id):\(accountRevision)")
                }
            }.accessibilityHidden(fullScreen)
        }
        }
        }.background(MBTheme.background, ignoresSafeAreaEdges: .bottom)
        .background(Color.black.ignoresSafeArea(edges: .top))
        #if os(iOS)
        .ignoresSafeArea(.container, edges: .top)
        .statusBarHidden(video.isVisible)
        .fullScreenCover(isPresented: $fullScreen) { VideoFullscreenView(video: video, playback: playback) }
        #else
        .sheet(isPresented: $fullScreen) { VideoFullscreenView(video: video, playback: playback) }
        #endif
    }

    private var picture: some View {
        VideoScreen(video: video, pictureVisible: video.isVisible && !fullScreen)
            .modifier(VideoTapControls(chrome: chrome, playback: playback))
            .aspectRatio(video.aspectRatio, contentMode: .fit)
            .overlay(alignment: .top) {
                if chrome.visible { HStack {
                    Button { if let browse { browse() } else { video.browse() } } label: {
                        Image(systemName: "chevron.left").frame(width: 44, height: 44).contentShape(Rectangle())
                    }.accessibilityLabel("返回浏览")
                    Spacer()
                    Button(action: openMusic) {
                        Label("听歌模式", systemImage: "headphones").padding(.horizontal, 8).frame(minHeight: 44).contentShape(Rectangle())
                    }.accessibilityIdentifier("video-open-music")
                }
                .buttonStyle(.plain).font(.subheadline.weight(.semibold)).imageScale(.large)
                .foregroundStyle(.white).padding(.horizontal, 4)
                .background(alignment: .top) { LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).allowsHitTesting(false) } }
            }
            .overlay(alignment: .bottom) {
                if chrome.visible { VideoTransport(playback: playback, fullscreen: { fullScreen = true }, editing: chrome.editing)
                    .accessibilityIdentifier("video-transport")
                    .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)) }
            }
    }
}

private struct VideoDetailsView: View {
    @Environment(\.skinAccent) private var skinAccent
    let track: Track
    let video: VideoModel
    let playback: PlaybackModel
    let interactionSource: (any InteractionSource)?
    @State private var selectedTab = VideoDetailTab.info
    @State private var interaction: InteractionModel?
    @State private var loadedInteractions = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                ForEach(VideoDetailTab.allCases, id: \.self) { tab in
                    Button { selectedTab = tab } label: {
                        HStack(spacing: 4) {
                            Text(tab.title)
                            if tab == .comments, let total = interaction?.total, total > 0 {
                                Text(total.formatted()).font(.caption)
                            }
                        }.font(.subheadline.weight(selectedTab == tab ? .semibold : .regular))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                            .overlay(alignment: .bottom) {
                                if selectedTab == tab { Capsule().frame(width: 25, height: 3) }
                            }
                            .foregroundStyle(selectedTab == tab ? skinAccent : .secondary)
                    }.buttonStyle(.plain).accessibilityLabel(tab.title)
                        .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
                }
            }.padding(.horizontal, 8)
            Divider()
            detailPages
        }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .task(id: interactionSource != nil) {
                loadedInteractions = false
                guard let interactionSource else { interaction = nil; return }
                let interaction = InteractionModel(source: interactionSource, track: track)
                self.interaction = interaction
                await interaction.refresh()
                guard !Task.isCancelled else { return }
                loadedInteractions = true
                await interaction.loadLike()
            }
    }

    @ViewBuilder private var detailPages: some View {
        #if os(iOS)
        TabView(selection: $selectedTab) {
            ForEach(VideoDetailTab.allCases, id: \.self) { tab in detail(tab).tag(tab) }
        }.tabViewStyle(.page(indexDisplayMode: .never))
        #else
        detail(selectedTab)
        #endif
    }
    @ViewBuilder private func detail(_ tab: VideoDetailTab) -> some View {
        switch tab {
        case .info: ScrollView { information.padding(16) }
        case .comments:
            if let interaction, interaction.capabilities.comments { TrackCommentsList(model: interaction) }
            else if interaction != nil && !loadedInteractions { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
            else { ContentUnavailableView("此音源不支持评论", systemImage: "bubble.left.and.bubble.right") }
        case .captions:
            CaptionView(timeline: playback.timeline, positionMilliseconds: playback.positionMilliseconds, message: playback.captionMessage, seek: { playback.seek(to: $0) }).padding(16)
        case .queue:
            ScrollView {
                VideoQueueView(playback: playback)
                #if os(iOS)
                SystemVolumeView().frame(maxWidth: 240).frame(height: 28).accessibilityLabel("系统音量").padding(.top, 12)
                #endif
            }.contentMargins(16)
        }
    }

    private var information: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(track.artist, systemImage: "person.crop.circle").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            Text(track.title).font(.title3.bold())
            if let interaction { TrackLikeButton(model: interaction) }
            if let message = playback.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red) }
            if let message = video.persistenceMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
            Divider()
            HStack {
                Button("上一首", systemImage: "backward.end.fill") { playback.previous() }
                Spacer()
                Button("下一首", systemImage: "forward.end.fill") { playback.next() }
            }
            .font(.subheadline)
            PlaybackOrderControls(playback: playback)
            VideoQueueView(playback: playback)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private enum VideoDetailTab: CaseIterable {
    case info, comments, captions, queue

    var title: LocalizedStringKey {
        switch self {
        case .info: "简介"
        case .comments: "评论"
        case .captions: "字幕"
        case .queue: "队列"
        }
    }
}

private struct VideoQueueView: View {
    let playback: PlaybackModel
    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            Text("播放队列 · \(playback.queue.tracks.count)").font(.headline)
            ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                Button { playback.jump(to: index, intent: .transport) } label: {
                    VideoTrackRow(track: track, selected: playback.queue.index == index)
                }.buttonStyle(.plain).accessibilityLabel("队列选择 \(track.title)")
            }
        }
    }
}

private struct VideoFullscreenView: View {
    @Environment(\.skinAccent) private var skinAccent
    let video: VideoModel
    let playback: PlaybackModel
    @Environment(\.dismiss) private var dismiss
    @State private var chrome = VideoChromeState()
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoScreen(video: video).ignoresSafeArea().modifier(VideoTapControls(chrome: chrome, playback: playback))
            if chrome.visible {
                VStack {
                    HStack {
                        Button("退出全屏", systemImage: "chevron.left") { dismiss() }
                        Text(playback.queue.current?.title ?? "视频播放").font(.headline).lineLimit(1)
                        Spacer()
                    }
                    .padding().background(.black.opacity(0.6))
                    Spacer()
                    VideoTransport(playback: playback, editing: chrome.editing).accessibilityIdentifier("video-transport").background(.black.opacity(0.6))
                }.foregroundStyle(.white)
            }
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("fullscreen-video")
        .tint(skinAccent)
        #if os(iOS)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear { MobileOrientation.fullscreen(true) }
        .onDisappear { MobileOrientation.fullscreen(false) }
        #endif
    }
}

private struct VideoTransport: View {
    @Environment(\.skinAccent) private var skinAccent
    let playback: PlaybackModel
    var fullscreen: (() -> Void)? = nil
    var editing: (Bool) -> Void = { _ in }
    @State private var preview = 0.0
    @State private var scrubbing = false
    @State private var selection: Int?
    var body: some View {
        HStack(spacing: 8) {
            Button { playback.toggle() } label: {
                Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill")
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }.accessibilityLabel(LocalizedStringKey(playback.state == .playing ? "暂停" : "播放"))
            PlaybackSeekBar(value: Binding(get: { min(max(0, scrubbing ? preview : Double(playback.positionMilliseconds)), Double(max(1, playback.durationMilliseconds))) }, set: { preview = $0 }),
                   in: 0...Double(max(1, playback.durationMilliseconds))) { editing in
                scrubbing = editing
                self.editing(editing)
                if editing { selection = playback.selectionRevision; preview = Double(playback.positionMilliseconds) }
                else { if selection == playback.selectionRevision { playback.seek(to: Int64(preview)) }; selection = nil }
            }
                .accessibilityLabel("视频播放进度")
            HStack(spacing: 2) {
                Text(TimeLabel.string(milliseconds: playback.positionMilliseconds))
                Text("/\(TimeLabel.string(milliseconds: playback.durationMilliseconds))")
            }.font(.caption.monospacedDigit()).fixedSize()
            if let fullscreen {
                Button(action: fullscreen) {
                    Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 44, height: 44).contentShape(Rectangle())
                }.accessibilityLabel("全屏播放")
            }
        }.labelStyle(.iconOnly).buttonStyle(.plain).foregroundStyle(.white).tint(skinAccent)
            .accessibilityElement(children: .contain)
            .padding(.horizontal, 8)
            .onChange(of: playback.selectionRevision) { _, _ in scrubbing = false; selection = nil }
    }
}

struct VideoScreen: View {
    let video: VideoModel
    var pictureVisible = true
    var body: some View {
        Color.black.overlay {
            if video.track == nil { message("先从首页或搜索选择视频") }
            else if !video.isSupported { message("此音源不支持视频播放") }
            else if let error = video.errorMessage {
                VStack(spacing: 12) { message(error); Button("重试视频") { video.retry() } }
            } else {
                ZStack {
                    if video.isReady, let player = video.player, pictureVisible {
                        NativeVideoSurface(player: player).accessibilityLabel("视频画面")
                    }
                    if video.isLoading || video.isBuffering {
                        ProgressView(LocalizedStringKey(video.isLoading ? "正在加载视频" : "正在缓冲"))
                            .tint(.white).foregroundStyle(.white)
                    }
                }
            }
        }
    }
    private func message(_ text: String) -> some View {
        Text(LocalizedStringKey(text)).font(.callout).foregroundStyle(.white.opacity(0.85)).multilineTextAlignment(.center).padding(20)
    }
}

#if os(iOS)
import MediaPlayer

private struct SystemVolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { MPVolumeView() }
    func updateUIView(_ view: MPVolumeView, context: Context) {}
}

private final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var displayObservation: NSKeyValueObservation?
    weak var pictureInPicture: VideoPictureInPicture?
}
private struct NativeVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    @Environment(\.videoPictureInPicture) private var pictureInPicture
    func makeUIView(context: Context) -> PlayerSurface {
        let view = PlayerSurface()
        view.playerLayer.videoGravity = .resizeAspect
        view.isAccessibilityElement = true
        view.accessibilityIdentifier = "native-video-surface"
        view.displayObservation = view.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { @Sendable [weak view] layer, _ in
            let ready = layer.isReadyForDisplay
            Task { @MainActor in view?.accessibilityValue = AppLocalization.text(ready ? "视频画面已显示" : "等待视频画面") }
        }
        return view
    }
    func updateUIView(_ view: PlayerSurface, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
        if view.pictureInPicture !== pictureInPicture {
            view.pictureInPicture?.detach(view.playerLayer)
            view.pictureInPicture = pictureInPicture
        }
        pictureInPicture?.attach(view.playerLayer)
    }
    static func dismantleUIView(_ view: PlayerSurface, coordinator: ()) {
        view.displayObservation = nil
        view.pictureInPicture?.detach(view.playerLayer)
        if view.pictureInPicture?.running != true { view.playerLayer.player = nil }
    }
}
#else
private struct NativeVideoSurface: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        let layer = AVPlayerLayer()
        layer.videoGravity = .resizeAspect
        view.layer = layer
        return view
    }
    func updateNSView(_ view: NSView, context: Context) { (view.layer as? AVPlayerLayer)?.player = player }
    static func dismantleNSView(_ view: NSView, coordinator: ()) { (view.layer as? AVPlayerLayer)?.player = nil }
}
#endif
