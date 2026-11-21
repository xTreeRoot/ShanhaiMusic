import MBFoundation
import SwiftUI

struct ArtworkView: View {
    let track: Track
    var size: CGFloat = 52

    var body: some View {
        RemoteArtwork(track: track)
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size > 100 ? 24 : 10))
        .accessibilityHidden(true)
    }
}

struct VideoArtwork: View {
    let track: Track

    var body: some View {
        RemoteArtwork(track: track).aspectRatio(16 / 9, contentMode: .fit)
            .overlay(alignment: .bottomTrailing) {
                if track.durationMilliseconds > 0 {
                    Text(TimeLabel.string(milliseconds: track.durationMilliseconds)).font(.caption2.monospacedDigit())
                        .foregroundStyle(.white).padding(4).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 4)).padding(5)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .accessibilityHidden(true)
    }
}

struct VideoTrackGrid: View {
    let tracks: [Track]
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var includeArtistInLabel = true

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 142), spacing: 12, alignment: .top)], spacing: 16) {
            ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                Button { select(tracks, index) } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        VideoArtwork(track: track)
                        Text(track.title).font(.subheadline.weight(.medium)).lineLimit(2)
                        Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.plain).contextMenu { menu(track) }
                    .accessibilityLabel(includeArtistInLabel ? "播放 \(track.title)，\(track.artist)" : "播放 \(track.title)")
            }
        }
    }
}

struct VideoTrackRow: View {
    @Environment(\.skinAccent) private var skinAccent
    let track: Track
    var selected = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VideoArtwork(track: track).frame(width: 122)
            VStack(alignment: .leading, spacing: 5) {
                Text(track.title).font(.subheadline.weight(.medium)).lineLimit(2)
                    .foregroundStyle(selected ? skinAccent : .primary)
                Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.contentShape(Rectangle())
    }
}

/// 封面和 API/音频共用代理策略，图片上下文由音源随曲目提供。
struct RemoteArtwork: View {
    let track: Track
    @State private var image: Image?
    private struct Request: Hashable {
        let sourceID: String
        let url: URL?
        let headers: [String: String]
    }
    var body: some View {
        ZStack {
            MBTheme.artworkGradient
        }
        .overlay {
            GeometryReader { geometry in
                if let image {
                    image.resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped()
                        .accessibilityIdentifier("artwork-loaded-" + track.id)
                }
                else if track.sourceID == "demo" || track.localMetadata?.folderID == "preview" { PreviewCover(track: track) }
                else { Image(systemName: "music.note").foregroundStyle(.white.opacity(0.75)).frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
        }
        .task(id: Request(sourceID: track.sourceID, url: track.artworkURL, headers: track.artworkHeaders)) {
            image = nil
            guard let url = track.artworkURL else { return }
            if url.isFileURL {
                let result = await Task.detached(priority: .utility) { try? Data(contentsOf: url) }.value
                guard !Task.isCancelled else { return }
                image = result.flatMap(decodedImage); return
            }
            do {
                let value = try await ArtworkCache.shared.data(url: url, sourceID: track.sourceID, headers: track.artworkHeaders)
                guard !Task.isCancelled else { return }
                image = decodedImage(value)
            } catch { /* 无封面时保留渐变占位。 */ }
        }
    }

    // 每次资源加载只解码一次，播放时钟更新不反复解码同一张封面。
    private func decodedImage(_ data: Data) -> Image? {
        #if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        return Image(uiImage: image)
        #else
        guard let image = NSImage(data: data) else { return nil }
        return Image(nsImage: image)
        #endif
    }
}

struct TrackProblemAccessibility: ViewModifier {
    @Environment(\.stationPlayback) private var playback
    let track: Track
    var enabled = true

    func body(content: Content) -> some View {
        let problem = enabled ? (playback?.trackProblem(for: track) ?? track.declaredProblem) : nil
        content.accessibilityValue(problem?.label ?? "")
    }
}

struct TrackRow: View {
    @Environment(\.skinAccent) private var skinAccent
    @Environment(\.stationPlayback) private var playback
    let track: Track
    var selected = false

    var body: some View {
        let problem = playback?.trackProblem(for: track) ?? track.declaredProblem
        HStack(spacing: 14) {
            ArtworkView(track: track)
            VStack(alignment: .leading, spacing: 5) {
                Text(track.title).font(.body.weight(.medium)).lineLimit(2)
                    .foregroundStyle(selected ? skinAccent : .primary)
                Text(track.artist.isEmpty ? "未知作者" : track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let problem {
                    Text(problem.label).font(.caption.weight(.medium)).foregroundStyle(skinAccent)
                }
            }
            Spacer(minLength: 8)
            Text(TimeLabel.string(milliseconds: track.durationMilliseconds))
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}


/// 原创演示封面，明确标记 DEMO；不替代真实音源的图片加载。
private struct PreviewCover: View {
    let track: Track
    private var warm: Bool { track.sourceTrackID == "night" }
    private var rain: Bool { track.sourceTrackID == "rain" }
    var body: some View {
        GeometryReader { area in
            let side = area.size.width
            ZStack(alignment: .topLeading) {
                Color(red: warm ? 0.78 : 0.17, green: warm ? 0.39 : rain ? 0.29 : 0.44, blue: warm ? 0.26 : 0.49)
                Circle().fill(Color(red: 0.96, green: 0.81, blue: 0.51)).frame(width: side * 0.42, height: side * 0.42).offset(x: side * 0.48, y: side * 0.12)
                Canvas { context, size in
                    for layer in 0..<5 {
                        var path = Path()
                        let y = size.height * (0.5 + Double(layer) * 0.1)
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addCurve(to: CGPoint(x: size.width, y: y + 24), control1: CGPoint(x: size.width * 0.3, y: y - size.height * 0.2), control2: CGPoint(x: size.width * 0.6, y: y + size.height * 0.2))
                        path.addLine(to: CGPoint(x: size.width, y: size.height)); path.addLine(to: CGPoint(x: 0, y: size.height)); path.closeSubpath()
                        context.fill(path, with: .color(.black.opacity(0.1 + Double(layer) * 0.035)))
                    }
                }
                VStack(alignment: .leading) {
                    Text("山海 / DEMO").font(.system(size: max(5, side * 0.045), weight: .medium, design: .monospaced)).tracking(side * 0.005)
                    Spacer()
                    Text(track.title).font(.system(size: max(7, side * 0.09), weight: .bold)).lineLimit(2)
                }.foregroundStyle(.white).padding(side * 0.09)
            }.clipped()
        }.accessibilityHidden(true)
    }
}
