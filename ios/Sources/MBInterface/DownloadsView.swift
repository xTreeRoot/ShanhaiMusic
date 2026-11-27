import CoreTransferable
import MBFoundation
import SwiftUI
import UniformTypeIdentifiers

enum LocalPage: Hashable { case songs, favorites, recent, albums, artists, album(String), artist(String), playlist(String) }

struct DownloadsView: View {
    @Environment(\.skinAccent) private var skinAccent
    let model: DownloadModel
    let library: LocalLibraryModel
    let playback: PlaybackModel
    @State private var importing = false
    @State private var importingFolder = false
    @State private var creating = false
    @State private var renaming = false
    @State private var deleting = false
    @State private var playlist: LocalPlaylist?
    @State private var name = ""
    @State private var query = ""
    @State private var clearingCache = false
    var body: some View {
        NavigationStack {
            List {
                Group {
                    if let progress = model.importProgress { HStack { ProgressView(); Text("正在导入 \(progress)").lineLimit(1) } }
                    if !model.downloading.isEmpty {
                        Section("正在下载") {
                            ForEach(model.downloading.keys.sorted(), id: \.self) { id in
                                HStack { ProgressView(); Text(model.downloading[id] ?? ""); Spacer(); Button("取消") { model.cancel(id) } }
                            }
                        }
                    }
                    if query.isEmpty { navigation; ResourceLibrarySection(library: library, playback: playback); recentAdditions; playlists }
                    else { results }
                    Section {
                        HStack { Text("播放缓存"); Spacer(); Text(ByteCountFormatter.string(fromByteCount: model.cacheBytes, countStyle: .file)).foregroundStyle(.secondary) }
                        Button("清空播放缓存", role: .destructive) { clearingCache = true }.disabled(model.cacheBytes == 0)
                    }
                    if let error = model.errorMessage ?? library.errorMessage { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }

                }.modifier(SkinRow())
            }
            .modifier(MusicGroupedList())
            .navigationTitle("")
            .modifier(CompactRootNavigation())
            .confirmationDialog("清空播放缓存？", isPresented: $clearingCache, titleVisibility: .visible) {
                Button("清空播放缓存", role: .destructive) { Task { await model.clearCache() } }
            } message: { Text("已下载和导入的音频会保留。") }
            .searchable(text: $query, prompt: "搜索本地歌曲、专辑、作者、歌单")
            .navigationDestination(for: LocalPage.self) { page in LocalLibraryPage(page: page, library: library, downloads: model, playback: playback) }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("导入音频", systemImage: "square.and.arrow.down") { importingFolder = false; importing = true }
                        Button("导入文件夹", systemImage: "folder") { importingFolder = true; importing = true }
                        Button("新建歌单", systemImage: "text.badge.plus") { name = ""; creating = true }
                    } label: { Label("添加到资料库", systemImage: "plus") }
                }
            }
            .task { await model.refresh() }
            .refreshable { await model.refresh() }
            .fileImporter(isPresented: $importing, allowedContentTypes: importingFolder ? [.folder] : [.audio], allowsMultipleSelection: !importingFolder) { result in
                switch result {
                case .success(let urls): Task { if importingFolder, let folder = urls.first { await model.importFolder(folder) } else { await model.importFiles(urls) } }
                case .failure(let error): model.reportImportError(error)
                }
            }
            .alert("新建歌单", isPresented: $creating) {
                TextField("歌单名称", text: $name)
                Button("取消", role: .cancel) {}
                Button("创建") { Task { await library.createPlaylist(name) } }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .alert("重命名歌单", isPresented: $renaming) {
                TextField("歌单名称", text: $name)
                Button("取消", role: .cancel) {}
                Button("保存") { if let playlist { Task { await library.renamePlaylist(playlist.id, name: name) } } }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .confirmationDialog("删除歌单“\(playlist?.name ?? "")”？", isPresented: $deleting, titleVisibility: .visible) {
                Button("删除歌单", role: .destructive) { if let playlist { Task { await library.deletePlaylist(playlist.id) } } }
            } message: { Text("音频文件会保留在资料库里。") }
        }
    }
    private var navigation: some View {
        Section {
            link("歌曲", systemImage: "music.note", page: .songs, count: library.catalog.tracks.count)
            link("专辑", systemImage: "square.stack", page: .albums, count: library.catalog.albums.count)
            link("作者", systemImage: "person.2", page: .artists, count: library.catalog.artists.count)
            link("喜欢", systemImage: "heart", page: .favorites, count: library.favorites.count)
            link("最近播放", systemImage: "clock", page: .recent, count: library.recent.count)
        }
    }
    @ViewBuilder private var recentAdditions: some View {
        if !model.items.isEmpty {
            Section("最近添加") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(Array(model.items.prefix(8).enumerated()), id: \.element.id) { index, item in
                            Button { playback.select(Array(model.items.prefix(8)).map(\.track), index: index) } label: {
                                VStack(alignment: .leading, spacing: 7) {
                                    ArtworkView(track: item.track, size: 116)
                                    Text(item.track.title).font(.subheadline.weight(.medium)).lineLimit(1)
                                    Text(item.track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }.frame(width: 116, alignment: .leading)
                            }.buttonStyle(.plain).accessibilityLabel("播放 \(item.track.title)")
                        }
                    }.padding(.vertical, 8)
                }
            }
        }
    }
    private var playlists: some View {
        Section("我的歌单") {
            ForEach(library.snapshot.playlists) { item in
                HStack {
                    NavigationLink(value: LocalPage.playlist(item.id)) {
                        HStack(spacing: 12) {
                            if let track = library.resolve(item.trackIDs).first { ArtworkView(track: track) }
                            else { Image(systemName: "music.note.list").font(.title2).frame(width: 52, height: 52).background(skinAccent.opacity(0.08), in: RoundedRectangle(cornerRadius: 8)) }
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.name).font(.headline)
                                Text("\(library.resolve(item.trackIDs).count) 首").font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 4)
                    }
                    Menu { playlistActions(item) } label: { Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel("歌单操作 \(item.name)")
                }.contextMenu { playlistActions(item) }
            }
            Button("新建歌单", systemImage: "plus") { name = ""; creating = true }
        }
    }
    @ViewBuilder private func playlistActions(_ item: LocalPlaylist) -> some View {
        Button("重命名", systemImage: "pencil") { playlist = item; name = item.name; renaming = true }
        Button("删除歌单", systemImage: "trash", role: .destructive) { playlist = item; deleting = true }
    }
    private var results: some View {
        Group {
            Section("歌曲") {
                let tracks = library.catalog.search(query)
                LocalTrackRows(tracks: tracks, playback: playback, downloads: model)
                if tracks.isEmpty { Text("没有匹配的本地歌曲").foregroundStyle(.secondary) }
            }
            Section("专辑与作者") {
                ForEach(library.catalog.albums.filter { LocalCatalog.matches($0.title, query: query) }) { album in
                    NavigationLink(value: LocalPage.album(album.id)) { LocalCollectionRow(collection: album) }
                }
                ForEach(library.catalog.artists.filter { LocalCatalog.matches($0.title, query: query) }) { artist in
                    NavigationLink(value: LocalPage.artist(artist.id)) { LocalCollectionRow(collection: artist) }
                }
            }
            Section("歌单") {
                ForEach(library.snapshot.playlists.filter { LocalCatalog.matches($0.name, query: query) }) { item in
                    NavigationLink(value: LocalPage.playlist(item.id)) {
                        HStack(spacing: 12) {
                            if let track = library.resolve(item.trackIDs).first { ArtworkView(track: track) }
                            else { Image(systemName: "music.note.list").font(.title2).frame(width: 52, height: 52).background(skinAccent.opacity(0.08), in: RoundedRectangle(cornerRadius: 8)) }
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.name).font(.headline)
                                Text("\(library.resolve(item.trackIDs).count) 首").font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 4)
                    }
                }
            }
        }
    }
    private func link(_ title: String, systemImage: String, page: LocalPage, count: Int) -> some View {
        NavigationLink(value: page) { HStack { Label(LocalizedStringKey(title), systemImage: systemImage); Spacer(); Text("\(count)").foregroundStyle(.secondary) } }
    }
}

