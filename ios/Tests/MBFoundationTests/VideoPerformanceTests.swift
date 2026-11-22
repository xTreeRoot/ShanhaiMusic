import AVFoundation
import Darwin
import Foundation
@testable import MBFoundation
import Testing

/// 显式运行的原生取流基准，使用同一限速 Range 服务器，不访问账号或真实平台。
@Test @MainActor func videoPerformanceBaseline() async throws {
    guard let address = ProcessInfo.processInfo.environment["MB_VIDEO_BENCHMARK_URL"],
          let resultPath = ProcessInfo.processInfo.environment["MB_VIDEO_BENCHMARK_RESULT"] else { return }
    let engine = NativeVideoEngine()
    defer { engine.stop() }
    var firstFrames: [Double] = [], stalls = 0, stallSeconds = 0.0
    let clock = ContinuousClock()
    for index in 0..<3 {
        let start = clock.now
        let resource = MediaResource(url: URL(string: address + "?item=\(index)")!, headers: ["X-Benchmark": "range-fixture"], fileExtension: "mp4")
        try await engine.load(resource: resource, at: 0)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
        engine.player.currentItem?.add(output)
        engine.sync(positionMilliseconds: 0, playing: true)
        var frame = false
        while start.duration(to: clock.now) < .seconds(20) {
            if output.hasNewPixelBuffer(forItemTime: engine.player.currentTime()) { frame = true; break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(frame, "原生播放器必须真正产出首帧")
        firstFrames.append(seconds(start.duration(to: clock.now)) * 1000)
        if index == 0 {
            let until = clock.now + .seconds(9)
            var waiting = false, began = clock.now
            while clock.now < until {
                let current = engine.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                if current && !waiting { stalls += 1; began = clock.now }
                if !current && waiting { stallSeconds += seconds(began.duration(to: clock.now)) }
                waiting = current
                try await Task.sleep(for: .milliseconds(20))
            }
            if waiting { stallSeconds += seconds(began.duration(to: clock.now)) }
        }
        engine.stop()
    }
    var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
    let metrics: [String: Any] = ["firstFrameMilliseconds": firstFrames, "stallCount": stalls, "stallSeconds": stallSeconds,
                                  "peakResidentBytes": usage.ru_maxrss, "userCPUSeconds": Double(usage.ru_utime.tv_sec) + Double(usage.ru_utime.tv_usec) / 1e6,
                                  "systemCPUSeconds": Double(usage.ru_stime.tv_sec) + Double(usage.ru_stime.tv_usec) / 1e6]
    try JSONSerialization.data(withJSONObject: metrics, options: [.prettyPrinted, .sortedKeys]).write(to: URL(fileURLWithPath: resultPath))
}
private func seconds(_ value: Duration) -> Double { Double(value.components.seconds) + Double(value.components.attoseconds) / 1e18 }

@Test @MainActor func videoPreparedPerformance() async throws {
    guard let address = ProcessInfo.processInfo.environment["MB_VIDEO_BENCHMARK_URL"],
          let resultPath = ProcessInfo.processInfo.environment["MB_VIDEO_BENCHMARK_RESULT"] else { return }
    let engine = NativeVideoEngine()
    defer { engine.stop() }
    var firstFrames: [Double] = [], preparation: [Double] = []
    for index in 0..<3 {
        let resource = MediaResource(url: URL(string: address + "?prepared=\(index)")!, headers: [:], fileExtension: "mp4")
        let loader = try RemoteMediaAsset(resource: resource)
        let began = ContinuousClock.now
        try await loader.prime()
        preparation.append(seconds(began.duration(to: .now)) * 1000)
        let start = ContinuousClock.now
        try await engine.load(prepared: PreparedVideo(resource: resource, loader: loader), at: 0)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
        engine.player.currentItem?.add(output)
        engine.sync(positionMilliseconds: 0, playing: true)
        var frame = false
        while start.duration(to: .now) < .seconds(20) {
            if output.hasNewPixelBuffer(forItemTime: engine.player.currentTime()) { frame = true; break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(frame)
        firstFrames.append(seconds(start.duration(to: .now)) * 1000)
        engine.stop()
    }
    let metrics = ["firstFrameMilliseconds": firstFrames, "backgroundPreparationMilliseconds": preparation]
    try JSONSerialization.data(withJSONObject: metrics, options: [.prettyPrinted, .sortedKeys]).write(to: URL(fileURLWithPath: resultPath))
}
