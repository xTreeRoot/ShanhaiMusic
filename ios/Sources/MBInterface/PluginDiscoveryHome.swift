import MBFoundation
import SwiftUI

/// 插件首页复用目录实体和路由，首页摘要按可见宽度排列，最多两行横滑封面。
struct PluginDiscoveryHome: View {
    let source: PluginSource
    let capabilities: Set<String>
    let content: PluginBrowse?
    let loading: Bool
    let error: String?
    let retry: () -> Void
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var previews: [String: PluginNavigationPreview] = [:]
    var refresh: () async -> Void = {}
    var showNavigation = true
    var nextOffset: Int? = nil
    var loadMore: () async -> Void = {}
    @ScaledMetric(relativeTo: .subheadline) private var cardHeight = 246.0

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                if let error { MusicErrorRow(message: error, retry: retry).padding(.horizontal, 20) }
                if let content {
                    ForEach(content.sections) { section in
                        VStack(alignment: .leading, spacing: 14) {
                            HStack {
                                MusicSectionHeading(title: section.title)
                                if let collection = section.moreCollection {
                                    NavigationLink { PluginCatalogPage(source: source, request: .collection(collection, section.title), select: select, menu: menu) } label: { Label("更多", systemImage: "chevron.right").font(.subheadline) }
                                } else if let page = section.morePage {
                                    NavigationLink {
                                        PluginBrowseView(source: source, page: page, select: select, menu: menu)
                                    } label: { Label("更多", systemImage: "chevron.right").font(.subheadline) }
                                    .accessibilityLabel("查看更多\(section.title)")
                                } else if let item = (source.manifest.navigation ?? []).first(where: { $0.title == section.title && $0.builtin != "home" && $0.builtin != "search" }) {
                                    NavigationLink {
                                        PluginCatalogPage(source: source, request: item.page.map(PluginPageRequest.browse) ?? .collection(item.builtin ?? "", section.title), select: select, menu: menu)
                                    } label: { Label("更多", systemImage: "chevron.right").font(.subheadline) }
                                    .accessibilityLabel("查看更多\(section.title)")
                                }
                            }.padding(.horizontal, 20)
                            let count = section.tracks.count + section.entities.count + section.links.count
                            if count == 0 {
                                if !section.empty.isEmpty { Text(section.empty).font(.subheadline).foregroundStyle(.secondary).padding(.horizontal, 20) }
                            } else {
                                DiscoveryShelf(count: count, rowHeight: cardHeight) { index in
                                    if index < section.tracks.count {
                                        let track = section.tracks[index]
                                        Button { select(section.tracks, index) } label: {
                                            PluginDiscoveryTile(track: track, subtitle: track.artist)
                                        }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)")
                                            .accessibilityIdentifier("plugin-card-\(section.id)-\(index)")
                                            .modifier(TrackProblemAccessibility(track: track))
                                            .contextMenu { menu(track) }
                                    } else if index < section.tracks.count + section.entities.count {
                                        let entity = section.entities[index - section.tracks.count]
                                        NavigationLink {
                                            PluginCatalogPage(source: source, request: .entity(entity), select: select, menu: menu)
                                        } label: {
                                            PluginDiscoveryTile(track: entity.artworkTrack(sourceID: source.manifest.id),
                                                                subtitle: entity.subtitle)
                                        }.buttonStyle(.plain)
                                    } else {
                                        let link = section.links[index - section.tracks.count - section.entities.count]
                                        NavigationLink {
                                            PluginBrowseView(source: source, page: link.page, select: select, menu: menu)
                                        } label: {
                                            PluginDiscoveryTile(track: artworkTrack(id: link.page, title: link.title, url: link.imageURL, headers: link.artworkHeaders),
                                                                subtitle: link.subtitle)
                                        }.buttonStyle(.plain)
                                    }
                                }.accessibilityIdentifier("plugin-shelf-\(section.id)")
                            }
                        }
                    }
                }
                ForEach(Array((showNavigation ? source.discoveryNavigation(content) : []).enumerated()), id: \.offset) { _, item in
                    PluginNavigationShelf(source: source, item: item, select: select, menu: menu, refreshed: previews[item.previewKey])
                }
                if let nextOffset, !loading && error == nil {
                    ProgressView().task(id: nextOffset) { await loadMore() }
                }
                if loading && content == nil { ProgressView().frame(maxWidth: .infinity).padding(.vertical, 80) }
                if !loading && content?.sections.isEmpty != false && error == nil {
                    ContentUnavailableView("暂无内容", systemImage: "music.note")
                }
            }.padding(.top, 12).padding(.bottom, 24)
                .background(ScrollRefreshControl(action: refresh))
        }.accessibilityIdentifier("plugin-discovery-scroll")
    }

    private func artworkTrack(id: String, title: String, url: URL?, headers: [String: String]) -> Track {
        var track = Track(sourceID: source.manifest.id, sourceTrackID: id, title: title, artist: "", artworkURL: url, durationMilliseconds: 0)
        track.artworkHeaders = headers; return track
    }
}

struct PluginDiscoveryTile: View {
    let track: Track
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RemoteArtwork(track: track).frame(width: 156, height: 156).accessibilityHidden(true).clipShape(RoundedRectangle(cornerRadius: 14))
            Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                .frame(minHeight: 38, alignment: .topLeading).multilineTextAlignment(.leading)
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }.frame(width: 156, alignment: .topLeading).contentShape(Rectangle())
    }
}
