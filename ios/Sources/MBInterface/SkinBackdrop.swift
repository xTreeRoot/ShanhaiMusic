import MBFoundation
import SwiftUI

extension BackgroundAccent {
    var color: Color {
        switch self {
        case .system: MBTheme.accent
        case .peach: Color(red: 0.88, green: 0.65, blue: 0.57)
        case .rose: Color(red: 0.77, green: 0.32, blue: 0.42)
        case .silver: Color(red: 0.43, green: 0.47, blue: 0.53)
        case .forest: Color(red: 0.20, green: 0.48, blue: 0.39)
        case .brick: Color(red: 0.69, green: 0.24, blue: 0.19)
        case .red: Color(red: 0.86, green: 0.19, blue: 0.23)
        case .orange: Color(red: 0.87, green: 0.37, blue: 0.16)
        }
    }
    var title: String {
        switch self {
        case .system: "默认"; case .peach: "浅杏"; case .rose: "蔷薇"; case .silver: "银灰"
        case .forest: "松绿"; case .brick: "砖红"; case .red: "朱红"; case .orange: "暖橙"
        }
    }
}

extension EnvironmentValues {
    @Entry var backgroundSkinImage: CGImage? = nil
    @Entry var backgroundSkinOpacity: Double? = nil
    @Entry var backgroundSkinStyle = BackgroundSkinStyle()
    @Entry var backgroundSkinCanvas = CGRect.zero
    @Entry var skinAccent = MBTheme.accent
}

/// 每个页面使用同一窗口坐标绘制图片，导航栏、内容、底栏不再各自裁一张图。
struct SkinBackdrop: View {
    let image: CGImage
    let opacity: Double
    let style: BackgroundSkinStyle
    @Environment(\.backgroundSkinCanvas) private var canvas
    var body: some View {
        GeometryReader { geometry in
            let frame = geometry.frame(in: .global)
            let area = canvas.isEmpty ? frame : canvas
            let scale = max(area.width / Double(image.width), area.height / Double(image.height)) * style.zoom
            let width = Double(image.width) * scale + style.blur * 4
            let height = Double(image.height) * scale + style.blur * 4
            Image(decorative: image, scale: 1).resizable()
                .frame(width: width, height: height)
                .blur(radius: style.blur, opaque: true)
                .position(x: area.midX - frame.minX + max(0, width - area.width) / 2 * style.horizontal,
                          y: area.midY - frame.minY + max(0, height - area.height) / 2 * style.vertical)
                .opacity(opacity)
        // 图片按窗口定位，但只能绘制在当前承载区域，避免下方内容的背景盖到搜索框等相邻控件。
        }.clipped().background(MBTheme.background).allowsHitTesting(false).accessibilityHidden(true)
    }
}

struct SkinCanvas: ViewModifier {
    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let frame = geometry.frame(in: .global)
            let insets = geometry.safeAreaInsets
            content.environment(\.backgroundSkinCanvas,
                CGRect(x: frame.minX - insets.leading, y: frame.minY - insets.top,
                       width: frame.width + insets.leading + insets.trailing,
                       height: frame.height + insets.top + insets.bottom))
        }
    }
}

struct SkinSurface: ViewModifier {
    var defaultColor: Color? = nil
    var list = false
    var enabled = true
    @Environment(\.backgroundSkinImage) private var image
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.backgroundSkinOpacity) private var opacity
    @Environment(\.backgroundSkinStyle) private var style
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(list && enabled && image != nil ? .hidden : .automatic)
            #if os(iOS)
            .toolbarBackground(enabled && image != nil ? .hidden : .automatic, for: .navigationBar, .tabBar)
            #endif
            .background {
                if enabled, let image {
                    SkinBackdrop(image: image, opacity: opacity ?? (colorScheme == .dark ? 0.35 : 0.22), style: style)
                        .ignoresSafeArea()
                } else if let defaultColor { defaultColor }
            }
    }
}

/// 分组仍保留轻微层级，避免整块不透明底色挡住皮肤。
struct SkinRow: ViewModifier {
    @Environment(\.backgroundSkinImage) private var image
    func body(content: Content) -> some View {
        content.listRowBackground(image == nil ? nil : Color.primary.opacity(0.035))
    }
}
