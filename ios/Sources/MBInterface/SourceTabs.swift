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
    @Environment(\.backgroundSkinImage) private var skinImage
    @Environment(\.skinAccent) private var skinAccent

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 3) {
                    ForEach(sources) { source in
                        let active = source.id == selected
                        Button { select(source.id) } label: {
                            Text(source.name)
                                .font(.subheadline.weight(active ? .bold : .medium))
                                .foregroundStyle(active ? .primary : .secondary)
                                .lineLimit(1)
                                .padding(.horizontal, 7)
                                .frame(minHeight: 44)
                                .fixedSize(horizontal: true, vertical: false)
                                .contentShape(Rectangle())
                                .overlay(alignment: .bottom) {
                                    if active { Capsule().fill(skinAccent).frame(height: 3).padding(.horizontal, 7).padding(.bottom, 4) }
                                }
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
            .onChange(of: [selected] + sources.map(\.id), initial: true) { _, _ in
                proxy.scrollTo(selected)
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
