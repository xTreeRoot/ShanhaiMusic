import Foundation
import Network

/// 只向插件传递通用取流提示；具体格式和分辨率选择仍由插件负责。
actor MediaNetworkConditions {
    static let shared = MediaNetworkConditions()
    private let monitor = NWPathMonitor()
    private var constrained = false
    private var bandwidth: Double?
    private var lastSample: ContinuousClock.Instant?
    private var stalledUntil = ContinuousClock.now
    init() {
        monitor.pathUpdateHandler = { path in
            Task { await MediaNetworkConditions.shared.setConstrained(path.isConstrained || path.isExpensive) }
        }
        monitor.start(queue: DispatchQueue(label: "mb.media-network"))
    }
    deinit { monitor.cancel() }
    private func setConstrained(_ value: Bool) { constrained = value }
    func sample(bytes: Int, seconds: Double) {
        guard bytes >= 64 << 10, seconds > 0 else { return }
        let value = Double(bytes) * 8 / seconds
        bandwidth = bandwidth.map { min(value, $0 * 0.7 + value * 0.3) } ?? value
        lastSample = .now
    }
    func stalled() { stalledUntil = .now + .seconds(30) }
    func snapshot() -> PluginJSON {
        var fields: [String: PluginJSON] = ["constrained": .bool(constrained), "recentStall": .bool(.now < stalledUntil)]
        if let bandwidth, let lastSample, lastSample.duration(to: .now) < .seconds(90) { fields["bandwidthBps"] = .number(bandwidth) }
        return .object(fields)
    }
}
