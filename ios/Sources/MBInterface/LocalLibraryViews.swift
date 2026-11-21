import MBFoundation
import SwiftUI
import UniformTypeIdentifiers

struct LocalLibraryPage: View {
    let page: LocalPage
    let library: LocalLibraryModel
    let downloads: DownloadModel
    let playback: PlaybackModel
    @State private var adding = false
    @State private var query = ""
    var body: some View {
        List {
            Group {
                if page == .albums || page == .artists {
                    ForEach(collections.filter { LocalCatalog.matches($0.title, query: query) }) { collection in
                        NavigationLink(value: page == .albums ? LocalPage.album(collection.id) : .artist(collection.id)) { LocalCollectionRow(collection: collection) }
                    }
                    if collections.isEmpty { ContentUnavailableView(LocalizedStringKey(page == .albums ? "暂无专辑" : "暂无作者"), systemImage: page == .albums ? "square.stack" : "person", description: Text("导入带标签的音频后会自动整理")) }
                } else {
                    if !tracks.isEmpty {
                        Section {
                            if let track = tracks.first {
                                HStack(spacing: 16) {
                                    ArtworkView(track: track, size: 88)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(title).font(.title2.bold())
                                        Text("\(tracks.count) 首").font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }.padding(.vertical, 12)
                            }
                            HStack(spacing: 12) {
                                Button { playback.select(filtered, index: 0) } label: { MusicActionLabel(title: "播放全部", symbol: "play.fill") }.buttonStyle(.borderedProminent)
                                Button { playback.select(filtered, index: 0); if !playback.queue.shuffled { playback.toggleShuffle() } } label: { MusicActionLabel(title: "随机播放", symbol: "shuffle") }.buttonStyle(.bordered)
                            }.disabled(filtered.isEmpty)
                        }.listRowSeparator(.hidden)
                    }
                    Section {
                        LocalTrackRows(tracks: filtered, playback: playback, downloads: downloads, playlistID: playlistID, library: library)
                            .onDelete { offsets in
                                let selected = offsets.compactMap { filtered.indices.contains($0) ? filtered[$0] : nil }
                                Task {
                                    if let playlistID { await library.remove(selected, from: playlistID) }
                                    else if page == .favorites { for track in selected { await library.toggleFavorite(track) } }
                                }
                            }
                            .onMove { offsets, destination in
                                if let playlistID { Task { await library.move(in: playlistID, offsets: offsets, to: destination) } }
                            }
                            .deleteDisabled(playlistID == nil && page != .favorites)
                            .moveDisabled(playlistID == nil || !query.isEmpty)
                        if tracks.isEmpty { ContentUnavailableView("暂无歌曲", systemImage: "music.note", description: Text(LocalizedStringKey(playlistID == nil ? "在曲目菜单添加喜欢，或导入音频" : "点添加歌曲选择资料库里的音频"))) }
                        else if filtered.isEmpty { ContentUnavailableView.search(text: query) }
                    }
                    if playlistID != nil { Section { Button("添加歌曲", systemImage: "plus") { adding = true } } }
                }
                if let error = library.errorMessage { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }

            }.modifier(SkinRow())
        }
        .listStyle(.plain).modifier(SkinSurface(defaultColor: MBTheme.background, list: true))
        .navigationTitle(Text(LocalizedStringKey(title)))
        .searchable(text: $query, prompt: "在此页面搜索")
        #if os(iOS)
        .toolbar { if playlistID != nil { ToolbarItem(placement: .primaryAction) { EditButton() } } }
        #endif
        .sheet(isPresented: $adding) { if let playlistID { LocalPlaylistAddView(playlistID: playlistID, library: library) } }
    }
    private var filtered: [Track] { tracks.filter { LocalCatalog.matches($0.title + "\n" + $0.artist + "\n" + ($0.localMetadata?.album ?? ""), query: query) } }
    private var playlistID: String? { if case .playlist(let id) = page { id } else { nil } }
    private var collections: [LocalCollection] { page == .albums ? library.catalog.albums : library.catalog.artists }
    private var tracks: [Track] {
        switch page {
        case .songs: library.catalog.tracks
        case .favorites: library.favorites
        case .recent: library.recent
        case .album(let id): library.catalog.albums.first { $0.id == id }?.tracks ?? []
        case .artist(let id): library.catalog.artists.first { $0.id == id }?.tracks ?? []
        case .playlist(let id): library.resolve(library.snapshot.playlists.first { $0.id == id }?.trackIDs ?? [])
        case .albums, .artists: []
        }
    }
    private var title: String {
        switch page {
        case .songs: "歌曲"
        case .favorites: "喜欢"
        case .recent: "最近播放"
        case .albums: "专辑"
        case .artists: "作者"
        case .album(let id): library.catalog.albums.first { $0.id == id }?.title ?? "专辑"
        case .artist(let id): library.catalog.artists.first { $0.id == id }?.title ?? "作者"
        case .playlist(let id): library.snapshot.playlists.first { $0.id == id }?.name ?? "歌单"
        }
    }
}

