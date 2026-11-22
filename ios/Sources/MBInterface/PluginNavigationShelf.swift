import MBFoundation
import SwiftUI

/// 导航入口显示真实目录预览；“更多”沿用原页面和能力合同。
struct PluginNavigationShelf: View {
    let source: PluginSource
    let item: PluginManifest.Navigation
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var refreshed: PluginNavigationPreview? = nil
    @State private var content: PluginBrowse?
    @State private var error: String?
    @ScaledMetric(relativeTo: .subheadline) private var cardHeight = 246.0
    private var title: String { item.title ?? item.page ?? item.builtin ?? "" }
    private var request: PluginPageRequest { item.page.map(PluginPageRequest.browse) ?? .collection(item.builtin ?? "", title) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NavigationLink { PluginCatalogPage(source: source, request: request, select: select, menu: menu) } label: {
                HStack {
                    MusicSectionHeading(title: title)
                    Label("更多", systemImage: "chevron.right").font(.subheadline)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(title).padding(.horizontal, 20)
            if let content {
                let tracks = Array(content.sections.flatMap(\.tracks).prefix(6))
                let entities = Array(content.sections.flatMap(\.entities).prefix(6))
                let links = Array(content.sections.flatMap(\.links).prefix(6))
                if tracks.isEmpty && entities.isEmpty && links.isEmpty {
                    Text(content.sections.first.map { $0.empty.isEmpty ? "暂无内容" : $0.empty } ?? "暂无内容")
                        .font(.subheadline).foregroundStyle(.secondary).padding(.horizontal, 20)
                } else {
                    DiscoveryShelf(count: tracks.count + entities.count + links.count, rowHeight: cardHeight) { index in
                        if index < tracks.count {
                            let track = tracks[index]
                            Button { select(tracks, index) } label: { PluginDiscoveryTile(track: track, subtitle: track.artist) }
                                .buttonStyle(.plain).contextMenu { menu(track) }
                        } else if index < tracks.count + entities.count {
                            let entity = entities[index - tracks.count]
                            NavigationLink { PluginCatalogPage(source: source, request: .entity(entity), select: select, menu: menu) } label: {
                                PluginDiscoveryTile(track: entity.artworkTrack(sourceID: source.manifest.id), subtitle: entity.subtitle)
                            }.buttonStyle(.plain)
                        } else {
                            let link = links[index - tracks.count - entities.count]
                            NavigationLink { PluginBrowseView(source: source, page: link.page, select: select, menu: menu) } label: {
                                PluginDiscoveryTile(track: artwork(link), subtitle: link.subtitle)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
            if let error {
                MusicErrorRow(message: error) { Task { await load() } }.padding(.horizontal, 20)
            } else if content == nil { ProgressView().frame(maxWidth: .infinity) }
        }.task(id: refreshed?.revision) {
            if let refreshed {
                if let loaded = refreshed.content { content = loaded }
                error = refreshed.error
            } else { await load() }
        }
    }
    private func artwork(_ link: PluginBrowse.Link) -> Track {
        var track = Track(sourceID: source.manifest.id, sourceTrackID: link.page, title: link.title, artist: "", artworkURL: link.imageURL)
        track.artworkHeaders = link.artworkHeaders; return track
    }
    private func load() async {
        do {
            let loaded: PluginBrowse
            if let page = item.page { loaded = try await source.browse(page) }
            else { loaded = try await source.collection(item.builtin ?? "").0 }
            guard !Task.isCancelled else { return }; content = loaded; error = nil
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
