import MBFoundation
import SwiftUI

/// 系统分页手势与内容横向滚动协调，平台顺序直接使用插件本地顺序。
struct DiscoveryPager: View {
    let sources: [SourceTab]
    let providers: [String: any MusicSource]
    let selected: String
    let switchSource: (String) -> Void
    var pageProgress: (CGFloat) -> Void = { _ in }
    @State private var pageID: String?
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View {
        #if os(iOS)
        GeometryReader { viewport in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(sources) { tab in
                        if let source = providers[tab.id] {
                            DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu)
                                .containerRelativeFrame(.horizontal).id(tab.id)
                        }
                    }
                }.scrollTargetLayout()
                    .background(GeometryReader { page in
                        Color.clear.preference(key: DiscoveryOffsetKey.self, value: page.frame(in: .named("discovery-pages")).minX)
                    })
            }.coordinateSpace(name: "discovery-pages")
                .scrollIndicators(.hidden).scrollTargetBehavior(.paging)
                .scrollPosition(id: $pageID).scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                .onPreferenceChange(DiscoveryOffsetKey.self) { x in
                    guard viewport.size.width > 0 else { return }
                    pageProgress(min(CGFloat(max(0, sources.count - 1)), max(0, -x / viewport.size.width)))
                }
                .onChange(of: pageID) { _, id in if let id, id != selected { switchSource(id) } }
                .onChange(of: selected) { _, id in
                    if pageID != id { withAnimation(.easeInOut(duration: 0.25)) { pageID = id } }
                }
                .onChange(of: sources.map(\.id), initial: true) { _, _ in pageID = selected }
        }.accessibilityIdentifier("discovery-platform-pager")
        #else
        if let source = providers[selected] { DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu) }
        #endif
    }
}

private struct DiscoveryPlatformPage: View {
    let source: any MusicSource
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var discovery: DiscoveryModel?
    init(source: any MusicSource, videoMode: Bool, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView) {
        self.source = source; self.videoMode = videoMode; self.select = select; self.menu = menu
        _discovery = State(initialValue: (source as? any DiscoverySource).map { DiscoveryModel(source: $0) })
    }
    var body: some View {
        Group {
            if let plugin = source as? PluginSource { PluginHomeView(source: plugin, select: select, menu: menu).id(ObjectIdentifier(plugin)) }
            else if let discovery, let provider = source as? any DiscoverySource {
                DiscoveryHomeView(model: discovery, source: provider, videoMode: videoMode, select: select, menu: menu)
            } else { ContentUnavailableView("暂无内容", systemImage: "music.note") }
        }.task { if let discovery, discovery.sections.isEmpty { await discovery.refresh() } }
    }
}

private struct DiscoveryOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat { 0 }
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
