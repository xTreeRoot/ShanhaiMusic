import MBFoundation
import SwiftUI

/// 歌曲信息优先占用剩余宽度；平台互动与本地收藏分开，未知总数不显示假值。
struct PlayerTrackMetadata: View {
    let track: Track
    let compact: Bool
    let openCreator: (() -> Void)?
    let openComments: () -> Void
    let more: () -> Void
    private let pluginID: String?
    @State private var interaction: InteractionModel?

    init(track: Track, compact: Bool, source: (any InteractionSource)?, openCreator: (() -> Void)?,
         openComments: @escaping () -> Void, more: @escaping () -> Void) {
        self.track = track; self.compact = compact; self.openCreator = openCreator
        self.openComments = openComments; self.more = more
        pluginID = (source as? PluginSource)?.manifest.id
        _interaction = State(initialValue: source.map { InteractionModel(source: $0, track: track) })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 4) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(track.title).font(compact ? .headline : .title2.bold()).lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let openCreator {
                        Button(action: openCreator) {
                            HStack(spacing: 3) {
                                Text(track.artist).lineLimit(1)
                                Image(systemName: "chevron.right").font(.caption2)
                            }.font(.subheadline).foregroundStyle(.secondary)
                                .frame(minHeight: 32, alignment: .leading).contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityLabel("查看作者 \(track.artist)")
                            .accessibilityIdentifier("player-creator")
                    } else { Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                }.frame(maxWidth: .infinity, alignment: .leading).layoutPriority(1)
                HStack(spacing: 0) {
                    if interaction?.capabilities.likesReadable == true {
                        Button { if let interaction { Task { await interaction.toggleLike() } } } label: {
                            countedIcon(interaction?.liked == true ? "heart.fill" : "heart", count: interaction?.summary.likes)
                                .foregroundStyle(interaction?.liked == true ? Color.red : Color.primary)
                        }.disabled(interaction?.capabilities.likes != true || interaction?.isChangingLike == true)
                            .accessibilityLabel(interaction?.liked == true ? "取消点赞" : "点赞")
                            .accessibilityValue(interaction?.summary.likes.map(String.init) ?? "")
                            .accessibilityIdentifier("player-platform-like")
                    }
                    Button(action: openComments) {
                        countedIcon("bubble.right", count: interaction?.summary.comments)
                    }.disabled(interaction?.capabilities.comments != true)
                        .accessibilityLabel("评论").accessibilityValue(interaction?.summary.comments.map(String.init) ?? "")
                        .accessibilityIdentifier("player-track-comments")
                    Button(action: more) {
                        Image(systemName: "ellipsis").rotationEffect(.degrees(90)).font(.title3)
                            .frame(width: 36, height: 48).contentShape(Rectangle())
                    }.accessibilityLabel("歌曲更多操作").accessibilityIdentifier("player-track-more")
                }.buttonStyle(.plain).fixedSize(horizontal: true, vertical: false)
            }
            if let message = interaction?.likeError { Text(message).font(.caption).foregroundStyle(.red).lineLimit(2) }
        }.task { await interaction?.loadSummary() }
            .onReceive(NotificationCenter.default.publisher(for: .pluginLikedStateDidChange).receive(on: RunLoop.main)) { event in
                guard let pluginID, event.object as? String == pluginID else { return }
                if let interaction { Task { await interaction.loadLike() } }
            }
    }

    private func countedIcon(_ name: String, count: Int?) -> some View {
        Image(systemName: name).font(.title3).frame(width: 48, height: 48)
            .overlay(alignment: .topTrailing) {
                if let count {
                    Text(count.formatted(.number.notation(.compactName))).font(.system(size: 9, weight: .semibold))
                        .padding(.horizontal, 1).offset(y: -1).accessibilityHidden(true)
                }
            }.contentShape(Rectangle())
    }
}

