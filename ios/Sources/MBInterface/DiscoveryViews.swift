import MBFoundation
import SwiftUI

/// 以封面和横向精选组织首页；完整曲目列表留给各分区详情。
struct DiscoveryHomeView: View {
    let model: DiscoveryModel
    let source: any DiscoverySource
    var videoMode = false
    let select: ([Track], Int) -> Void
    var menu: (Track) -> AnyView = { _ in AnyView(EmptyView()) }

    @ScaledMetric(relativeTo: .subheadline) private var cardHeight = 246.0

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 30) {
                if let message = model.errorMessage { MusicErrorRow(message: message) { Task { await model.refresh() } }.padding(.horizontal, 20) }
                if model.sections.isEmpty && model.isLoading { ProgressView("正在加载首页").frame(maxWidth: .infinity).padding(.vertical, 100) }
                ForEach(model.sections) { section in
                    VStack(alignment: .leading, spacing: 14) {
                        NavigationLink(value: MBPage.discovery(section.category)) {
                            HStack(alignment: .center) {
                                MusicSectionHeading(title: section.category.title)
                                Text("更多").font(.subheadline).foregroundStyle(.secondary)
                                Image(systemName: "chevron.right").font(.subheadline.bold()).foregroundStyle(.tertiary)
                            }.frame(minHeight: 44).contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityLabel("查看更多\(section.category.title)").padding(.horizontal, 20)
                        if let message = section.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20) }
                        if section.tracks.isEmpty {
                            Text(LocalizedStringKey(section.errorMessage == nil ? "暂无视频" : "下拉刷新重试")).foregroundStyle(.secondary).padding(.horizontal, 20)
                        } else if videoMode {
                            VideoTrackGrid(tracks: section.tracks, select: select, menu: menu).padding(.horizontal, 16)
                        } else if section.category == .chart {
                            VStack(spacing: 0) {
                                ForEach(Array(section.tracks.prefix(3).enumerated()), id: \.element.id) { index, track in
                                    HStack(spacing: 12) {
                                        Text("\(index + 1)").font(.title3.bold().monospacedDigit()).foregroundStyle(.secondary).frame(width: 24)
                                        Button { select(section.tracks, index) } label: { TrackRow(track: track) }
                                            .buttonStyle(.plain).accessibilityLabel("播放 \(track.title)，\(track.artist)")
                                        Menu { menu(track) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                                    }.contextMenu { menu(track) }
                                    if index < min(2, section.tracks.count - 1) { Divider().padding(.leading, 94) }
                                }
                            }.padding(.horizontal, 20)
                        } else {
                            DiscoveryShelf(count: section.tracks.count, rowHeight: cardHeight) { index in
                                let track = section.tracks[index]
                                DiscoveryCoverCard(track: track, prominent: section.category == .recommended,
                                                   play: { select(section.tracks, index) }, menu: { menu(track) })
                            }
                        }
                    }
                }
                if model.sections.isEmpty && !model.isLoading {
                    ContentUnavailableView {
                        Label("从喜欢的声音开始", systemImage: "music.note")
                    } actions: { Button("重新加载") { Task { await model.refresh() } }.buttonStyle(.borderedProminent) }
                }
            }.padding(.top, 12).padding(.bottom, 24)
        }.modifier(SkinSurface(defaultColor: MBTheme.background)).refreshable { await model.refresh() }
    }
}

private struct DiscoveryCoverCard<MenuContent: View>: View {
    let track: Track
    let prominent: Bool
    let play: () -> Void
    @ViewBuilder let menu: () -> MenuContent
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: play) {
                RemoteArtwork(track: track).aspectRatio(1, contentMode: .fit)
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: "play.fill").font(.subheadline).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(.black.opacity(0.3), in: Circle()).padding(12)
                    }.clipShape(RoundedRectangle(cornerRadius: prominent ? 18 : 12))
            }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)，\(track.artist)")
            HStack(alignment: .top, spacing: 0) {
                Button(action: play) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
                        Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)，\(track.artist)")
                Menu(content: menu) { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
            }
        }.frame(width: 156).contextMenu(menuItems: menu)
    }
}

struct DiscoveryPageView: View {
    @State private var model: DiscoveryPageModel
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    init(source: any DiscoverySource, category: DiscoveryCategory, videoMode: Bool = false, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView = { _ in AnyView(EmptyView()) }) {
        _model = State(initialValue: DiscoveryPageModel(source: source, category: category)); self.select = select; self.menu = menu
        self.videoMode = videoMode
    }
    var body: some View {
        List {
            Group {
                if let section = model.section, !section.tracks.isEmpty {
                    if videoMode {
                        if let subtitle = section.subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
                        VideoTrackGrid(tracks: section.tracks, select: select, menu: menu).listRowSeparator(.hidden)
                    } else {
                        Section {
                            HStack(spacing: 12) {
                                if let track = section.tracks.first { ArtworkView(track: track, size: 76) }
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(LocalizedStringKey(model.category.title)).font(.title2.bold())
                                    if let subtitle = section.subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
                                    Text("\(section.tracks.count) 首").font(.caption).foregroundStyle(.secondary)
                                }
                            }.padding(.vertical, 8)
                            Button { select(section.tracks, 0) } label: { MusicActionLabel(title: "播放全部", symbol: "play.fill") }.buttonStyle(.borderedProminent)
                        }.listRowSeparator(.hidden)
                        Section {
                            ForEach(Array(section.tracks.enumerated()), id: \.element.id) { index, track in
                                HStack(spacing: 4) {
                                    Button { select(section.tracks, index) } label: { TrackRow(track: track) }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)，\(track.artist)")
                                    Menu { menu(track) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                                }.contextMenu { menu(track) }
                            }
                        }
                    }
                }
                if let message = model.errorMessage { MusicErrorRow(message: message) { Task { await model.refresh() } } }
                if model.isLoading && model.section == nil { ProgressView().frame(maxWidth: .infinity) }
                else if model.section?.tracks.isEmpty == true { ContentUnavailableView("暂无视频", systemImage: "music.note") }

            }.modifier(SkinRow())
        }.listStyle(.plain).modifier(SkinSurface(defaultColor: MBTheme.background, list: true))
        .navigationTitle(Text(LocalizedStringKey(model.category.title)))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { if model.section == nil { await model.refresh() } }.refreshable { await model.refresh() }
    }
}