struct LocalTrackRows: DynamicViewContent {
    let tracks: [Track]
    let playback: PlaybackModel
    let downloads: DownloadModel
    var playlistID: String?
    var library: LocalLibraryModel?
    @State private var lyricsTrack: Track?
    @State private var importingLyrics = false
    @State private var deletion: SavedMedia?
    @State private var confirmingDeletion = false
    nonisolated var data: [Track] { tracks }
    var body: some View {
        ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
            HStack {
                Button { playback.select(tracks, index: index) } label: { TrackRow(track: track, selected: playback.queue.current?.id == track.id) }
                    .buttonStyle(.plain).accessibilityLabel("播放 \(track.title)")
                    .contextMenu { actions(track) }
                Menu { actions(track) } label: { Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44) }
                    .accessibilityLabel("曲目操作 \(track.title)")
            }
        }
        .confirmationDialog("删除音频文件？", isPresented: $confirmingDeletion, titleVisibility: .visible) {
            Button("删除音频文件", role: .destructive) { if let deletion { Task { await downloads.remove(deletion) } } }
        } message: { Text("文件将从设备移除，不能撤销。") }
        .fileImporter(isPresented: $importingLyrics, allowedContentTypes: [.item]) { result in
            switch result {
            case .success(let url): if let lyricsTrack { Task { await downloads.attachLyrics(url, to: lyricsTrack) } }
            case .failure(let error): downloads.reportImportError(error)
            }
        }
    }
    @ViewBuilder private func actions(_ track: Track) -> some View {
        TrackContextMenu(track: track, playback: playback, downloads: downloads)
        if track.localFileID != nil {
            Button("导入 LRC 歌词", systemImage: "quote.bubble") { lyricsTrack = track; importingLyrics = true }
        }
        if let playlistID, let library {
            Button("从歌单移除", systemImage: "minus.circle", role: .destructive) { Task { await library.remove([track], from: playlistID) } }
        }
        if let item = downloads.items.first(where: { $0.track.id == track.id }) {
            Button("删除音频文件", systemImage: "trash", role: .destructive) { deletion = item; confirmingDeletion = true }
        }
    }
}

struct LocalCollectionRow: View {
    let collection: LocalCollection
    var body: some View {
        HStack(spacing: 12) {
            if let track = collection.artwork { ArtworkView(track: track) }
            VStack(alignment: .leading, spacing: 5) {
                Text(collection.title).font(.body.weight(.medium))
                Text(collection.subtitle).font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 5)
    }
}

private struct LocalPlaylistAddView: View {
    let playlistID: String
    let library: LocalLibraryModel
    @State private var selected: Set<String> = []
    @State private var saving = false
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List(library.catalog.search(query)) { track in
                let existing = library.snapshot.playlists.first { $0.id == playlistID }?.trackIDs.contains(track.id) == true
                Button {
                    if selected.contains(track.id) { selected.remove(track.id) } else { selected.insert(track.id) }
                } label: {
                    HStack { TrackRow(track: track); Image(systemName: existing || selected.contains(track.id) ? "checkmark.circle.fill" : "circle").foregroundStyle(existing ? .secondary : .primary) }
                }.buttonStyle(.plain).disabled(existing || saving).accessibilityLabel("选择 \(track.title)")
            }
            .navigationTitle("添加歌曲")
            .searchable(text: $query, prompt: "搜索资料库")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加 \(selected.count) 首") {
                        saving = true
                        Task { await library.add(library.catalog.tracks.filter { selected.contains($0.id) }, to: playlistID); saving = false; if library.errorMessage == nil { dismiss() } }
                    }.disabled(selected.isEmpty || saving)
                }
            }
            .safeAreaInset(edge: .bottom) { if let error = library.errorMessage { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red).padding() } }
        }
    }
}
