import SwiftUI

/// 按可见宽度先填满第一行，再换到第二行；后续内容横向滚动。
struct DiscoveryShelf<Content: View>: View {
    let count: Int
    let rowHeight: CGFloat
    @ViewBuilder let content: (Int) -> Content
    @State private var width: CGFloat = 0

    var body: some View {
        let columns = max(1, Int((max(0, width - 40) + 16) / 172))
        let rowCount = min(2, max(1, (count + columns - 1) / columns))
        let capacity = columns * 2
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(Array(stride(from: 0, to: count, by: capacity)), id: \.self) { start in
                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(0..<rowCount, id: \.self) { row in
                            let first = start + row * columns
                            if first < count {
                                HStack(alignment: .top, spacing: 16) {
                                    ForEach(first..<min(first + columns, count), id: \.self) { index in
                                        content(index).frame(width: 156, height: rowHeight, alignment: .topLeading).clipped()
                                    }
                                }
                            }
                        }
                    }
                }
            }
                .padding(.horizontal, 20)
                .padding(.vertical, 4)
        }
        .scrollBounceBehavior(.basedOnSize, axes: .vertical)
        .frame(height: rowHeight * CGFloat(rowCount) + CGFloat(rowCount - 1) * 16 + 8)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}
