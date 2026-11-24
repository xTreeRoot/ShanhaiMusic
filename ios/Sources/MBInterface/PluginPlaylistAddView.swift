import MBFoundation
import SwiftUI

private struct StationPlaybackKey: EnvironmentKey { static let defaultValue: PlaybackModel? = nil }
extension EnvironmentValues {
    var stationPlayback: PlaybackModel? {
        get { self[StationPlaybackKey.self] }
        set { self[StationPlaybackKey.self] = newValue }
    }
}

struct PluginPlaylistAddView: View {
    let source: PluginSource
    let playlist: PluginEntity
    let existing: Set<String>
    let didAdd: () async -> Void
    @State private var search: SearchModel
    @State private var input = ""
    @State private var selected: Set<String> = []
    @State private var saving = false
    @State private var error: String?
    @FocusState private var editing: Bool
    @Environment(\.dismiss) private var dismiss
    init(source: PluginSource, playlist: PluginEntity, existing: Set<String>, didAdd: @escaping () async -> Void) {
        self.source = source; self.playlist = playlist; self.existing = existing; self.didAdd = didAdd
        _search = State(initialValue: SearchModel(source: source))
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("搜索音源", text: $input).textFieldStyle(.roundedBorder).padding().focused($editing)
                    .autocorrectionDisabled().onSubmit { editing = false; selected = []; Task { await search.search(input) } }
                List {
                ForEach(search.tracks) { track in
                    Button {
                        if selected.contains(track.sourceTrackID) { selected.remove(track.sourceTrackID) } else { selected.insert(track.sourceTrackID) }
                    } label: {
                        HStack { TrackRow(track: track); Image(systemName: existing.contains(track.sourceTrackID) || selected.contains(track.sourceTrackID) ? "checkmark.circle.fill" : "circle") }
                    }.buttonStyle(.plain).disabled(saving || existing.contains(track.sourceTrackID)).accessibilityLabel("选择 \(track.title)")
                }
                if search.hasMore { PaginationFooter(page: "\(search.paginationGeneration):\(search.cursor)", loading: search.isLoading, blocked: search.errorMessage != nil) { await search.loadMore() } }
                if search.isLoading { ProgressView() }
                if let error = error ?? search.errorMessage { Text(error).foregroundStyle(.red) }
                if search.errorMessage != nil { Button("重试") { Task { if search.hasMore { await search.loadMore() } else { await search.search(input) } } } }
                if search.tracks.isEmpty && !search.isLoading { ContentUnavailableView("搜索音源", systemImage: "magnifyingglass") }
                }.modifier(PaginationViewport())
            }
            .navigationTitle("添加平台歌曲")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加 \(selected.count) 首") {
                        saving = true
                        Task {
                            defer { saving = false }
                            do {
                                try await source.mutate("addToPlaylist", arguments: .object(["id": .string(playlist.sourceID), "trackIds": .array(selected.sorted().map(PluginJSON.string))]))
                                await didAdd(); dismiss()
                            } catch { self.error = error.localizedDescription }
                        }
                    }.disabled(selected.isEmpty || saving)
                }
            }
        }
    }
}
