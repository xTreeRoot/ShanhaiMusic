import MBFoundation
import SwiftUI

/// 裁剪只生成候选图，交给根页面在真实发现页预览。
private enum BackgroundCropRatio { case portrait, square, landscape, original }

struct BackgroundImageEditor: View {
    let image: CGImage
    let preview: (CGImage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var ratio = BackgroundCropRatio.portrait
    @State private var zoom = 1.0
    @State private var pan = CGSize.zero
    @State private var dragStart = CGSize.zero
    @State private var failed = false

    private var aspect: Double {
        switch ratio { case .portrait: 9.0 / 16; case .square: 1; case .landscape: 16.0 / 9; case .original: Double(image.width) / Double(image.height) }
    }
    var body: some View {
        NavigationStack {
            GeometryReader { area in
                VStack(spacing: 20) {
                    Picker("裁剪比例", selection: $ratio) {
                        Text("竖屏").tag(BackgroundCropRatio.portrait)
                        Text("方形").tag(BackgroundCropRatio.square)
                        Text("横屏").tag(BackgroundCropRatio.landscape)
                        Text("原图").tag(BackgroundCropRatio.original)
                    }.pickerStyle(.segmented).onChange(of: ratio) { _, _ in zoom = 1; pan = .zero; dragStart = .zero }
                    cropCanvas(available: CGSize(width: area.size.width, height: max(120, area.size.height - 145)))
                    HStack {
                        Image(systemName: "minus.magnifyingglass")
                        Slider(value: $zoom, in: 1...4).accessibilityLabel("背景缩放")
                        Image(systemName: "plus.magnifyingglass")
                    }
                    Text("拖动图片调整位置").font(.caption).foregroundStyle(.secondary)
                    if failed { Text("无法更新背景图片").foregroundStyle(.red) }
                }.padding(20)
            }
            .navigationTitle("裁剪背景")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.accessibilityIdentifier("cancel-background-edit")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("预览") { preparePreview() }.accessibilityIdentifier("preview-background-image")
                }
            }
        }
    }

    private func cropCanvas(available: CGSize) -> some View {
        let width = min(available.width, available.height * aspect)
        let height = width / aspect
        let scale = max(width / Double(image.width), height / Double(image.height)) * zoom
        let maxX = max(0, (Double(image.width) * scale - width) / 2)
        let maxY = max(0, (Double(image.height) * scale - height) / 2)
        return ZStack {
            Color.black
            Image(decorative: image, scale: 1).resizable()
                .frame(width: Double(image.width) * scale, height: Double(image.height) * scale)
                .offset(x: pan.width * maxX, y: pan.height * maxY)
        }.frame(width: width, height: height).clipped()
            .overlay(Rectangle().stroke(.white.opacity(0.8), lineWidth: 1))
            .contentShape(Rectangle()).accessibilityIdentifier("background-crop-canvas")
            .gesture(DragGesture().onChanged { value in
                pan = CGSize(width: maxX > 0 ? min(1, max(-1, dragStart.width + value.translation.width / maxX)) : 0,
                             height: maxY > 0 ? min(1, max(-1, dragStart.height + value.translation.height / maxY)) : 0)
            }.onEnded { _ in dragStart = pan })
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private func preparePreview() {
        guard let rect = BackgroundImageCrop.rectangle(width: image.width, height: image.height, aspect: aspect,
                                                       zoom: zoom, horizontal: pan.width, vertical: pan.height),
              let cropped = image.cropping(to: rect) else { failed = true; return }
        failed = false
        preview(cropped)
    }
}
