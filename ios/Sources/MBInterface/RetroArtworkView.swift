import MBFoundation
import SwiftUI

/// 旋转依播放位置插值，暂停和 seek 都停在相应角度；降低动态效果时静止。
struct RetroArtworkView: View {
    @Environment(\.skinAccent) private var skinAccent
    let track: Track
    let style: PlayerVisualStyle
    let positionMilliseconds: Int64
    let durationMilliseconds: Int64
    let playing: Bool
    var size: CGFloat = 280
    @State private var sampled = Date()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !playing || reduceMotion)) { context in
            let seconds = Double(positionMilliseconds) / 1_000 + (playing ? max(0, context.date.timeIntervalSince(sampled)) : 0)
            let turn = reduceMotion ? 0 : seconds.truncatingRemainder(dividingBy: 360) * (style == .vinyl ? 200 : 150)
            Group {
                if style == .vinyl { vinyl(turn: turn) }
                else { cassette(turn: turn) }
            }.frame(width: size, height: size)
        }
        .onChange(of: positionMilliseconds) { _, _ in sampled = Date() }
        .onChange(of: playing) { _, _ in sampled = Date() }
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("player-artwork-" + style.rawValue)
        .accessibilityLabel(style == .vinyl ? "黑胶播放器" : "磁带播放器")
    }
    private var progress: Double { min(1, max(0, Double(positionMilliseconds) / Double(max(1, durationMilliseconds)))) }
    private func vinyl(turn: Double) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20).fill(LinearGradient(colors: [Color(white: 0.3), Color(white: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing))
            ZStack {
                Circle().fill(Color(white: 0.06)).shadow(color: .black.opacity(0.8), radius: 8, y: 4)
                ForEach(0..<16, id: \.self) { index in
                    Circle().stroke(.white.opacity(index % 3 == 0 ? 0.12 : 0.04), lineWidth: 1).padding(CGFloat(index) * size * 0.013 + 8)
                }
                RemoteArtwork(track: track).frame(width: size * 0.29, height: size * 0.29).clipShape(Circle())
                    .rotationEffect(.degrees(turn))
                Circle().fill(.white.opacity(0.65)).frame(width: 8, height: 8)
            }.frame(width: size * 0.78, height: size * 0.78).offset(x: -size * 0.045, y: -size * 0.02)
            tonearm
                .rotationEffect(.degrees(playing ? -18 - progress * 14 : 5), anchor: .top)
                .frame(width: size * 0.1, height: size * 0.61)
                .offset(x: size * 0.35, y: -size * 0.075)
                .animation(reduceMotion ? nil : .spring(duration: 0.7), value: playing)
            VStack {
                Spacer()
                HStack(spacing: 14) {
                    Circle().fill(playing ? Color.orange : Color.gray).frame(width: 10, height: 10)
                    Text("33 ⅓").font(.system(size: 11, weight: .bold, design: .monospaced)).foregroundStyle(.white.opacity(0.7))
                    Spacer()
                    Text("山海").font(.system(size: 12, weight: .black)).foregroundStyle(.white.opacity(0.55))
                }.padding(18)
            }
        }.shadow(color: .black.opacity(0.2), radius: 20, y: 12)
    }
    private var tonearm: some View {
        GeometryReader { geometry in
            let width = geometry.size.width, height = geometry.size.height
            Path { path in
                path.move(to: CGPoint(x: width / 2, y: 5)); path.addLine(to: CGPoint(x: width / 2, y: height * 0.7))
                path.addLine(to: CGPoint(x: width * 0.1, y: height * 0.94))
            }.stroke(LinearGradient(colors: [.white, .gray, .white], startPoint: .leading, endPoint: .trailing), style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
            Circle().fill(.gray.gradient).frame(width: 24, height: 24).position(x: width / 2, y: 5)
            RoundedRectangle(cornerRadius: 2).fill(Color(white: 0.15)).frame(width: 12, height: 23).rotationEffect(.degrees(27)).position(x: width * 0.05, y: height * 0.95)
        }.shadow(color: .black.opacity(0.5), radius: 3, x: 3, y: 5)
    }
    private func cassette(turn: Double) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 17).fill(LinearGradient(colors: [Color(white: 0.27), Color(white: 0.13)], startPoint: .top, endPoint: .bottom))
                .overlay { RoundedRectangle(cornerRadius: 17).stroke(.white.opacity(0.15), lineWidth: 2) }
            VStack(spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title).font(.system(size: 15, weight: .semibold, design: .serif)).italic().lineLimit(1)
                        Text(track.artist).font(.system(size: 10)).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Text("A").font(.system(size: 24, weight: .black))
                }.foregroundStyle(Color(red: 0.12, green: 0.19, blue: 0.35))
                HStack(spacing: 6) {
                    RemoteArtwork(track: track).frame(width: 30, height: 30).clipShape(RoundedRectangle(cornerRadius: 2))
                    HStack {
                        reel(turn: turn, fill: 1 - progress)
                        Spacer(minLength: 8)
                        reel(turn: turn, fill: progress)
                    }.padding(.horizontal, 12).frame(height: 52).background(Color(white: 0.08), in: Capsule())
                }
                Rectangle().fill(skinAccent).frame(height: 8).overlay(alignment: .bottom) { Rectangle().fill(skinAccent.opacity(0.4)).frame(height: 2) }
            }.padding(16).background(Color(red: 0.89, green: 0.87, blue: 0.8), in: RoundedRectangle(cornerRadius: 8)).padding(14).padding(.bottom, 22)
            VStack {
                Spacer()
                HStack(spacing: 44) {
                    ForEach(0..<2) { _ in Circle().fill(.black).frame(width: 8, height: 8).overlay { Circle().stroke(.gray, lineWidth: 1) } }
                }.padding(.bottom, 12)
            }
            VStack { screws; Spacer(); screws }.padding(7)
        }.frame(width: size, height: size * 0.66).shadow(color: .black.opacity(0.25), radius: 18, y: 12)
    }
    private var screws: some View {
        HStack { screw; Spacer(); screw }
    }
    private var screw: some View { Image(systemName: "plus.circle.fill").font(.system(size: 8)).foregroundStyle(.gray) }
    private func reel(turn: Double, fill: Double) -> some View {
        ZStack {
            Circle().fill(Color(red: 0.24, green: 0.16, blue: 0.1)).frame(width: 24 + sqrt(fill) * 22, height: 24 + sqrt(fill) * 22)
            Circle().stroke(.black.opacity(0.5), lineWidth: 1).frame(width: 24 + sqrt(fill) * 22, height: 24 + sqrt(fill) * 22)
            Image(systemName: "gearshape.fill").font(.system(size: 24)).foregroundStyle(.gray.gradient).rotationEffect(.degrees(turn))
            Circle().fill(.black).frame(width: 7, height: 7)
        }.frame(width: 46, height: 46)
    }
}
