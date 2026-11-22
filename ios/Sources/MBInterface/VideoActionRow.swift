import MBFoundation
import SwiftUI

/// 普通视频操作沿同一行排列，大字体时允许横向滚动。
struct VideoActionRow: View {
    @Environment(\.skinAccent) private var skinAccent
    let playback: PlaybackModel
    let interaction: InteractionModel?
    var body: some View {
        GeometryReader { geometry in
            let width = max(72, geometry.size.width / (interaction?.capabilities.likes == true ? 5 : 4))
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    action("上一首", width: width) { playback.previous() }
                    action("下一首", width: width) { playback.next() }
                    if let interaction, interaction.capabilities.likes {
                        Button { Task { await interaction.toggleLike() } } label: {
                            Image(systemName: interaction.liked ? "hand.thumbsup.fill" : "hand.thumbsup")
                                .frame(width: width, height: 44).contentShape(Rectangle())
                        }.disabled(interaction.isChangingLike)
                            .accessibilityLabel(interaction.liked ? "取消点赞" : "点赞")
                            .accessibilityIdentifier("video-action-like")
                    }
                    action("随机播放", width: width) { playback.toggleShuffle() }
                        .foregroundStyle(playback.queue.shuffled ? skinAccent : .secondary)
                        .accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
                    action(playback.queue.repeatMode.title, width: width) { playback.cycleRepeat() }
                        .foregroundStyle(playback.queue.repeatMode == .off ? .secondary : skinAccent)
                }
            }.scrollIndicators(.hidden)
        }.frame(height: 48).font(.subheadline).buttonStyle(.plain)
            .accessibilityElement(children: .contain).accessibilityIdentifier("video-action-row")
    }
    private func action(_ title: String, width: CGFloat, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Text(LocalizedStringKey(title)).lineLimit(1).minimumScaleFactor(0.8)
                .frame(width: width, height: 44).contentShape(Rectangle())
        }.accessibilityLabel(LocalizedStringKey(title))
    }
}
