import SwiftUI

#if os(iOS)
import UIKit

/// 小圆点和细轨道保留 44pt 触控区，音乐与视频使用一致的拖动与无障碍行为。
struct PlaybackSeekBar: UIViewRepresentable {
    @Environment(\.skinAccent) private var skinAccent
    @Binding var value: Double
    let bounds: ClosedRange<Double>
    let onEditingChanged: (Bool) -> Void
    @Environment(\.isEnabled) private var enabled
    init(value: Binding<Double>, in bounds: ClosedRange<Double>, onEditingChanged: @escaping (Bool) -> Void) {
        _value = value; self.bounds = bounds; self.onEditingChanged = onEditingChanged
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> CompactSeekSlider {
        let view = CompactSeekSlider()
        view.maximumTrackTintColor = .secondaryLabel.withAlphaComponent(0.22)
        view.addTarget(context.coordinator, action: #selector(Coordinator.begin), for: .touchDown)
        view.addTarget(context.coordinator, action: #selector(Coordinator.change(_:)), for: .valueChanged)
        view.addTarget(context.coordinator, action: #selector(Coordinator.end), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        return view
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: CompactSeekSlider, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 200, height: 44)
    }
    func updateUIView(_ view: CompactSeekSlider, context: Context) {
        context.coordinator.parent = self
        view.setAccent(UIColor(skinAccent))
        view.minimumValue = Float(bounds.lowerBound); view.maximumValue = Float(bounds.upperBound)
        view.isEnabled = enabled
        if !view.isTracking { view.value = Float(min(bounds.upperBound, max(bounds.lowerBound, value))) }
    }
    final class Coordinator: NSObject {
        var parent: PlaybackSeekBar
        private var editing = false
        init(_ parent: PlaybackSeekBar) { self.parent = parent }
        @objc func begin() { if !editing { editing = true; parent.onEditingChanged(true) } }
        @objc func change(_ slider: UISlider) { begin(); parent.value = Double(slider.value) }
        @objc func end() { if editing { editing = false; parent.onEditingChanged(false) } }
    }
}

final class CompactSeekSlider: UISlider {
    private var accent: UIColor?
    func setAccent(_ color: UIColor) {
        guard accent != color else { return }
        accent = color; minimumTrackTintColor = color
        for (state, size) in [(UIControl.State.normal, CGFloat(9)), (.highlighted, CGFloat(12))] {
            let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { drawing in
                color.setFill(); drawing.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: size, height: size))
            }
            setThumbImage(image, for: state)
        }
    }
    override func trackRect(forBounds bounds: CGRect) -> CGRect {
        let track = super.trackRect(forBounds: bounds)
        return CGRect(x: track.minX, y: bounds.midY - 1.5, width: track.width, height: 3)
    }
    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: 44) }
    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool { update(touch); return true }
    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool { update(touch); return true }
    override func accessibilityIncrement() { adjust(0.05) }
    override func accessibilityDecrement() { adjust(-0.05) }
    private func adjust(_ fraction: Float) {
        sendActions(for: .touchDown)
        value = min(maximumValue, max(minimumValue, value + fraction * (maximumValue - minimumValue)))
        sendActions(for: .valueChanged); sendActions(for: .touchUpInside)
    }
    private func update(_ touch: UITouch) {
        let track = trackRect(forBounds: bounds)
        let fraction = min(1, max(0, (touch.location(in: self).x - track.minX) / max(1, track.width)))
        value = minimumValue + Float(fraction) * (maximumValue - minimumValue)
        sendActions(for: .valueChanged)
    }
}
#else
struct PlaybackSeekBar: View {
    @Binding var value: Double
    let bounds: ClosedRange<Double>
    let onEditingChanged: (Bool) -> Void
    init(value: Binding<Double>, in bounds: ClosedRange<Double>, onEditingChanged: @escaping (Bool) -> Void) {
        _value = value; self.bounds = bounds; self.onEditingChanged = onEditingChanged
    }
    var body: some View { Slider(value: $value, in: bounds, onEditingChanged: onEditingChanged) }
}
#endif
