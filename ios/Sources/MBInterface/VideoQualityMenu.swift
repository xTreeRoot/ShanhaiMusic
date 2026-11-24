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
            Menu { VideoQualityOptions(video: video) } label: {
                Text(video.actualQualityID ?? "画质").font(.caption.weight(.semibold))
                    .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            }.accessibilityLabel("画质").accessibilityValue("首选 \(selected)，当前 \(video.actualQualityID ?? "未知")")
                .accessibilityIdentifier("video-quality")
        }
    }
}

/// 菜单和竖屏底部面板共享选项，保存偏好后对后续视频继续生效。
struct VideoQualityOptions: View {
    let video: VideoModel
    @Environment(\.videoQualityPreference) private var preference
    private var selected: String { preference?.wrappedValue ?? video.qualityID }
    var body: some View {
        Group {
            option("自动（最高可用）", id: "auto")
            ForEach(video.qualities) { item in option(item.label, id: item.id) }
            if selected != "auto", !video.qualities.contains(where: { $0.id == selected }) {
                Text("首选 \(selected) · 当前视频不可用").font(.caption).foregroundStyle(.secondary).padding()
            }
            if let actual = video.actualQualityID { Text("当前画质 \(actual)").font(.caption).foregroundStyle(.secondary).padding() }
        }
    }
    private func option(_ title: String, id: String) -> some View {
        Button { preference?.wrappedValue = id; video.setQuality(id) } label: {
            HStack { Text(AppLocalization.text(title)); Spacer(); if selected == id { Image(systemName: "checkmark") } }
                .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
        }.accessibilityIdentifier("video-quality-" + id).accessibilityAddTraits(selected == id ? .isSelected : [])
    }
}
