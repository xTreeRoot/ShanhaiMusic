import MBFoundation
import SwiftUI

/// 系统分页手势与内容横向滚动协调，平台顺序直接使用插件本地顺序。
struct DiscoveryPager: View {
    let sources: [SourceTab]
    let providers: [String: any MusicSource]
    let selected: String
    let switchSource: (String) -> Void
    @State private var pageID: String?
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var openPlugins: () -> Void = {}
    var body: some View {
        if sources.isEmpty {
            ContentUnavailableView {
                Label("暂无可用音源", systemImage: "puzzlepiece.extension")
            } actions: {
                Button("管理插件", action: openPlugins).buttonStyle(.borderedProminent)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
        #if os(iOS)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(sources) { tab in
                        if let source = providers[tab.id] {
                            DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu)
                                .containerRelativeFrame(.horizontal).id(tab.id)
                        }
                    }
                }.scrollTargetLayout()
            }
                .scrollIndicators(.hidden).scrollTargetBehavior(.paging)
                .scrollPosition(id: $pageID).scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                .onChange(of: pageID) { _, id in if let id, id != selected { switchSource(id) } }
                .onChange(of: selected) { _, id in
                    if pageID != id { withAnimation(.easeInOut(duration: 0.25)) { pageID = id } }
                }
                .onChange(of: sources.map(\.id), initial: true) { _, _ in pageID = selected }
                .accessibilityIdentifier("discovery-platform-pager")
        #else
        if let source = providers[selected] { DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu) }
        #endif
        }
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
