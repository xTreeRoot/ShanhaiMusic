import MBFoundation
import SwiftUI

/// 播放器和曲目列表共用同一平台歌单选择器，显示真实写入结果。
struct PlatformPlaylistPicker: View {
    let track: Track
    let source: PluginSource?
    @State private var model: PlatformPlaylistModel?
    @Environment(\.dismiss) private var dismiss
    init(track: Track, source: PluginSource?) {
        self.track = track; self.source = source
        _model = State(initialValue: source.map { PlatformPlaylistModel(source: $0, track: track) })
    }
    var body: some View {
        NavigationStack {
            List {
                Text(track.title).font(.headline)
                if let model {
                    ForEach(model.playlists) { playlist in
                        Button(playlist.title) { Task { if await model.add(to: playlist) { dismiss() } } }
                            .disabled(model.saving).accessibilityIdentifier("platform-playlist-" + playlist.sourceID)
                    }
                    if model.loading || model.saving { ProgressView() }
                    if let error = model.error {
                        Text(error).foregroundStyle(.red)
                        Button("重试") { Task { await model.load() } }.disabled(model.saving)
                    } else if !model.loading && model.playlists.isEmpty {
                        ContentUnavailableView(source?.manifest.playlistEmptyTitle ?? AppLocalization.text("没有可编辑的平台歌单"), systemImage: "music.note.list")
                    }
                } else { Text("此曲目没有可用的平台歌单接口") }
            }.navigationTitle(source?.manifest.playlistAddTitle ?? AppLocalization.text("添加到平台歌单"))
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }.task { await model?.load() }
    }
}
