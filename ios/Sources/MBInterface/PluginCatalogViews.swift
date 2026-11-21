import MBFoundation
import SwiftUI

struct PluginPersonalLinks: View {
    let source: PluginSource
    let account: SourceAccount?
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var available: Set<String> = []
    private let entries = [("playlists", "平台歌单"), ("liked", "喜欢的歌曲"), ("recent", "最近播放"), ("savedAlbums", "收藏专辑"), ("shows", "订阅播客"), ("stations", "电台")]
    var body: some View {
        let personal = source.personalNavigation
        ForEach(personal, id: \.previewKey) { item in
            if let page = item.page {
                NavigationLink(item.title ?? page) { PluginBrowseView(source: source, page: page, select: select, menu: menu) }
            } else if let method = item.builtin, available.contains(method) {
                let title = item.title ?? entries.first(where: { $0.0 == method })?.1 ?? method
                NavigationLink(LocalizedStringKey(title)) { PluginCatalogPage(source: source, request: .collection(method, title), select: select, menu: menu) }
            }
        }
        if let page = account?.page, !personal.contains(where: { $0.page == page }) {
            NavigationLink("我的主页") { PluginBrowseView(source: source, page: page, select: select, menu: menu) }
        }
        ForEach(entries, id: \.0) { method, title in
            if available.contains(method), !personal.contains(where: { $0.builtin == method && $0.page == nil }) {
                NavigationLink(LocalizedStringKey(title)) { PluginCatalogPage(source: source, request: .collection(method, title), select: select, menu: menu) }
            }
        }
        Color.clear.frame(height: 0).task {
            var found: Set<String> = []
            for (method, _) in entries where (try? await source.supports(method)) == true { found.insert(method) }
            available = found
        }.listRowInsets(EdgeInsets()).accessibilityHidden(true)
    }
}

enum PluginPageRequest {
    case home, browse(String), collection(String, String), entity(PluginEntity), search(String, String)
    var isHome: Bool { if case .home = self { true } else { false } }
}

