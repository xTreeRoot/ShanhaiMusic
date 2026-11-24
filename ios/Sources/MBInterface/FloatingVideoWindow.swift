import MBFoundation
import SwiftUI

/// 应用内浏览时沿用同一个视频会话，位置受屏幕安全区约束。
struct FloatingVideoWindow: View {
    let video: VideoModel
    let playback: PlaybackModel
    let expand: () -> Void
    @State private var offset = CGSize.zero
    @State private var origin = CGSize.zero
    var body: some View {
        GeometryReader { area in
            let width = min(224, area.size.width * 0.60)
            let height = min(width / video.aspectRatio, area.size.height * 0.6)
            VideoScreen(video: video, pictureVisible: video.isFloating)
                .frame(width: width, height: height)
                .overlay(alignment: .top) {
                    HStack {
                        Button { video.browse() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("关闭视频小窗")
                        Spacer(minLength: 0)
                        Button(action: expand) { Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 44, height: 44) }.accessibilityLabel("展开视频")
                    }.background(.black.opacity(0.4))
                }
                .overlay(alignment: .bottomLeading) {
                    Button { playback.toggle() } label: {
                        Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
                    }.accessibilityLabel("播放或暂停")
                }
                .buttonStyle(.plain).foregroundStyle(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12)).shadow(radius: 8)
                .offset(offset)
                .gesture(DragGesture().onChanged { value in
                    offset = CGSize(width: min(0, max(-(area.size.width - width - 24), origin.width + value.translation.width)),
                                    height: min(0, max(-(area.size.height - height - 112), origin.height + value.translation.height)))
                }.onEnded { _ in origin = offset })
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(.trailing, 12).padding(.bottom, 100)
        }
    }
}
