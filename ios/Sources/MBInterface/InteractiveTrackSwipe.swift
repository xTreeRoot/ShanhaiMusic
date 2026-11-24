import MBFoundation
import SwiftUI

/// 拖动即时移动当前舞台；取消回弹，超过阈值才换资源并从另一侧进入。
struct InteractiveTrackSwipe: ViewModifier {
    let selection: Int
    var vertical = false
    var simultaneous = false
    var previousTrack: Track? = nil
    var nextTrack: Track? = nil
    let previous: () -> Void
    let next: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var offset: CGFloat = 0
    @State private var settling = false
    @GestureState private var dragging = false
    @State private var transition: Task<Void, Never>?

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let size = geometry.size
            let extent = vertical ? size.height : size.width
            let stage = content.frame(width: size.width, height: size.height)
                .offset(x: vertical ? 0 : offset, y: vertical ? offset : 0)
                .contentShape(Rectangle())
                .accessibilityValue(offset == 0 ? "" : (vertical ? (offset < 0 ? "切换下一个视频" : "切换上一个视频") : (offset < 0 ? "切换下一首" : "切换上一首")))
            ZStack {
                if offset != 0, let track = offset < 0 ? nextTrack : previousTrack {
                    adjacent(track, size: size)
                        .offset(x: vertical ? 0 : (offset < 0 ? extent : -extent) + offset,
                                y: vertical ? (offset < 0 ? extent : -extent) + offset : 0)
                        .allowsHitTesting(false)
                        .accessibilityLabel("\(offset < 0 ? "下一条预览" : "上一条预览") \(track.title)，\(track.artist)")
                }
                if simultaneous { stage.simultaneousGesture(gesture(extent: extent)) }
                else { stage.highPriorityGesture(gesture(extent: extent)) }
            }
        }.clipped()
        .onChange(of: dragging) { _, active in
            if !active && !settling { withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) { offset = 0 } }
        }
        .onChange(of: selection) { _, _ in if !settling { offset = 0 } }
        .onDisappear { transition?.cancel(); transition = nil; offset = 0; settling = false }
    }

    private func gesture(extent: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12).updating($dragging) { _, active, _ in active = true }.onChanged { value in
            guard !settling, vertical || value.startLocation.x >= 24 else { return }
            let main = vertical ? value.translation.height : value.translation.width
            let cross = vertical ? value.translation.width : value.translation.height
            guard abs(main) > abs(cross) * 1.3 else { return }
            // 手指跟随阶段不用隐式动画，保持一对一的拖动反馈。
            var transaction = Transaction(); transaction.disablesAnimations = true
            withTransaction(transaction) { offset = min(extent, max(-extent, main)) }
        }.onEnded { value in
            guard !settling else { return }
            let main = vertical ? value.translation.height : value.translation.width
            let cross = vertical ? value.translation.width : value.translation.height
            // GestureState 可能先于结束回调复位，提交依据使用手势最终位移。
            guard (vertical || value.startLocation.x >= 24), abs(main) >= max(vertical ? 72 : 64, extent * 0.18), abs(main) > abs(cross) * 1.3 else {
                withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) { offset = 0 }; return
            }
            let forward = main < 0
            settling = true
            transition = Task { @MainActor in
                if !reduceMotion {
                    withAnimation(.easeOut(duration: 0.15)) { offset = forward ? -extent : extent }
                    do { try await Task.sleep(for: .milliseconds(150)) } catch { return }
                }
                guard !Task.isCancelled else { return }
                var transaction = Transaction(); transaction.disablesAnimations = true
                // 相邻预览已滑到中心，同一帧提交资源并归零，避免再次从空白移入。
                withTransaction(transaction) {
                    if forward { next() } else { previous() }
                    offset = 0; settling = false; transition = nil
                }
            }
        }
    }

    private func adjacent(_ track: Track, size: CGSize) -> some View {
        ZStack(alignment: vertical && offset < 0 ? .topLeading : .bottomLeading) {
            if vertical {
                RemoteArtwork(track: track).frame(width: size.width, height: size.height).clipped()
                LinearGradient(colors: offset < 0 ? [.black.opacity(0.85), .clear] : [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
            } else {
                ArtworkView(track: track, size: max(60, min(size.width - 40, size.height - 84, 440)))
                    .frame(width: size.width, height: size.height, alignment: .center)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(track.title).font(.headline).lineLimit(2)
                Text(track.artist).font(.subheadline).lineLimit(1)
            }.foregroundStyle(vertical ? Color.white : Color.primary).padding(20)
        }.frame(width: size.width, height: size.height)
    }
}
