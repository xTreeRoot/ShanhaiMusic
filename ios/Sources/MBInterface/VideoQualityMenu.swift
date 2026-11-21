import MBFoundation
import SwiftUI

private struct VideoQualityPreferenceKey: EnvironmentKey {
    static let defaultValue: Binding<String>? = nil
}
extension EnvironmentValues {
    var videoQualityPreference: Binding<String>? {
        get { self[VideoQualityPreferenceKey.self] }
        set { self[VideoQualityPreferenceKey.self] = newValue }
    }
}

/// 横屏、全屏与竖屏视频共用真实画质选项和持久偏好。
struct VideoQualityMenu: View {
    let video: VideoModel
    @Environment(\.videoQualityPreference) private var preference
    private var selected: String { preference?.wrappedValue ?? video.qualityID }
    var body: some View {
        if !video.qualities.isEmpty {
            Menu {
                Button { choose("auto") } label: {
                    Label("自动（最高可用）", systemImage: selected == "auto" ? "checkmark" : "circle")
                }
                ForEach(video.qualities) { option in
                    Button { choose(option.id) } label: {
                        Label(option.label, systemImage: selected == option.id ? "checkmark" : "circle")
                    }.accessibilityIdentifier("video-quality-" + option.id)
                }
                if selected != "auto", !video.qualities.contains(where: { $0.id == selected }) {
                    Text("首选 \(selected) · 当前视频不可用")
                }
                if let actual = video.actualQualityID { Text("当前画质 \(actual)") }
            } label: {
                Text(video.actualQualityID ?? "画质").font(.caption.weight(.semibold))
                    .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            }.accessibilityLabel("画质").accessibilityValue("首选 \(selected)，当前 \(video.actualQualityID ?? "未知")")
                .accessibilityIdentifier("video-quality")
        }
    }
    private func choose(_ id: String) {
        preference?.wrappedValue = id
        video.setQuality(id)
    }
}
