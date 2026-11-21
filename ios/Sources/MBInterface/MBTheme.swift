import SwiftUI

/// 原生音乐页面共用的颜色与间距，内容层级不依赖固定背景或字体尺寸。
enum MBTheme {
    #if os(iOS)
    static let accent = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? .systemRed : UIColor(red: 0.82, green: 0.16, blue: 0.23, alpha: 1)
    })
    static let background = Color(uiColor: .systemBackground)
    static let grouped = Color(uiColor: .systemGroupedBackground)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    #else
    static let accent = Color(nsColor: .systemRed)
    static let background = Color(nsColor: .windowBackgroundColor)
    static let grouped = Color(nsColor: .underPageBackgroundColor)
    static let surface = Color(nsColor: .controlBackgroundColor)
    #endif
    static let artworkGradient = LinearGradient(
        colors: [Color(red: 0.35, green: 0.36, blue: 0.37), Color(red: 0.16, green: 0.17, blue: 0.19)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}

struct MusicSectionHeading: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(title)).font(.title2.bold()).foregroundStyle(.primary)
            if let subtitle { Text(LocalizedStringKey(subtitle)).font(.subheadline).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct MusicActionLabel: View {
    let title: String
    let symbol: String
    var body: some View {
        Label(LocalizedStringKey(title), systemImage: symbol)
            .font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 44)
    }
}

/// 错误和重试在同一位置呈现，保留已加载的内容。
struct MusicErrorRow: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(LocalizedStringKey(message), systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(.secondary)
            Button("重试", action: retry).buttonStyle(.bordered).frame(minHeight: 44)
        }.padding(.vertical, 8)
    }
}


struct MusicGroupedList: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.listStyle(.insetGrouped).modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
        #else
        content.listStyle(.inset).modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
        #endif
    }
}
