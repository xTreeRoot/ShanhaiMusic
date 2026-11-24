import MBFoundation
import SwiftUI

/// 平板侧栏使用当前来源公开的栏目，并把详情路径作为唯一选中状态。
struct DiscoverySidebar: View {
    let source: any MusicSource
    @Binding var path: [MBPage]
    @Binding var columns: NavigationSplitViewVisibility
    let mode: PlaybackMode
    let browse: () -> Void
    let chooseMode: (PlaybackMode) -> Void
    let openSettings: () -> Void
    @Environment(\.skinAccent) private var accent

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("山海").font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Button { columns = .detailOnly } label: {
                    Image(systemName: "sidebar.left").frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel("收起侧边栏")
            }.padding(.horizontal, 16).padding(.top, 8)
            entries
        }
        // 标题和列表共用背景，避免系统浮动导航栏给标题单独绘制一块底色。
        .modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
        .background(MBTheme.grouped.ignoresSafeArea())
        .navigationTitle("")
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 260)
        .accessibilityIdentifier("discovery-sidebar")
    }

    private var entries: some View {
        List {
            Section("发现") {
                destination("首页", symbol: "house", page: nil, id: "home")
                if let plugin = source as? PluginSource {
                    ForEach(plugin.manifest.discoverySections.filter { $0.builtin != "home" }, id: \.id) { item in
                        let page = item.page.map { MBPage.pluginPage($0, item.title) }
                            ?? .pluginCollection(item.builtin ?? "", item.title)
                        destination(item.title, symbol: "square.grid.2x2", page: page, id: item.id)
                    }
                } else if source is any DiscoverySource {
                    ForEach(DiscoveryCategory.allCases) { category in
                        destination(category.title, symbol: "music.note.list", page: .discovery(category), id: category.rawValue)
                    }
                }
            }
            Section("播放器") {
                modeButton("听歌模式", symbol: "music.note", value: .music)
                modeButton("视频模式", symbol: "play.rectangle", value: .video)
                Button(action: openSettings) {
                    Label("网络设置", systemImage: "gearshape").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
    }

    private func destination(_ title: String, symbol: String, page: MBPage?, id: String) -> some View {
        let selected = path.first == page
        return Button {
            browse()
            path = page.map { [$0] } ?? []
        } label: {
            Label(LocalizedStringKey(title), systemImage: symbol)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                .fontWeight(selected ? .semibold : .regular)
                .foregroundStyle(selected ? accent : .primary)
        }.buttonStyle(.plain)
            .listRowBackground(selected ? accent.opacity(0.14) : Color.clear)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("sidebar-" + id)
    }

    private func modeButton(_ title: String, symbol: String, value: PlaybackMode) -> some View {
        Button { chooseMode(value) } label: {
            HStack {
                Label(LocalizedStringKey(title), systemImage: symbol)
                Spacer()
                if mode == value { Image(systemName: "checkmark").accessibilityHidden(true) }
            }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain)
            .accessibilityValue(LocalizedStringKey(mode == value ? "已选中" : "未选中"))
    }
}
