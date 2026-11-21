import SwiftUI

/// 两行封面共享横向滚动；按列保留原始顺序，播放索引仍指向完整分区。
struct DiscoveryShelf<Content: View>: View {
    let count: Int
    let rowHeight: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        let rowCount = min(2, max(1, count))
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHGrid(rows: Array(repeating: GridItem(.fixed(rowHeight), alignment: .top), count: rowCount),
                      alignment: .top, spacing: 16, content: content)
                .padding(.horizontal, 20)
                .padding(.vertical, 4)
        }
        .scrollBounceBehavior(.basedOnSize, axes: .vertical)
        .frame(height: rowHeight * CGFloat(rowCount) + CGFloat(rowCount - 1) * 16 + 8)
    }
}
