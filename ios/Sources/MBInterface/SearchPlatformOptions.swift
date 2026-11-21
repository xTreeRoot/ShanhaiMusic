import MBFoundation
import SwiftUI

/// 选项固定展开在搜索框下方，保持输入框可见、当前选择清楚。
struct SearchPlatformOptions: View {
    @Environment(\.skinAccent) private var skinAccent
    let sources: [SourceTab]
    let selected: String
    let choose: (String) -> Void
    private var options: [SourceTab] {
        [SourceTab(id: "all", name: AppLocalization.text("全平台搜索"))] + sources +
        [SourceTab(id: "torrent", name: AppLocalization.text("磁力链搜索"))]
    }
    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(options) { option in
                    if option.id == "torrent" { Divider().padding(.horizontal, 12) }
                    Button { choose(option.id) } label: {
                        HStack(spacing: 10) {
                            Text(option.name).frame(maxWidth: .infinity, alignment: .leading)
                            if selected == option.id { Image(systemName: "checkmark").font(.caption.weight(.semibold)) }
                        }.font(.subheadline).foregroundStyle(selected == option.id ? skinAccent : Color.primary)
                            .padding(.horizontal, 14).frame(minHeight: 44)
                            .background(selected == option.id ? skinAccent.opacity(0.08) : .clear)
                    }.buttonStyle(.plain).accessibilityAddTraits(selected == option.id ? .isSelected : [])
                }
            }.padding(.vertical, 4)
        }.frame(width: 250, height: min(340, CGFloat(options.count * 44 + 9)))
            .background(MBTheme.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay { RoundedRectangle(cornerRadius: 12).stroke(.secondary.opacity(0.16)) }
            .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
            .accessibilityIdentifier("search-platform-options")
    }
}
