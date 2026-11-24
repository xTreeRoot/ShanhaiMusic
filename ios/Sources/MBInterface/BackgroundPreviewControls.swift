import MBFoundation
import SwiftUI

/// 在真正的发现页编辑草稿；确认前图片和全部视觉设置都不落盘。
struct BackgroundPreviewControls: View {
    @Bindable var skin: BackgroundSkin
    let confirm: () async -> Void
    @State private var dragStart = CGSize.zero
    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                if let image = skin.preview {
                    SkinBackdrop(image: image, opacity: 1, style: skin.previewStyle)
                        .environment(\.backgroundSkinCanvas, .zero)
                        .frame(width: 88, height: 58).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
                        .contentShape(Rectangle()).accessibilityIdentifier("background-position-preview")
                        .gesture(DragGesture().onChanged { value in
                            skin.previewStyle.horizontal = min(1, max(-1, dragStart.width + value.translation.width / 44))
                            skin.previewStyle.vertical = min(1, max(-1, dragStart.height + value.translation.height / 29))
                        }.onEnded { _ in dragStart = CGSize(width: skin.previewStyle.horizontal, height: skin.previewStyle.vertical) })
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("自定义皮肤").font(.headline)
                    Text("拖动左图调整位置").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            control("图片缩放", value: $skin.previewStyle.zoom, range: 1...4, id: "background-image-zoom", suffix: "×")
            HStack(spacing: 4) {
                Text("主题色").font(.subheadline).frame(width: 66, alignment: .leading)
                ForEach(BackgroundAccent.allCases) { accent in
                    Button { skin.previewStyle.accent = accent } label: {
                        Circle().fill(accent.color).frame(width: 20, height: 20).padding(3)
                            .overlay(Circle().stroke(skin.previewStyle.accent == accent ? Color.primary : .clear, lineWidth: 1.5))
                    }.buttonStyle(.plain).accessibilityLabel("主题色 \(accent.title)")
                        .accessibilityAddTraits(skin.previewStyle.accent == accent ? .isSelected : [])
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
            control("透明度", value: Binding(get: { 1 - skin.previewOpacity }, set: { skin.previewOpacity = 1 - $0 }), range: 0...1, id: "background-transparency", suffix: "%")
            control("模糊度", value: $skin.previewStyle.blur, range: 0...30, id: "background-blur", suffix: "")
            Toggle("应用于音乐播放器背景", isOn: $skin.previewStyle.appliesToPlayer)
                .font(.subheadline).accessibilityIdentifier("background-preview-player-switch")
            if skin.failed { Text("无法更新背景图片").foregroundStyle(.red) }
            HStack {
                Button("取消") { skin.cancelPreview() }.accessibilityIdentifier("cancel-background-edit")
                Spacer()
                if skin.busy { ProgressView() }
                Button("使用背景") { Task { await confirm() } }
                    .buttonStyle(.borderedProminent).accessibilityIdentifier("apply-background-image")
            }
        }.disabled(skin.busy).padding(.horizontal, 20).padding(.vertical, 12)
            .background(.regularMaterial)
            .onAppear { dragStart = CGSize(width: skin.previewStyle.horizontal, height: skin.previewStyle.vertical) }
    }
    private func control(_ title: String, value: Binding<Double>, range: ClosedRange<Double>, id: String, suffix: String) -> some View {
        HStack {
            Text(LocalizedStringKey(title)).font(.subheadline).frame(width: 66, alignment: .leading)
            Slider(value: value, in: range).accessibilityIdentifier(id).accessibilityLabel(LocalizedStringKey(title))
            Text(suffix == "%" ? "\(Int((value.wrappedValue * 100).rounded()))%" : String(format: "%.1f%@", value.wrappedValue, suffix))
                .font(.caption.monospacedDigit()).frame(width: 42, alignment: .trailing)
        }
    }
}
