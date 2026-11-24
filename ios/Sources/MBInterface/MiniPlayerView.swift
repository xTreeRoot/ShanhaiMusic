import MBFoundation
import SwiftUI

struct MiniPlayerView: View {
    @Environment(\.skinAccent) private var skinAccent
    let playback: PlaybackModel
    var systemContainer = false
    var transitionEnabled = false
    let transitionNamespace: Namespace.ID
    let openQueue: () -> Void
    let open: () -> Void

    var body: some View {
        if systemContainer {
            controls.padding(.horizontal, 12).padding(.vertical, 8)
        } else {
            controls
                .padding(8)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.primary.opacity(0.06)))
                .overlay(alignment: .bottomLeading) {
                    GeometryReader { area in
                        Capsule().fill(skinAccent.opacity(0.5)).frame(width: area.size.width * min(1, max(0, Double(playback.positionMilliseconds) / Double(max(1, playback.durationMilliseconds)))), height: 2).offset(y: area.size.height - 2)
                    }.clipped().allowsHitTesting(false).accessibilityHidden(true)
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .frame(maxWidth: 680).padding(.horizontal, 12).padding(.vertical, 5).frame(maxWidth: .infinity)
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if let track = playback.queue.current {
                Button(action: open) {
                    HStack(spacing: 12) {
                        ArtworkView(track: track, size: 40).modifier(CoverTransitionSource(enabled: transitionEnabled, namespace: transitionNamespace))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(playback.errorMessage ?? track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                    }
                }
                .accessibilityLabel("打开播放页，\(track.title)")
                Button { playback.toggle() } label: {
                    if playback.state == .loading { ProgressView().frame(width: 44, height: 44) }
                    else {
                        Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
                    }
                }
                .accessibilityLabel(Text(playback.state == .playing ? "暂停" : "播放"))
                Button("播放队列", systemImage: "list.bullet") { openQueue() }
                    .labelStyle(.iconOnly).frame(width: 44, height: 44)
                    .accessibilityValue("\(playback.queue.tracks.count)")
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("mini-player")
    }
}
