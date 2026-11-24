import MBFoundation
import SwiftUI

private struct LyricVisualStyleKey: EnvironmentKey { static let defaultValue = LyricVisualStyle.flow }
private struct LyricSparksKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var lyricVisualStyle: LyricVisualStyle {
        get { self[LyricVisualStyleKey.self] }
        set { self[LyricVisualStyleKey.self] = newValue }
    }
    var lyricSparks: Bool {
        get { self[LyricSparksKey.self] }
        set { self[LyricSparksKey.self] = newValue }
    }
}

struct CaptionView: View {
    @Environment(\.skinAccent) private var skinAccent
    let timeline: CaptionTimeline
    let positionMilliseconds: Int64
    let message: String?
    var viewportHeight: CGFloat = 260
    var compact = false
    @ScaledMetric(relativeTo: .title2) private var wordSize: CGFloat = 25
    var seek: ((Int64) -> Void)? = nil
    @State private var selectedIndex: Int?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.lyricVisualStyle) private var style
    @Environment(\.lyricSparks) private var sparks

    var body: some View {
        if timeline.lines.isEmpty {
            if compact {
                Text(LocalizedStringKey(message ?? "暂无歌词")).font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ContentUnavailableView("暂无字幕", systemImage: "quote.bubble", description: Text(LocalizedStringKey(message ?? "此视频没有提供可用字幕")))
            }
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: compact ? 0 : 24) {
                        ForEach(Array(timeline.lines.enumerated()), id: \.offset) { index, line in
                            HStack(spacing: 8) {
                                Button { selectedIndex = index } label: {
                                VStack(alignment: .leading, spacing: compact ? 4 : 10) {
                                    if compact {
                                        Text(line.text).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                                            .foregroundStyle(index == activeIndex ? skinAccent : .secondary)
                                    } else { LyricWordLayout(style: style) {
                                        ForEach(Array(line.timedWords.enumerated()), id: \.offset) { wordIndex, word in
                                            wordView(word, index: wordIndex, active: index == activeIndex)
                                        }
                                    } }
                                    if !compact, let translation = line.translation, !translation.isEmpty {
                                        Text(translation).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .scaleEffect(index == activeIndex ? 1 : 0.94, anchor: .leading)
                                .opacity(index == activeIndex ? 1 : 0.6)
                                .animation(reduceMotion ? nil : .spring(duration: 0.45), value: activeIndex)
                                }.buttonStyle(.plain).disabled(seek == nil)
                                    .accessibilityLabel("选择歌词 \(line.text)")
                                    .accessibilityAddTraits(index == selectedIndex || index == activeIndex ? .isSelected : [])
                                Group {
                                    if selectedIndex == index, let seek {
                                        Button {
                                            selectedIndex = nil
                                            seek(line.startMilliseconds)
                                        } label: {
                                            VStack(spacing: 5) {
                                                Image(systemName: "play.fill").font(.title3)
                                                Text(Self.time(line.startMilliseconds)).font(.caption2.monospacedDigit())
                                            }.frame(width: compact ? 48 : 56).frame(minHeight: 48)
                                        }.buttonStyle(.plain)
                                            .accessibilityLabel("从这一句播放 \(line.text)")
                                            .accessibilityIdentifier("lyric-confirm-\(index)")
                                    } else { Color.clear.frame(width: compact ? 48 : 56).accessibilityHidden(true) }
                                }.frame(width: compact ? 48 : 56)
                            }.frame(height: compact ? 48 : nil).padding(.horizontal, 8).padding(.vertical, compact ? 0 : 6)
                                .background(selectedIndex == index ? Color.primary.opacity(0.07) : .clear, in: RoundedRectangle(cornerRadius: 14))
                                .id(index)

                        }
                    }.padding(.top, compact ? 0 : 32).padding(.bottom, compact ? 48 : 32).padding(.horizontal, 8).frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: viewportHeight)
                .clipped()
                .accessibilityIdentifier("lyric-style-" + style.rawValue)
                .onAppear { if let activeIndex { proxy.scrollTo(activeIndex, anchor: compact ? .top : .center) } }
                .onChange(of: activeIndex) { _, index in
                    guard selectedIndex == nil, let index else { return }
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { proxy.scrollTo(index, anchor: compact ? .top : .center) }
                }
                .onChange(of: timeline.lines) { _, _ in selectedIndex = nil }
            }
        }
    }
    private static func time(_ milliseconds: Int64) -> String {
        let seconds = max(0, milliseconds / 1_000)
        return String(format: "%02lld:%02lld", seconds / 60, seconds % 60)
    }
    private var activeIndex: Int? { timeline.activeIndex(at: positionMilliseconds) }
    private func wordView(_ word: CaptionWord, index: Int, active: Bool) -> some View {
        let progress = word.progress(at: positionMilliseconds)
        return Text(word.text)
            .font(.system(size: compact ? 16 : (style == .tilt ? wordSize * 1.2 : wordSize), weight: style == .tilt && index % 2 == 0 ? .black : .semibold, design: style == .tilt ? .rounded : .default))
            .foregroundStyle(.secondary)
            .overlay(alignment: .leading) {
                GeometryReader { geometry in
                    skinAccent.frame(width: geometry.size.width * (active ? progress : 0))
                }.mask(Text(word.text).font(.system(size: compact ? 16 : (style == .tilt ? wordSize * 1.2 : wordSize), weight: style == .tilt && index % 2 == 0 ? .black : .semibold, design: style == .tilt ? .rounded : .default)))
            }
            .overlay {
                if sparks && !reduceMotion && active {
                    Canvas { context, size in
                        let age = Double(positionMilliseconds - word.startMilliseconds) / 600
                        guard age >= 0 && age < 1 else { return }
                        for particle in 0..<6 {
                            let angle = Double(particle) * .pi / 3
                            let x = size.width * progress + cos(angle) * age * 24
                            let y = size.height * 0.5 + sin(angle) * age * 22 - age * 15
                            context.opacity = 1 - age
                            context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 2.5, height: 2.5)), with: .color(skinAccent))
                        }
                    }.allowsHitTesting(false)
                }
            }
            .rotationEffect(.degrees(style == .tilt && !reduceMotion && index % 3 == 0 ? -4 : 0))
            .fixedSize()
    }
}

/// 按词自然换行；阶梯与倾诉在行内保留逐字时间和字重。
private struct LyricWordLayout: Layout {
    let style: LyricVisualStyle
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? 300, subviews: subviews).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (view, point) in zip(subviews, result.points) { view.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified) }
    }
    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        var x: CGFloat = 0, y: CGFloat = 0, height: CGFloat = 0, row = 0
        var points: [CGPoint] = []
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                row += 1; y += height + (style == .flow ? 5 : 10); height = 0
                x = style == .steps ? CGFloat(row % 3) * 12 : 0
            }
            points.append(CGPoint(x: x, y: y)); x += size.width + (style == .flow ? 1 : 3); height = max(height, size.height)
        }
        return (CGSize(width: width, height: y + height), points)
    }
}
