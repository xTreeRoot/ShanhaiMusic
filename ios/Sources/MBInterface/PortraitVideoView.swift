import MBFoundation
import SwiftUI

/// 视频铺满舞台；互动沿右侧排列，文字操作统一无填充，面板复用真实插件能力。
struct PortraitVideoView: View {
    let track: Track
    let video: VideoModel
    let playback: PlaybackModel
    let interactionSource: (any InteractionSource)?
    var librarySource: (any LibrarySource)? = nil
    let openMusic: () -> Void
    var browse: (() -> Void)? = nil
    let saveResource: (Track) -> Void
    let menu: (Track) -> AnyView
    @State private var panel: Panel?
    @State private var saved = false
    @State private var interaction: InteractionModel?
    @State private var preview = 0.0
    @State private var scrubbing = false
    @State private var selection: Int?
    private enum Panel: String, Identifiable {
        case more, comments, captions, quality, information, creator
        var id: String { rawValue }
    }

    var body: some View {
        GeometryReader { geometry in
            let inset = geometry.safeAreaInsets
            let readingComments = panel == .comments
            let pictureHeight = readingComments ? geometry.size.height * 0.35 : geometry.size.height
            ZStack {
                VideoScreen(video: video, pictureVisible: video.isVisible)
                    .aspectRatio(video.aspectRatio, contentMode: .fit)
                    .frame(width: geometry.size.width, height: pictureHeight)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .contentShape(Rectangle())
                    .onTapGesture { playback.toggle() }
                    .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                        guard abs(value.translation.height) > abs(value.translation.width) else { return }
                        if value.translation.height < -72 { playback.next() }
                        else if value.translation.height > 72 { playback.previous() }
                    })
                    .accessibilityLabel("竖屏视频画面")
                    .accessibilityAction(named: "下一个") { playback.next() }
                    .accessibilityAction(named: "上一个") { playback.previous() }
                LinearGradient(stops: [.init(color: .black.opacity(0.65), location: 0), .init(color: .clear, location: 0.25),
                                       .init(color: .clear, location: 0.55), .init(color: .black.opacity(0.8), location: 1)],
                               startPoint: .top, endPoint: .bottom).allowsHitTesting(false)
                if playback.state != .playing && !video.isLoading {
                    Image(systemName: "play.fill").font(.system(size: 48)).shadow(radius: 12).allowsHitTesting(false)
                }
                VStack(spacing: 0) {
                    HStack {
                        Button { if let browse { browse() } else { video.browse() } } label: {
                            Image(systemName: "chevron.left").font(.title2).frame(width: 44, height: 44).contentShape(Rectangle())
                        }.accessibilityLabel("返回浏览")
                        Spacer()
                        Button(action: openMusic) { Image(systemName: "headphones").font(.title2).frame(width: 44, height: 44).contentShape(Rectangle()) }
                            .accessibilityLabel("听歌模式")
                        Button { panel = .more } label: { Image(systemName: "ellipsis").font(.title2).frame(width: 44, height: 44).contentShape(Rectangle()) }
                            .accessibilityLabel("视频更多操作").accessibilityIdentifier("portrait-more")
                    }.padding(.horizontal, 8).padding(.top, max(inset.top, 8))
                    Spacer(minLength: 16)
                    HStack(alignment: .bottom, spacing: 8) {
                        metadata.frame(maxWidth: .infinity, alignment: .leading)
                        VStack(spacing: 20) {
                            if let interaction, interaction.capabilities.likes {
                                action(interaction.liked ? "取消点赞" : "点赞", symbol: interaction.liked ? "hand.thumbsup.fill" : "hand.thumbsup",
                                       count: interaction.summary.likes.map(compact)) { Task { await interaction.toggleLike() } }
                                    .disabled(interaction.isChangingLike)
                            }
                            if interaction?.capabilities.comments == true {
                                action("评论", symbol: "bubble.right", count: interaction?.summary.comments.map(compact)) { panel = .comments }
                            }
                            if let url = track.pageURL {
                                ShareLink(item: url) { control("分享", symbol: "arrowshape.turn.up.right", count: nil) }.accessibilityLabel("分享链接")
                            }
                        }.frame(width: 60).padding(.bottom, 6)
                    }.padding(.leading, 18).padding(.trailing, 8)
                    progress.padding(.horizontal, 14).padding(.top, 8).padding(.bottom, max(inset.bottom, 8))
                }
            }.foregroundStyle(.white).background(.black)
        }.ignoresSafeArea(.container, edges: [.top, .bottom])
        .buttonStyle(.plain).tint(.white)
        .task(id: track.id) {
            saved = false; panel = nil
            guard let source = interactionSource else { interaction = nil; return }
            let model = InteractionModel(source: source, track: track); interaction = model
            await model.loadSummary()
        }
        .sheet(item: $panel) { selected in
            panelContent(selected).presentationDetents([.fraction(0.65), .large]).presentationDragIndicator(.visible)
                .tint(.primary).buttonStyle(.plain)
        }
    }
    private var metadata: some View {
        VStack(alignment: .leading, spacing: 10) {
            if librarySource != nil, track.artistID != nil {
                Button { panel = .creator } label: {
                    Label(track.artist, systemImage: "person.crop.circle").font(.subheadline.weight(.semibold))
                        .lineLimit(1).frame(minHeight: 40).contentShape(Rectangle())
                }.accessibilityLabel("查看作者 \(track.artist)")
            } else { Text(track.artist).font(.subheadline.weight(.semibold)).lineLimit(1) }
            Button { panel = .information } label: {
                Text(track.title).font(.subheadline).lineLimit(3).multilineTextAlignment(.leading).contentShape(Rectangle())
            }.accessibilityLabel(track.title)
            if let error = interaction?.likeError { Text(error).font(.caption).lineLimit(2) }
        }.shadow(color: .black.opacity(0.5), radius: 3, y: 1)
    }
    private var progress: some View {
        HStack(spacing: 8) {
            Button { playback.toggle() } label: {
                Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").frame(width: 36, height: 44).contentShape(Rectangle())
            }.accessibilityLabel(playback.state == .playing ? "暂停" : "播放")
            PlaybackSeekBar(value: Binding(get: { min(max(0, scrubbing ? preview : Double(playback.positionMilliseconds)), Double(max(1, playback.durationMilliseconds))) }, set: { preview = $0 }),
                            in: 0...Double(max(1, playback.durationMilliseconds))) { editing in
                scrubbing = editing
                if editing { selection = playback.selectionRevision; preview = Double(playback.positionMilliseconds) }
                else { if selection == playback.selectionRevision { playback.seek(to: Int64(preview)) }; selection = nil }
            }.accessibilityLabel("视频播放进度")
            Text(TimeLabel.string(milliseconds: playback.positionMilliseconds)).font(.caption2.monospacedDigit())
        }.onChange(of: playback.selectionRevision) { _, _ in scrubbing = false; selection = nil }
    }
    @ViewBuilder private func panelContent(_ selected: Panel) -> some View {
        switch selected {
        case .comments:
            if let interaction {
                VStack(spacing: 0) {
                    header("评论", value: interaction.summary.comments.map(compact))
                    TrackCommentsList(model: interaction).task { await interaction.refresh() }
                }
            }
        case .more:
            ScrollView {
                VStack(spacing: 0) {
                    header("更多操作")
                    row("说明", symbol: "text.alignleft") { panel = .information }
                    row(saved ? "已收藏" : "收藏到资料库", symbol: saved ? "bookmark.fill" : "bookmark") { saveResource(track); saved = true }
                    Menu { menu(track) } label: { rowLabel("歌单与下载", symbol: "text.badge.plus") }
                    row("字幕", symbol: "captions.bubble") { panel = .captions }
                    if !video.qualities.isEmpty { row("画质", symbol: "gearshape", value: video.actualQualityID) { panel = .quality } }
                    row("听歌模式", symbol: "headphones") { panel = nil; openMusic() }
                    Divider().padding(.vertical, 6)
                    HStack {
                        Button("上一个", systemImage: "backward.end") { panel = nil; playback.previous() }.frame(maxWidth: .infinity, minHeight: 48)
                        Button("下一个", systemImage: "forward.end") { panel = nil; playback.next() }.frame(maxWidth: .infinity, minHeight: 48)
                    }.padding(.bottom, 16)
                }.padding(.horizontal, 20)
            }.accessibilityIdentifier("portrait-actions")
        case .quality:
            VStack(spacing: 0) { header("画质"); ScrollView { VStack(spacing: 0) { VideoQualityOptions(video: video) }.padding(.horizontal, 20) } }
        case .captions:
            VStack(spacing: 0) {
                header("字幕")
                CaptionView(timeline: playback.timeline, positionMilliseconds: playback.positionMilliseconds, message: playback.captionMessage, seek: { playback.seek(to: $0) }).padding()
            }
        case .information:
            ScrollView { VStack(alignment: .leading, spacing: 16) { header("说明"); Text(track.title).font(.title3.bold()); Text(track.artist).foregroundStyle(.secondary) }.padding(20) }
        case .creator:
            if let librarySource, let id = track.artistID {
                NavigationStack {
                    LibraryPageView(source: librarySource, route: .creator(id), select: { tracks, index in
                        panel = nil; playback.select(tracks, index: index, autoplay: true)
                    }, menu: menu).toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { panel = nil } } }
                }
            }
        }
    }
    private func header(_ title: String, value: String? = nil) -> some View {
        HStack {
            Text(title).font(.title3.bold()); if let value { Text(value).foregroundStyle(.secondary) }
            Spacer(); Button { panel = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("关闭")
        }.padding(.horizontal, 16).padding(.vertical, 8)
    }
    private func row(_ title: String, symbol: String, value: String? = nil, perform: @escaping () -> Void) -> some View {
        Button(action: perform) { rowLabel(title, symbol: symbol, value: value) }.accessibilityLabel(title)
    }
    private func rowLabel(_ title: String, symbol: String, value: String? = nil) -> some View {
        HStack(spacing: 18) {
            Image(systemName: symbol).font(.title3).frame(width: 28)
            Text(title); Spacer(); if let value { Text(value).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, minHeight: 58, alignment: .leading).contentShape(Rectangle())
    }
    private func control(_ title: String, symbol: String, count: String?) -> some View {
        VStack(spacing: 5) {
            Image(systemName: symbol).font(.system(size: 27, weight: .medium)).frame(width: 48, height: 42)
            Text(count ?? AppLocalization.text(title)).font(.caption.weight(.medium)).lineLimit(1)
        }.frame(minHeight: 56).contentShape(Rectangle()).shadow(color: .black.opacity(0.5), radius: 3, y: 1)
    }
    private func action(_ title: String, symbol: String, count: String? = nil, _ perform: @escaping () -> Void) -> some View {
        Button(action: perform) { control(title, symbol: symbol, count: count) }.accessibilityLabel(title).accessibilityValue(count ?? "")
    }
    private func compact(_ value: Int) -> String { value.formatted(.number.notation(.compactName)) }
}