struct PluginCatalogPage: View {
    @Environment(\.skinAccent) private var skinAccent
    let source: PluginSource
    let request: PluginPageRequest
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var content: PluginBrowse?
    @State private var filterPage: String?
    @State private var homeTab: String?
    @State private var filterTabs: [PluginBrowse.Link] = []
    @State private var navigationPreviews: [String: PluginNavigationPreview] = [:]
    @State private var error: String?
    @State private var loading = false
    @State private var loadRevision = 0
    @State private var initialLoad: Task<Void, Never>?
    @State private var artworkPrefetch: Task<Void, Never>?
    @State private var offset = 0
    @State private var more = false
    @State private var appendFailed = false
    @State private var capabilities: Set<String> = []
    @State private var changing = false
    @State private var enabled = false
    @State private var rename = false
    @State private var delete = false
    @State private var create = false
    @State private var name = ""
    @State private var adding = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.stationPlayback) private var playback
    @Environment(\.sourceNames) private var sourceNames
    var body: some View {
        VStack(spacing: 0) {
            if !filterTabs.isEmpty {
                SourceTabs(sources: filterTabs.map { SourceTab(id: $0.page, name: $0.title) },
                           selected: filterPage ?? content?.selectedTab ?? filterTabs[0].page,
                           select: chooseFilter, namespace: "plugin-filter")
            }
            if request.isHome {
                PluginDiscoveryHome(source: source, capabilities: capabilities, content: content, loading: loading, error: error,
                                    retry: { Task { await load(refresh: true) } }, select: select, menu: menu, previews: navigationPreviews, refresh: { await load(refresh: true) },
                                    showNavigation: filterPage == nil, nextOffset: more ? offset : nil, loadMore: { await load(append: true) })
            } else {
                catalogList
            }
        }
        .modifier(SkinSurface(defaultColor: MBTheme.background))
        .navigationTitle(title)
        .onAppear { startLoading() }
        .onDisappear { initialLoad?.cancel(); initialLoad = nil; artworkPrefetch?.cancel() }
    }

    private func chooseFilter(_ page: String) {
        let next = request.isHome && page == homeTab ? nil : page
        guard filterPage != next else { return }
        initialLoad?.cancel(); filterPage = next
        content = nil; more = false
        initialLoad = Task { await load(refresh: true) }
    }

    private var catalogList: some View {
        List {
            Group {
                if let content {
                    if !content.subtitle.isEmpty { Text(content.subtitle).font(.subheadline).foregroundStyle(.secondary) }
                    if !content.description.isEmpty { Text(content.description).font(.callout).foregroundStyle(.secondary) }
                    let tracks = content.sections.flatMap(\.tracks)
                    if !tracks.isEmpty, case .entity = request {
                        Button { play(tracks, index: 0) } label: { MusicActionLabel(title: "播放全部", symbol: "play.fill") }.buttonStyle(.borderedProminent)
                    }
                    ForEach(content.sections) { section in
                        Section {
                            if section.trackLayout == "videos" {
                                VideoTrackGrid(tracks: section.tracks, select: { play($0, index: $1) }, menu: menu, includeArtistInLabel: false)
                            } else {
                            ForEach(Array(section.tracks.enumerated()), id: \.offset) { index, track in
                                HStack {
                                    Button { play(section.tracks, index: index) } label: { TrackRow(track: track) }.buttonStyle(.plain).accessibilityLabel("播放 \(track.title)")
                                        .modifier(TrackProblemAccessibility(track: track))
                                    Menu { menu(track); playlistTrackActions(track, index: index) } label: { Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                                }.contextMenu { menu(track); playlistTrackActions(track, index: index) }
                            }
                            }
                            ForEach(section.entities) { entity in
                                NavigationLink { PluginCatalogPage(source: source, request: .entity(entity), select: select, menu: menu) } label: {
                                    HStack(spacing: 12) {
                                        if entity.value["imageUrl"].string != nil { ArtworkView(track: entity.artworkTrack(sourceID: source.manifest.id)) }
                                        else { Image(systemName: entity.kind == .artist ? "person.crop.circle.fill" : entity.kind == .playlist ? "music.note.list" : "square.stack.fill").font(.title2).foregroundStyle(skinAccent).frame(width: 52, height: 52).background(skinAccent.opacity(0.08), in: RoundedRectangle(cornerRadius: 10)) }
                                        VStack(alignment: .leading, spacing: 5) { Text(entity.title).font(.headline); if !entity.subtitle.isEmpty { Text(entity.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2) } }
                                    }
                                }
                            }
                            ForEach(section.links) { link in
                                NavigationLink { PluginBrowseView(source: source, page: link.page, select: select, menu: menu) } label: {
                                    VStack(alignment: .leading) { Text(link.title); if !link.subtitle.isEmpty { Text(link.subtitle).font(.caption).foregroundStyle(.secondary) } }
                                }
                            }
                            if let page = section.morePage { NavigationLink("查看更多") { PluginBrowseView(source: source, page: page, select: select, menu: menu) } }
                            if section.tracks.isEmpty && section.entities.isEmpty && section.links.isEmpty && !section.empty.isEmpty { Text(section.empty).foregroundStyle(.secondary) }
                        } header: { Text(section.title) }
                    }
                }
                if let action = saveAction, capabilities.contains(action.method) {
                    Button(LocalizedStringKey(enabled ? action.off : action.on), systemImage: enabled ? "checkmark.circle.fill" : "plus.circle") {
                        guard case .entity(let item) = request else { return }
                        Task { await change { try await source.change(action.method, entity: item, on: !enabled); enabled.toggle() } }
                    }.disabled(changing)
                }
                if case .collection("playlists", _) = request, capabilities.contains("createPlaylist") { Button("新建平台歌单") { name = ""; create = true } }
                if case .entity(let item) = request, item.kind == .playlist && item.value["mine"] == .bool(true) && item.value["fixed"] != .bool(true) {
                    if capabilities.contains("addToPlaylist") { Button("添加平台歌曲") { adding = true } }
                    if capabilities.contains("renamePlaylist") { Button("重命名歌单") { name = content?.title ?? item.title; rename = true } }
                    if capabilities.contains("deletePlaylist") { Button("删除平台歌单", role: .destructive) { delete = true } }
                }
                if more { PaginationFooter(page: "\(loadRevision):\(offset)", loading: loading, blocked: error != nil || changing) { await load(append: true) } }
                if loading { ProgressView() }
                if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load(append: appendFailed) } } }
                if !loading && content?.sections.isEmpty != false && error == nil { ContentUnavailableView("暂无内容", systemImage: "music.note") }

            }.modifier(SkinRow())
        }
        .listStyle(.plain)
        .refreshable { await load(refresh: true) }
        .modifier(PaginationViewport())
        .modifier(SkinSurface(list: true))
        .navigationTitle(title)
        .alert("歌单名称", isPresented: $rename) { TextField("名称", text: $name); Button("取消", role: .cancel) {}; Button("保存") { Task { await playlistName(create: false) } }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || changing) }
        .alert("新建平台歌单", isPresented: $create) { TextField("名称", text: $name); Button("取消", role: .cancel) {}; Button("创建") { Task { await playlistName(create: true) } }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || changing) }
        .sheet(isPresented: $adding) {
            if case .entity(let item) = request {
                PluginPlaylistAddView(source: source, playlist: item, existing: Set(content?.sections.flatMap(\.tracks).map(\.sourceTrackID) ?? [])) { await load() }
            }
        }
        .alert("删除平台歌单？", isPresented: $delete) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                guard case .entity(let item) = request else { return }
                Task { await change { try await source.mutate("deletePlaylist", arguments: .object(["id": .string(item.sourceID)])); dismiss() } }
            }
        }
    }
    private func startLoading() {
        initialLoad?.cancel()
        initialLoad = Task {
            var found: Set<String> = []
            for method in ["liked", "savedAlbums", "stations", "shows", "search", "createPlaylist", "renamePlaylist", "deletePlaylist", "addToPlaylist", "removeFromPlaylist", "movePlaylistTrack", "savePlaylist", "saveAlbum", "followArtist", "subscribeShow"] where (try? await source.supports(method)) == true { found.insert(method) }
            guard !Task.isCancelled else { return }
            capabilities = found
            if case .entity(let item) = request { enabled = item.value["saved"] == .bool(true) || item.value["following"] == .bool(true) || item.value["subscribed"] == .bool(true) }
            await load()
        }
    }
    private var title: String {
        if request.isHome { return sourceNames[source.manifest.id] ?? source.displayName }
        if case .collection(_, let title) = request { return AppLocalization.text(title) }
        if let content, !content.title.isEmpty { return content.title }
        switch request { case .home: return source.displayName; case .browse(let page): return page; case .collection(_, let title): return title; case .entity(let item): return item.title; case .search(let query, _): return query }
    }
    private func play(_ tracks: [Track], index: Int) {
        if case .entity(let item) = request, item.kind == .station, let playback {
            playback.startStation(PlaybackStation(sourceID: source.manifest.id, id: item.sourceID, title: item.title), tracks: tracks, index: index)
        } else { select(tracks, index) }
    }
    private var saveAction: (method: String, on: String, off: String)? {
        guard case .entity(let item) = request else { return nil }
        switch item.kind {
        case .album: return ("saveAlbum", "收藏专辑", "取消收藏专辑")
        case .artist: return ("followArtist", "关注作者", "取消关注作者")
        case .playlist: return item.value["mine"] != .bool(true) ? ("savePlaylist", "收藏歌单", "取消收藏歌单") : nil
        case .show: return ("subscribeShow", "订阅播客", "取消订阅")
        case .station: return nil
        }
    }
    @ViewBuilder private func playlistTrackActions(_ track: Track, index: Int) -> some View {
        if case .entity(let item) = request, item.kind == .playlist, item.value["mine"] == .bool(true), item.value["fixed"] != .bool(true) {
            if capabilities.contains("removeFromPlaylist") { Button("从歌单移除", role: .destructive) { Task { await playlistTracks("removeFromPlaylist", ids: [track.sourceTrackID]) } } }
            if capabilities.contains("movePlaylistTrack"), index > 0 { Button("向上移动") { Task { await change { try await source.mutate("movePlaylistTrack", arguments: .object(["id": .string(item.sourceID), "from": .number(Double(index)), "to": .number(Double(index - 1))])); await load() } } } }
        }
    }
    private func playlistTracks(_ method: String, ids: [String]) async {
        guard !ids.isEmpty, case .entity(let item) = request else { return }
        await change { try await source.mutate(method, arguments: .object(["id": .string(item.sourceID), "trackIds": .array(ids.map(PluginJSON.string))])); await load() }
    }
    private func playlistName(create: Bool) async {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        await change {
            var values: [String: PluginJSON] = ["name": .string(name)]
            if case .entity(let item) = request { values["id"] = .string(item.sourceID) }
            try await source.mutate(create ? "createPlaylist" : "renamePlaylist", arguments: .object(values)); await load()
        }
    }
    private func change(_ operation: () async throws -> Void) async { guard !changing else { return }; changing = true; defer { changing = false }; do { try await operation() } catch { self.error = error.localizedDescription } }
    private func load(append: Bool = false, refresh: Bool = false) async {
        if refresh && changing { return }
        if append { guard more, !loading else { return } }
        appendFailed = append
        loadRevision += 1
        let revision = loadRevision
        loading = true; error = nil
        defer { if revision == loadRevision { loading = false } }
        do {
            let page: PluginBrowse, hasMore: Bool, count: Int
            if refresh && (!request.isHome || filterPage != nil) { await source.invalidateCatalog() }
            switch filterPage.map(PluginPageRequest.browse) ?? request {
            case .home:
                if refresh {
                    let snapshot = try await source.refreshDiscovery()
                    guard revision == loadRevision, !Task.isCancelled else { return }
                    page = snapshot.home; navigationPreviews = snapshot.previews
                } else { page = try await source.home() }
                hasMore = false; count = 0
            case .browse(let value): (page, hasMore, count) = try await source.browsePage(value, offset: append ? offset : 0)
            case .collection(let method, _): (page, hasMore, count) = try await source.collection(method, offset: append ? offset : 0)
            case .entity(let item): (page, hasMore, count) = try await source.entity(item, offset: append ? offset : 0)
            case .search(let query, let kind): (page, hasMore, count) = try await source.searchCatalog(query, kind: kind, offset: append ? offset : 0)
            }
            guard revision == loadRevision, !Task.isCancelled else { return }
            if append, var previous = content { previous.sections += page.sections.enumerated().map { index, section in section.reidentified("\(offset)-\(index)") }; content = previous; offset += count }
            else { content = page; offset = count }
            if !page.tabs.isEmpty { filterTabs = page.tabs }
            if request.isHome && filterPage == nil { homeTab = page.selectedTab }
            more = hasMore && count > 0
            artworkPrefetch?.cancel()
            let artwork = page.artworkTracks(sourceID: source.manifest.id)
            artworkPrefetch = Task { await ArtworkCache.shared.prefetch(artwork) }
        } catch { if revision == loadRevision, !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

struct PluginCatalogSearch: View {
    let source: PluginSource
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var input = ""
    @State private var query = ""
    @State private var kind = "tracks"
    var body: some View {
        VStack {
            Picker("类型", selection: $kind) {
                ForEach([("tracks", "歌曲"), ("albums", "专辑"), ("artists", "作者"), ("playlists", "歌单"), ("shows", "播客")], id: \.0) { key, title in Text(LocalizedStringKey(title)).tag(key) }
            }.pickerStyle(.segmented).padding(.horizontal)
            if !query.isEmpty { PluginCatalogPage(source: source, request: .search(query, kind), select: select, menu: menu).id(query + kind) }
            else { ContentUnavailableView("搜索音源", systemImage: "magnifyingglass") }
        }.searchable(text: $input).onSubmit(of: .search) { query = input.trimmingCharacters(in: .whitespacesAndNewlines) }.navigationTitle("搜索音源")
    }
}
