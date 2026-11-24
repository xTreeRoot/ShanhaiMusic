import MBFoundation
import SwiftUI

#if os(iOS)
import AVFAudio
import MediaPlayer
import UIKit

/// 横屏画面左侧调系统媒体音量，右侧调系统屏幕亮度；控件覆盖在此手势之上。
struct VideoAdjustmentControls: ViewModifier {
    var enabled = true
    @State private var system = VideoSystemAdjustments()
    @State private var drag: VideoAdjustmentDrag?
    @State private var origin: CGFloat = 0
    @State private var kind = VideoAdjustmentKind.volume
    @State private var value = 0.0
    @State private var restricted = false
    @State private var visible = false
    @State private var revision = 0
    @GestureState private var touching = false

    func body(content: Content) -> some View {
        if enabled {
            content.background { VolumeBridge(system: system).frame(width: 1, height: 1).opacity(0.01).allowsHitTesting(false).accessibilityHidden(true) }
                .overlay {
                    GeometryReader { geometry in
                        Color.clear.contentShape(Rectangle())
                            .simultaneousGesture(DragGesture(minimumDistance: 14)
                                .updating($touching) { _, active, _ in active = true }
                                .onChanged { gesture in
                                    guard drag != nil || abs(gesture.translation.height) > abs(gesture.translation.width) * 1.3 else { return }
                                    if drag == nil {
                                        kind = gesture.startLocation.x < geometry.size.width / 2 ? .volume : .brightness
                                        value = system.read(kind)
                                        restricted = !system.available(kind)
                                        drag = VideoAdjustmentDrag(kind: kind, initial: value, height: geometry.size.height)
                                        origin = gesture.translation.height
                                    }
                                    if let drag, !restricted {
                                        if let actual = system.write(kind, value: drag.value(translationY: gesture.translation.height - origin)) { value = actual }
                                        else { restricted = true }
                                    }
                                    visible = true
                                }
                                .onEnded { _ in drag = nil; revision += 1 })
                    }
                    .overlay { if visible { feedback.transition(.opacity).allowsHitTesting(false) } }
                }
                .onChange(of: touching) { _, active in if !active { drag = nil; revision += 1 } }
                .task(id: "\(touching):\(revision)") {
                    guard !touching else { return }
                    do { try await Task.sleep(for: .milliseconds(1500)); try Task.checkCancellation(); withAnimation(.easeOut(duration: 0.2)) { visible = false } }
                    catch { /* 新手势或页面离开时取消隐藏。 */ }
                }
        } else { content }
    }
    private var label: String { AppLocalization.text(kind == .volume ? "系统音量" : "系统亮度") }
    private var feedback: some View {
        VStack(spacing: 10) {
            Image(systemName: kind == .brightness ? "sun.max.fill" : value == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 28, weight: .medium))
            Text(restricted ? AppLocalization.text("当前设备暂不支持调节") : "\(label) \(Int((value * 100).rounded()))%")
                .font(.subheadline.weight(.semibold)).monospacedDigit()
            if !restricted {
                GeometryReader { geometry in
                    Capsule().fill(.white.opacity(0.25))
                    Capsule().fill(.white).frame(width: geometry.size.width * value)
                }.frame(width: 136, height: 4)
            }
        }.foregroundStyle(.white).padding(.horizontal, 22).padding(.vertical, 16)
            .background(.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 18))
            .accessibilityElement(children: .ignore).accessibilityLabel(label)
            .accessibilityValue(restricted ? AppLocalization.text("当前设备暂不支持调节") : "\(Int((value * 100).rounded()))%")
            .accessibilityIdentifier("video-adjustment-feedback")
    }
}

/// 只使用 UIKit 的屏幕亮度和 MPVolumeView 系统媒体滑块，不修改播放器增益。
@MainActor private final class VideoSystemAdjustments {
    weak var volumeView: MPVolumeView?
    private var slider: UISlider? { volumeView?.subviews.compactMap { $0 as? UISlider }.first }
    private var screen: UIScreen? { volumeView?.window?.windowScene?.screen }
    func available(_ kind: VideoAdjustmentKind) -> Bool {
        kind == .brightness ? screen != nil : slider != nil
    }
    func read(_ kind: VideoAdjustmentKind) -> Double {
        kind == .brightness ? Double(screen?.brightness ?? 0.5) : Double(AVAudioSession.sharedInstance().outputVolume)
    }
    func write(_ kind: VideoAdjustmentKind, value: Double) -> Double? {
        switch kind {
        case .brightness:
            guard let screen else { return nil }
            screen.brightness = CGFloat(value)
            return Double(screen.brightness)
        case .volume:
            guard let slider else { return nil }
            slider.setValue(Float(value), animated: false)
            slider.sendActions(for: [.valueChanged, .touchUpInside])
            return Double(slider.value)
        }
    }
}
private struct VolumeBridge: UIViewRepresentable {
    let system: VideoSystemAdjustments
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: CGRect(x: 0, y: 0, width: 140, height: 30))
        system.volumeView = view
        return view
    }
    func updateUIView(_ view: MPVolumeView, context: Context) { system.volumeView = view }
}
#else
struct VideoAdjustmentControls: ViewModifier {
    var enabled = true
    func body(content: Content) -> some View { content }
}
#endif
