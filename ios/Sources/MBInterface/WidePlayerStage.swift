import MBFoundation
import SwiftUI

enum WidePlayerSection: String, CaseIterable, Identifiable {
    case lyrics, rhythm, queue, selection, comments
    var id: String { rawValue }
    var title: String {
        switch self { case .lyrics: "歌词"; case .rhythm: "律动"; case .queue: "待播清单"; case .selection: "选歌"; case .comments: "评论" }
    }
}

/// 横屏与桌面共享的舞台分区；队列选曲仍由同一个 PlaybackModel 控制。
struct WidePlayerStage: View {
    let playback: PlaybackModel
    let demo: Bool
    let section: WidePlayerSection
    let interactionSource: (any InteractionSource)?
    let accountRevision: Int
    let position: Int64
    @State private var editingQueue = false

    var body: some View {
        GeometryReader { area in
            switch section {
            case .lyrics:
                CaptionView(timeline: timeline, positionMilliseconds: position, message: playback.captionMessage,
                            viewportHeight: area.size.height, seek: { playback.seek(to: $0) }).id(playback.queue.current?.id)
            case .rhythm:
                RhythmLyrics(timeline: timeline, position: position, playing: playback.state == .playing,
                             message: playback.captionMessage, seek: { playback.seek(to: $0) })
            case .queue:
                VStack(spacing: 0) {
                    HStack {
                        Text("共 \(playback.queue.tracks.count) 首").foregroundStyle(.secondary)
                        Spacer()
                        Button(editingQueue ? "完成编辑" : "编辑队列") { editingQueue.toggle() }.frame(minHeight: 32)
                    }.font(.caption)
                    List {
                        ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                            Button { playback.jump(to: index, intent: .transport, autoplay: !demo) } label: {
                                TrackRow(track: track, selected: index == playback.queue.index)
                            }.buttonStyle(.plain).listRowBackground(Color.clear).accessibilityLabel("队列选择 \(track.title)")
                        }.onDelete { playback.removeQueue($0) }.onMove { playback.moveQueue($0, to: $1) }
                    }.listStyle(.plain).scrollContentBackground(.hidden)
                        #if os(iOS)
                        .environment(\.editMode, .constant(editingQueue ? .active : .inactive))
                        #endif
                }
            case .selection:
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 105, maximum: 160), spacing: 16)], spacing: 16) {
                        ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                            Button { playback.jump(to: index, intent: .transport, autoplay: !demo) } label: {
                                VStack(alignment: .leading, spacing: 5) {
                                    GeometryReader { cell in
                                        ArtworkView(track: track, size: cell.size.width)
                                            .overlay(alignment: .bottomTrailing) {
                                                if index == playback.queue.index { Image(systemName: "checkmark.circle.fill").font(.title2).symbolRenderingMode(.palette).foregroundStyle(.white, MBTheme.accent).padding(6) }
                                            }
                                    }.aspectRatio(1, contentMode: .fit)
                                    Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }.buttonStyle(.plain).accessibilityLabel("选歌 \(track.title)")
                        }
                    }.padding(8)
                }
            case .comments:
                if let interactionSource, let track = playback.queue.current, track.localFileID == nil {
                    PlayerComments(source: interactionSource, track: track).id("\(track.id):\(accountRevision)")
                } else { ContentUnavailableView("当前音源不支持评论", systemImage: "bubble.left.and.bubble.right") }
            }
        }.simultaneousGesture(DragGesture(minimumDistance: 28).onEnded { value in
            guard section == .lyrics || section == .rhythm,
                  value.startLocation.x >= 24, abs(value.translation.width) >= 64,
                  abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
            if value.translation.width < 0 { playback.next() } else { playback.previous() }
        })
    }
    private var timeline: CaptionTimeline { demo ? DemoCatalog.timeline : playback.timeline }
}

private struct PlayerComments: View {
    @State private var model: InteractionModel
    init(source: any InteractionSource, track: Track) {
        _model = State(initialValue: InteractionModel(source: source, track: track))
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("评论 · \(model.total)").font(.caption).foregroundStyle(.secondary)
                Spacer()
                TrackLikeButton(model: model).font(.subheadline).frame(maxWidth: 200)
            }.padding(.horizontal, 16)
            TrackCommentsList(model: model, blendsWithPlayer: true)
        }.task { await model.refresh(); await model.loadLike() }
    }
}

/// 沿用字幕的逐字时间，暂停与系统减少动态效果时停止字形摆动。
private struct RhythmLyrics: View {
    let timeline: CaptionTimeline
    let position: Int64
    let playing: Bool
    let message: String?
    let seek: (Int64) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        if timeline.lines.isEmpty {
            ContentUnavailableView("暂无字幕", systemImage: "quote.bubble", description: Text(message ?? "此视频没有提供可用字幕"))
        } else {
            let index = timeline.activeIndex(at: position) ?? timeline.lines.indices.first(where: { timeline.lines[$0].startMilliseconds > position }) ?? (timeline.lines.count - 1)
            let line = timeline.lines[index]
            VStack(spacing: 20) {
                Spacer(minLength: 4)
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || !playing)) { context in
                    let phase = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate * 2.4
                    RhythmWordLayout {
                        ForEach(Array(line.timedWords.enumerated()), id: \.offset) { offset, word in
                            Text(word.text).font(.system(size: 38, weight: .bold, design: .rounded)).foregroundStyle(.secondary)
                                .overlay(alignment: .leading) {
                                    GeometryReader { size in MBTheme.accent.frame(width: size.size.width * word.progress(at: position)) }
                                        .mask(Text(word.text).font(.system(size: 38, weight: .bold, design: .rounded)))
                                }.fixedSize()
                                .offset(y: reduceMotion ? 0 : sin(phase + Double(offset) * 0.65) * 7)
                                .rotationEffect(.degrees(reduceMotion ? 0 : sin(phase + Double(offset)) * 4))
                        }
                    }.padding(.horizontal, 12)
                }.accessibilityElement(children: .ignore).accessibilityLabel(line.text).accessibilityIdentifier("rhythm-lyrics")
                Spacer(minLength: 4)
                if index + 1 < timeline.lines.count {
                    Button { seek(timeline.lines[index + 1].startMilliseconds) } label: {
                        Text(timeline.lines[index + 1].text).font(.subheadline).foregroundStyle(.secondary).lineLimit(1).frame(minHeight: 44)
                    }.buttonStyle(.plain).accessibilityLabel("下一句 \(timeline.lines[index + 1].text)")
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct RhythmWordLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? 500, subviews: subviews).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (view, point) in zip(subviews, result.points) { view.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified) }
    }
    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        var rows: [[(Int, CGSize)]] = [[]], widths: [CGFloat] = [0]
        for (index, view) in subviews.enumerated() {
            let size = view.sizeThatFits(.unspecified)
            if widths[rows.count - 1] > 0 && widths[rows.count - 1] + size.width + 3 > width { rows.append([]); widths.append(0) }
            rows[rows.count - 1].append((index, size)); widths[rows.count - 1] += size.width + 3
        }
        var points = [CGPoint](repeating: .zero, count: subviews.count), y: CGFloat = 10
        for (row, total) in zip(rows, widths) {
            var x = max(0, (width - total) / 2)
            for (index, size) in row { points[index] = CGPoint(x: x, y: y); x += size.width + 3 }
            y += (row.map { $0.1.height }.max() ?? 0) + 14
        }
        return (CGSize(width: width, height: y), points)
    }
}