/// 歌曲底部面板共用全局音质偏好；选择后由现有播放链路用于后续歌曲。
struct PlayerTrackActionsSheet: View {
    @Environment(\.skinAccent) private var skinAccent
    let track: Track
    let downloads: DownloadModel?
    let preferences: PreferencesModel?
    var pluginSource: PluginSource? = nil
    let openCreator: (() -> Void)?
    @Environment(\.offlineLibrary) private var library
    @Environment(\.dismiss) private var dismiss
    @State private var choosingQuality = false
    @State private var choosingPlatformPlaylist = false
    private var collected: Bool {
        guard let library else { return false }
        return library.contains(track) ? library.snapshot.favorites.contains(track.id) : library.resources.contains { $0.id == track.id }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack(spacing: 12) {
                    ArtworkView(track: track, size: 52)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title).font(.headline).lineLimit(2)
                        Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Button("关闭", systemImage: "xmark") { dismiss() }.labelStyle(.iconOnly).frame(width: 40, height: 44)
                }
                Divider()
                HStack(spacing: 24) {
                    Button {
                        Task {
                            guard let library else { return }
                            if library.contains(track) { await library.toggleFavorite(track) }
                            else if collected { await library.removeResource(track.id) }
                            else { await library.saveResource(track) }
                        }
                    } label: { tile(collected ? "已收藏" : "收藏", icon: collected ? "bookmark.fill" : "bookmark") }
                        .disabled(library == nil).accessibilityIdentifier("player-collect")
                    Button { downloads?.download(track) } label: { tile(downloaded ? "已下载" : downloading ? "下载中" : "下载", icon: "arrow.down.circle") }
                        .disabled(downloads == nil || downloaded || downloading || track.localFileID != nil)
                        .accessibilityIdentifier("player-download")
                }.frame(maxWidth: .infinity).buttonStyle(.plain)
                Divider()
                if track.localFileID == nil {
                    Button { choosingPlatformPlaylist = true } label: { row("添加到平台歌单", icon: "text.badge.plus") }
                        .buttonStyle(.plain).accessibilityIdentifier("player-add-platform-playlist")
                }
                if let openCreator {
                    Button { dismiss(); openCreator() } label: {
                        row("作者：\(track.artist)", icon: "person.crop.circle")
                    }.buttonStyle(.plain)
                }
                if let preferences {
                    Button { choosingQuality.toggle() } label: {
                        row("音质", icon: "waveform", value: preferences.values.quality.title)
                    }.buttonStyle(.plain).accessibilityIdentifier("player-quality")
                    if choosingQuality {
                        HStack(spacing: 8) {
                            ForEach(AudioQuality.allCases) { quality in
                                Button {
                                    preferences.values.quality = quality
                                } label: {
                                    Text(quality.title).font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).padding(.vertical, 12)
                                        .background(preferences.values.quality == quality ? skinAccent.opacity(0.12) : Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                                }.buttonStyle(.plain).accessibilityIdentifier("player-quality-" + quality.rawValue)
                                    .accessibilityAddTraits(preferences.values.quality == quality ? .isSelected : [])
                            }
                        }
                    }
                }
            }.padding(24)
        }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .accessibilityIdentifier("player-track-actions")
            .sheet(isPresented: $choosingPlatformPlaylist) { PlatformPlaylistPicker(track: track, source: pluginSource) }
    }
    private var downloading: Bool { downloads?.downloading[track.id] != nil }
    private var downloaded: Bool { downloads?.items.contains { $0.track.id == track.id } == true }
    private func tile(_ title: String, icon: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.title2).frame(width: 60, height: 56)
                .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
            Text(title).font(.subheadline)
        }.frame(minWidth: 90, minHeight: 90)
    }
    private func row(_ title: String, icon: String, value: String? = nil) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.title3).frame(width: 26)
            Text(title).lineLimit(1)
            Spacer(minLength: 4)
            if let value { Text(value).foregroundStyle(.secondary) }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
        }.frame(minHeight: 44).contentShape(Rectangle())
    }
}
