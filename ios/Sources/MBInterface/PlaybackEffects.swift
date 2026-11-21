import MBFoundation
import SwiftUI

struct PlaybackFog: View {
    let track: Track
    let bass: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        GeometryReader { geometry in
            RemoteArtwork(track: track)
                .frame(width: geometry.size.width, height: geometry.size.height)
                .blur(radius: 65)
                .scaleEffect(reduceMotion ? 1.15 : 1.15 + bass * 0.25)
                .offset(y: reduceMotion ? 0 : -bass * 35)
                .opacity(0.14 + bass * 0.06)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: bass)
        }.clipped().allowsHitTesting(false).accessibilityHidden(true)
    }
}

struct ProgressSmoke: View {
    @Environment(\.skinAccent) private var skinAccent
    let progress: Double
    let position: Int64
    let playing: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Canvas { context, size in
            let head = max(0, min(1, progress)) * max(0, size.width - 28) + 14
            guard playing && !reduceMotion else { return }
            let time = Double(position) / 1_000
            for index in 0..<24 {
                let age = Double(index) / 24
                let x = head - age * 70
                let y = size.height / 2 + sin(time * 4 + age * 9) * age * 15
                let radius = 1.5 + age * 3
                context.opacity = (1 - age) * 0.45
                context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: radius * 2, height: radius * 2)), with: .color(skinAccent))
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}

struct CoverTransitionSource: ViewModifier {
    let enabled: Bool
    let namespace: Namespace.ID
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        #if os(iOS)
        if #available(iOS 18, *), enabled && !reduceMotion { content.matchedTransitionSource(id: "player-cover", in: namespace) }
        else { content }
        #else
        content
        #endif
    }
}
struct CoverTransitionDestination: ViewModifier {
    let enabled: Bool
    let namespace: Namespace.ID
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        #if os(iOS)
        if #available(iOS 18, *), enabled && !reduceMotion && UIDevice.current.userInterfaceIdiom == .phone { content.navigationTransition(.zoom(sourceID: "player-cover", in: namespace)) }
        else { content }
        #else
        content
        #endif
    }
}