struct AudioExport: Transferable, Sendable {
    let item: SavedMedia
    let library: MediaLibrary
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .audio) { value in SentTransferredFile(try await value.library.export(value.item)) }
    }
}

private struct OfflineLibraryKey: EnvironmentKey { static let defaultValue: LocalLibraryModel? = nil }
extension EnvironmentValues {
    var offlineLibrary: LocalLibraryModel? {
        get { self[OfflineLibraryKey.self] }
        set { self[OfflineLibraryKey.self] = newValue }
    }
}

struct TrackContextMenu: View {
    let track: Track
    let playback: PlaybackModel
    let downloads: DownloadModel
    var openCreator: ((String) -> Void)?
    var addToPlatformPlaylist: (() -> Void)? = nil
    @Environment(\.offlineLibrary) private var library
    var body: some View {
        Button("加入播放队列", systemImage: "text.badge.plus") { playback.enqueue(track) }
        Button("下一首播放", systemImage: "text.line.first.and.arrowtriangle.forward") { playback.enqueue(track, next: true) }
        if let addToPlatformPlaylist {
            Button("添加到平台歌单", systemImage: "text.badge.plus", action: addToPlatformPlaylist)
        }
        if let library, library.contains(track) {
            Button(LocalizedStringKey(library.snapshot.favorites.contains(track.id) ? "取消喜欢" : "喜欢"), systemImage: "heart") { Task { await library.toggleFavorite(track) } }
            if !library.snapshot.playlists.isEmpty {
                Menu("添加到歌单", systemImage: "text.badge.plus") {
                    ForEach(library.snapshot.playlists) { playlist in Button(playlist.name) { Task { await library.add([track], to: playlist.id) } } }
                }
            }
        }
        if track.localFileID == nil {
            Button("下载音频", systemImage: "arrow.down.circle") { downloads.download(track) }.disabled(downloads.downloading[track.id] != nil)
        }
        if let id = track.artistID, let openCreator { Button("查看 UP 主投稿", systemImage: "person.crop.rectangle") { openCreator(id) } }
        if let item = downloads.items.first(where: { $0.track.id == track.id }) {
            ShareLink(item: AudioExport(item: item, library: downloads.library), preview: SharePreview(track.title)) { Label("导出音频", systemImage: "square.and.arrow.up") }
        }
        if let url = track.pageURL { ShareLink(item: url) { Label("分享链接", systemImage: "square.and.arrow.up") } }
        Button("复制标题", systemImage: "doc.on.doc") { copy(track.title) }
        Button("复制标题和作者", systemImage: "doc.on.doc") { copy(track.title + " - " + track.artist) }
    }
    private func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}
