import SwiftUI

/// 控件叠加于根视图，背景与内容仍由真正的发现页面渲染。
struct BackgroundPreviewControls: View {
    @Bindable var skin: BackgroundSkin
    let confirm: () async -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("透明度")
                Slider(value: Binding(get: { 1 - skin.previewOpacity }, set: { skin.previewOpacity = 1 - $0 }), in: 0...1)
                    .accessibilityIdentifier("background-transparency")
                    .accessibilityLabel("背景透明度")
                Text("\(Int(((1 - skin.previewOpacity) * 100).rounded()))%")
                    .monospacedDigit().frame(minWidth: 42)
            }
            if skin.failed { Text("无法更新背景图片").foregroundStyle(.red) }
            HStack {
                Button("取消") { skin.cancelPreview() }.accessibilityIdentifier("cancel-background-edit")
                Spacer()
                if skin.busy { ProgressView() }
                Button("使用背景") { Task { await confirm() } }
                    .buttonStyle(.borderedProminent).accessibilityIdentifier("apply-background-image")
            }
        }
        .disabled(skin.busy).padding(.horizontal, 20).padding(.vertical, 12)
        .background(.regularMaterial)

    }
}
