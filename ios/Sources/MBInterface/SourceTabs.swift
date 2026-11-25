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
    @Environment(\.skinAccent) private var skinAccent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 3) {
                    ForEach(sources) { source in
                        let active = source.id == selected
                        Button { select(source.id) } label: {
                            Text(source.name)
                                .font(.subheadline.weight(active ? .semibold : .medium))
                                .foregroundStyle(active ? skinAccent : Color.primary.opacity(0.85))
                                .lineLimit(1)
                                .overlay(alignment: .bottom) {
                                    if active {
                                        Capsule().fill(skinAccent).frame(height: 3).offset(y: 7)
                                            .accessibilityHidden(true)
                                    }
                                }
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
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .accessibilityIdentifier("\(namespace)-tabs")
            .onChange(of: [selected] + sources.map(\.id), initial: true) { previous, current in
                guard sources.contains(where: { $0.id == selected }) else { return }
                withAnimation(reduceMotion || previous == current ? nil : .easeOut(duration: 0.2)) {
                    proxy.scrollTo(selected, anchor: .center)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
    }
}

/// 发现页的文字标签与模式图标使用同一透明层，避免系统工具栏另加玻璃色块。
extension ToolbarContent {
    @ToolbarContentBuilder var discoveryBackground: some ToolbarContent {
        #if os(iOS)
        if #available(iOS 26.0, *) { sharedBackgroundVisibility(.hidden) }
        else { self }
        #else
        self
        #endif
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
