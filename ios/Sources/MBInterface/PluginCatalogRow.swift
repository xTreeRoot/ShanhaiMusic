import MBFoundation
import SwiftUI

/// 目录和作者列表使用同一行布局，保留图片请求头，介绍按剩余宽度换行。
struct PluginCatalogRow: View {
    let track: Track
    let subtitle: String
    var avatar = false
    var symbol = "square.stack.fill"
    @Environment(\.skinAccent) private var accent

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Group {
                if track.artworkURL != nil {
                    RemoteArtwork(track: track).frame(width: 52, height: 52)
                } else {
                    Image(systemName: symbol).font(.title2).foregroundStyle(accent)
                        .frame(width: 52, height: 52).background(accent.opacity(0.08))
                }
            }.clipShape(RoundedRectangle(cornerRadius: avatar ? 26 : 10))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(track.title).font(.headline)
                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }.lineLimit(nil).frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }.padding(.vertical, 4)
    }
}
