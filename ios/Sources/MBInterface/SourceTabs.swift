import SwiftUI

struct SourceTab: Identifiable {
    let id: String
    let name: String
}

struct SourceTabs: View {
    let sources: [SourceTab]
    let selected: String
    let select: (String) -> Void
    var namespace = "source"
    var pagePosition: CGFloat? = nil
    @Environment(\.backgroundSkinImage) private var skinImage
    @Environment(\.skinAccent) private var skinAccent

    var body: some View {
        let selectedIndex = sources.firstIndex(where: { $0.id == selected }) ?? 0
        let position = min(CGFloat(max(0, sources.count - 1)), max(0, pagePosition ?? CGFloat(selectedIndex)))
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 3) {
                    ForEach(sources) { source in
                        let index = sources.firstIndex(where: { $0.id == source.id }) ?? 0
                        let weight = max(0, 1 - abs(CGFloat(index) - position))
                        let active = source.id == selected
                        Button { select(source.id) } label: {
                            Text(source.name)
                                .font(.subheadline.weight(active ? .bold : .medium))
                                .foregroundStyle(active ? skinAccent : Color.primary.opacity(0.85))
                                .scaleEffect(1 + weight * 0.02)
                                .anchorPreference(key: SourceTabBoundsKey.self, value: .bounds) { [source.id: $0] }
                                .lineLimit(1)
                                .padding(.horizontal, 7)
                                .frame(minHeight: 44)
                                .fixedSize(horizontal: true, vertical: false)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(active ? .isSelected : [])
                        .accessibilityIdentifier("\(namespace)-tab-\(source.id)")
                        .id(source.id)
                    }
                }.padding(.horizontal, 12)
                    .overlayPreferenceValue(SourceTabBoundsKey.self) { anchors in
                        GeometryReader { geometry in
                            let lower = Int(position.rounded(.down)), upper = Int(position.rounded(.up))
                            if sources.indices.contains(lower), sources.indices.contains(upper),
                               let first = anchors[sources[lower].id], let second = anchors[sources[upper].id] {
                                let a = geometry[first], b = geometry[second], fraction = position - CGFloat(lower)
                                Capsule().fill(skinAccent)
                                    .frame(width: a.width + (b.width - a.width) * fraction, height: 3)
                                    .position(x: a.midX + (b.midX - a.midX) * fraction, y: geometry.size.height - 5)
                            }
                        }.allowsHitTesting(false).accessibilityHidden(true)
                    }
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .accessibilityIdentifier("\(namespace)-tabs")
            .onChange(of: [selected] + sources.map(\.id), initial: true) { _, _ in proxy.scrollTo(selected) }
            .onChange(of: Int(position.rounded())) { _, index in
                if sources.indices.contains(index) { withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(sources[index].id) } }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .background { if skinImage == nil { Rectangle().fill(.bar) } }
    }
}

/// 根页面保留操作栏并取消大标题占位；下级页面继续使用自己的导航标题。
struct CompactRootNavigation: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.navigationBarTitleDisplayMode(.inline)
        #else
        content
        #endif
    }
}

private struct SourceTabBoundsKey: PreferenceKey {
    static var defaultValue: [String: Anchor<CGRect>] { [:] }
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) { value.merge(nextValue(), uniquingKeysWith: { _, new in new }) }
}
