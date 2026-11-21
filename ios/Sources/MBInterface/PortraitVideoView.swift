import MBFoundation
import SwiftUI

/// 根据实际视频尺寸适配所有插件的竖屏资源，复用队列与互动能力。
struct PortraitVideoView: View {
    let track: Track
    let video: VideoModel
    let playback: PlaybackModel
    let interactionSource: (any InteractionSource)?
    let openMusic: () -> Void
    var browse: (() -> Void)? = nil
    let saveResource: (Track) -> Void
    let menu: (Track) -> AnyView
    @State private var comments = false
    @State private var captions = false
    @State private var saved = false
    @State private var interaction: InteractionModel?
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { if let browse { browse() } else { video.browse() } } label: { Label("返回浏览", systemImage: "chevron.left").frame(minHeight: 44) }
                Spacer()
                VideoQualityMenu(video: video)
                Button(action: openMusic) { Label("听歌模式", systemImage: "headphones").frame(minHeight: 44) }
            }.padding(.horizontal, 16)
            GeometryReader { geometry in
                VideoScreen(video: video, pictureVisible: video.isVisible)
                    .aspectRatio(video.aspectRatio, contentMode: .fit)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .contentShape(Rectangle())
                    .onTapGesture { playback.toggle() }
                    .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                        guard abs(value.translation.height) > abs(value.translation.width) else { return }
                        if value.translation.height < -72 { playback.next() }
                        else if value.translation.height > 72 { playback.previous() }
                    })
                    .overlay(alignment: .trailing) {
                        ScrollView(showsIndicators: false) {
                            VStack(spacing: 10) {
                                action("上一个", symbol: "chevron.up", playback.previous)
                                action((playback.state == .playing) ? "暂停" : "播放", symbol: (playback.state == .playing) ? "pause.fill" : "play.fill", playback.toggle)
                                if interactionSource != nil { action("评论", symbol: "bubble.right") { comments = true } }
                                action(saved ? "已收藏" : "收藏", symbol: saved ? "star.fill" : "star") { saveResource(track); saved = true }
                                if let url = track.pageURL { ShareLink(item: url) { control("分享链接", symbol: "square.and.arrow.up") }.accessibilityLabel("分享链接") }
                                action("字幕", symbol: "captions.bubble") { captions = true }
                                action("下一个", symbol: "chevron.down", playback.next)
                            }.padding(6)
                        }.frame(width: 74).fixedSize(horizontal: false, vertical: true)
                    }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(track.artist).font(.subheadline).foregroundStyle(.white.opacity(0.8))
                Text(track.title).font(.headline).lineLimit(2)
                if let interaction { TrackLikeButton(model: interaction) }
                Menu { menu(track) } label: { Label("加入歌单／稍后观看", systemImage: "text.badge.plus").frame(minHeight: 44) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 8)
        }.foregroundStyle(.white).background(.black)
        .task(id: track.id) {
            saved = false
            guard let source = interactionSource else { interaction = nil; return }
            let model = InteractionModel(source: source, track: track); interaction = model
            await model.loadLike()
        }
        .sheet(isPresented: $comments) { if let source = interactionSource { CommentsView(source: source, track: track) } }
        .sheet(isPresented: $captions) {
            NavigationStack {
                CaptionView(timeline: playback.timeline, positionMilliseconds: playback.positionMilliseconds, message: playback.captionMessage, seek: { playback.seek(to: $0) }).padding()
                    .navigationTitle("字幕").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { captions = false } } }
            }
        }
    }
    private func control(_ title: String, symbol: String) -> some View {
        VStack(spacing: 3) {
            Image(systemName: symbol).font(.title3).frame(width: 44, height: 44).background(.black.opacity(0.45), in: Circle())
            Text(LocalizedStringKey(title)).font(.caption2).lineLimit(1)
        }.foregroundStyle(.white)
    }
    private func action(_ title: String, symbol: String, _ perform: @escaping () -> Void) -> some View {
        Button(action: perform) { control(title, symbol: symbol) }.buttonStyle(.plain).accessibilityLabel(LocalizedStringKey(title))
    }
}
