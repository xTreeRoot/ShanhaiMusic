import AVFoundation
import Foundation
import UniformTypeIdentifiers

enum MediaStreamingFailure: LocalizedError {
    case rangeStatus(Int), rangeHeader, rangeData, contentType, unplayable, changed, fileBudget
    var errorDescription: String? {
        switch self {
        case .rangeStatus(let status): AppLocalization.text("媒体分段请求失败（HTTP %lld）", arguments: [status])
        case .rangeHeader: AppLocalization.text("媒体服务器未返回有效的分段范围")
        case .rangeData: AppLocalization.text("媒体分段数据与请求偏移不一致")
        case .contentType: AppLocalization.text("媒体类型不受原生播放器支持")
        case .unplayable: AppLocalization.text("原生播放器无法识别此媒体")
        case .changed: "媒体地址或内容已更新，需重新加载。"
        case .fileBudget: "媒体不支持分段，且完整文件超出临时下载容量。"
        }
    }

    static func transient(_ error: any Error) -> Bool {
        if TrackProblem.isConnectionError(error) { return true }
        if case Self.rangeStatus(let status) = error { return (500...599).contains(status) }
        return false
    }
    static func allowsRefresh(_ error: any Error) -> Bool {
        if transient(error) { return true }
        switch error {
        case Self.changed, Self.rangeStatus(401), Self.rangeStatus(410), NetworkFailure.http(401), NetworkFailure.http(410): return true
        default: return false
        }
    }
    static func allowsWholeFile(_ error: any Error) -> Bool {
        switch error {
        case Self.rangeStatus(200), Self.contentType, Self.unplayable: return true
        default: return false
        }
    }
}

/// Content-Range 必须对应请求偏移，不能把整段响应误当成 seek 后的数据。
struct MediaByteRange {
    let start: Int64
    let end: Int64
    let total: Int64
    init(response: HTTPURLResponse, offset: Int64, count: Int? = nil, requestedEnd: Int64? = nil) throws {
        guard response.statusCode == 206 else { throw MediaStreamingFailure.rangeStatus(response.statusCode) }
        guard let header = response.value(forHTTPHeaderField: "Content-Range"),
              header.hasPrefix("bytes ") else { throw MediaStreamingFailure.rangeHeader }
        let parts = header.dropFirst(6).split(separator: "/")
        guard parts.count == 2, let total = Int64(parts[1]) else { throw MediaStreamingFailure.rangeHeader }
        let bounds = parts[0].split(separator: "-")
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              start == offset, start >= 0, end >= start, total > end, count.map({ Int64($0) == end - start + 1 }) ?? true,
              requestedEnd.map({ min($0, total - 1) == end }) ?? true,
              [nil, "identity"].contains(response.value(forHTTPHeaderField: "Content-Encoding")) else { throw MediaStreamingFailure.rangeData }
        self.start = start
        self.end = end
        self.total = total
    }
}

/// 同一块的并发读取共享一个请求；响应头先校验，16KiB 到达即可供给播放器。
@MainActor final class MediaSegmentReader {
    nonisolated static let block: Int64 = 256 << 10
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    typealias StreamTransport = @Sendable (URLRequest, @escaping @Sendable (HTTPURLResponse) async throws -> Void,
                                          @escaping @Sendable (Data) async throws -> Void) async throws -> Void
    @MainActor private final class Segment {
        var data = Data()
        var range: MediaByteRange?
        var finished = false
        var failure: (any Error)?
        var task: Task<Void, Never>?
    }
    private let resource: MediaResource
    private let transport: StreamTransport
    private var segments: [Int64: Segment] = [:]
    private var order: [Int64] = []
    private var notifications: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var active = 0
    private var cancelled = false
    private(set) var length: Int64?
    private var validator: String?

    init(resource: MediaResource, transport: @escaping Transport) {
        self.resource = resource
        self.transport = { request, response, chunk in
            let (data, received) = try await transport(request)
            guard let http = received as? HTTPURLResponse else { throw NetworkFailure.response }
            try await response(http)
            try await chunk(data)
        }
    }
    init(resource: MediaResource, streaming: @escaping StreamTransport = { request, response, chunk in
        try await NetworkClient.shared.consume(for: request, maximumBytes: Int(MediaSegmentReader.block), response: response, chunk: chunk)
    }) { self.resource = resource; transport = streaming }

    func read(offset: Int64, end: Int64) async throws -> Data {
        try Task.checkCancellation()
        guard !cancelled else { throw CancellationError() }
        guard offset >= 0, offset < end, length.map({ offset < $0 }) ?? true else { return Data() }
        let first = offset / Self.block * Self.block
        let segment: Segment
        if let cached = segments[first] { segment = cached }
        else {
            // 最多保留 4MiB；仅回收完整块，进行中的 Range 请求最多两个。
            while order.count >= 16 {
                if let index = order.firstIndex(where: { segments[$0]?.finished == true }) {
                    segments.removeValue(forKey: order.remove(at: index))
                } else { try await changed() }
            }
            segment = Segment(); segments[first] = segment; order.append(first)
            segment.task = Task { await fetch(segment, first: first) }
        }
        let start = Int(offset - first)
        while true {
            try Task.checkCancellation()
            if let failure = segment.failure { throw failure }
            if start < segment.data.count {
                return segment.data.subdata(in: start..<min(segment.data.count, Int(min(end - first, Self.block))))
            }
            if segment.finished { return Data() }
            try await changed()
        }
    }

    func cancel() {
        cancelled = true
        segments.values.forEach { $0.task?.cancel() }
        segments.removeAll(); order.removeAll(); notify()
    }
    private func changed() async throws {
        let id = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                if Task.isCancelled || cancelled { continuation.resume() }
                else { notifications[id] = continuation }
            }
        } onCancel: { Task { @MainActor in self.notifications.removeValue(forKey: id)?.resume() } }
        try Task.checkCancellation()
        guard !cancelled else { throw CancellationError() }
    }
    private func notify() {
        let pending = notifications.values; notifications.removeAll()
        pending.forEach { $0.resume() }
    }
    private func fetch(_ segment: Segment, first: Int64) async {
        do {
            while active >= 2 { try await changed() }
            try Task.checkCancellation()
            active += 1
            defer { active -= 1; notify() }
            let last = min(first + Self.block - 1, (length ?? Int64.max) - 1)
            for attempt in 0...2 {
                var request = URLRequest(url: resource.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
                request.allHTTPHeaderFields = resource.headers
                request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                request.setValue("bytes=\(first)-\(last)", forHTTPHeaderField: "Range")
                request.setValue(validator, forHTTPHeaderField: "If-Range")
                do {
                    try await transport(request, { response in
                        try await self.accept(response, segment: segment, first: first, last: last)
                    }, { data in try await self.append(data, segment: segment) })
                    guard let range = segment.range, segment.data.count == Int(range.end - range.start + 1) else { throw MediaStreamingFailure.rangeData }
                    segment.finished = true; notify(); return
                } catch {
                    // 已交付的前缀不可重复或混入重试响应；仅无数据时重试。
                    guard segment.data.isEmpty, attempt < 2, MediaStreamingFailure.transient(error) else { throw error }
                    segment.range = nil
                    try await Task.sleep(for: .milliseconds(180 * (attempt + 1)))
                }
            }
        } catch { segment.failure = error; segment.finished = true; notify() }
    }
    private func accept(_ response: HTTPURLResponse, segment: Segment, first: Int64, last: Int64) throws {
        try Task.checkCancellation()
        guard !cancelled else { throw CancellationError() }
        if validator != nil && [200, 416].contains(response.statusCode) { throw MediaStreamingFailure.changed }
        let range = try MediaByteRange(response: response, offset: first, requestedEnd: last)
        let tag = response.value(forHTTPHeaderField: "ETag").flatMap { $0.first == "\"" && $0.last == "\"" ? $0 : nil }
        if let length, length != range.total { throw MediaStreamingFailure.changed }
        if let validator, let tag, validator != tag { throw MediaStreamingFailure.changed }
        if length == nil { validator = tag }
        length = range.total; segment.range = range
    }
    private func append(_ data: Data, segment: Segment) throws {
        try Task.checkCancellation()
        guard !cancelled, let range = segment.range, segment.data.count + data.count <= Int(range.end - range.start + 1) else { throw MediaStreamingFailure.rangeData }
        segment.data.append(data); notify()
    }
}

/// AVFoundation 只看虚拟媒体 URL；真实 Range 请求复用宿主网络层，保留代理、请求头和取消语义。
/// delegate 固定在 main queue 调用；耗时工作使用 URLSession async，不在主线程同步取远程轨道。
@MainActor final class RemoteMediaAsset: NSObject, @preconcurrency AVAssetResourceLoaderDelegate {
    let asset: AVURLAsset
    private let resource: MediaResource
    private let reader: MediaSegmentReader
    private(set) var lastFailure: (any Error)?
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var cancelled = false

    init(resource: MediaResource, transport: MediaSegmentReader.Transport? = nil) throws {
        guard let virtual = URL(string: "mbmedia://play/\(UUID()).\(resource.fileExtension)") else { throw NetworkFailure.response }
        self.resource = resource
        if let transport { reader = MediaSegmentReader(resource: resource, transport: transport) }
        else { reader = MediaSegmentReader(resource: resource) }
        asset = AVURLAsset(url: virtual)
        super.init()
        asset.resourceLoader.setDelegate(self, queue: .main)
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard !cancelled else { return false }
        let key = ObjectIdentifier(request)
        tasks[key] = Task {
            defer { tasks[key] = nil }
            do {
                try await serve(request)
                if !request.isCancelled { request.finishLoading() }
            } catch {
                if !request.isCancelled, !Task.isCancelled, !cancelled { lastFailure = error; request.finishLoading(with: error) }
            }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(request))?.cancel()
    }

    func prime() async throws {
        guard !cancelled else { throw CancellationError() }
        var offset: Int64 = 0
        while offset < min(reader.length ?? MediaSegmentReader.block, MediaSegmentReader.block) {
            let data = try await reader.read(offset: offset, end: MediaSegmentReader.block)
            guard !data.isEmpty else { break }; offset += Int64(data.count)
        }
        try Task.checkCancellation()
        guard !cancelled else { throw CancellationError() }
    }

    func cancel() {
        cancelled = true
        reader.cancel()
        asset.cancelLoading()
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
    }

    private func serve(_ loading: AVAssetResourceLoadingRequest) async throws {
        let dataRequest = loading.dataRequest
        var offset = dataRequest?.currentOffset ?? 0
        let length = dataRequest?.requestedLength ?? 2
        let all = dataRequest?.requestsAllDataToEndOfResource == true
        guard offset >= 0, length >= 0 else { throw NetworkFailure.response }
        let requestedOffset = dataRequest?.requestedOffset ?? 0
        let (requestedEnd, overflow) = requestedOffset.addingReportingOverflow(Int64(length))
        guard all || !overflow else { throw NetworkFailure.response }
        var end = all ? Int64.max : requestedEnd
        repeat {
            try Task.checkCancellation()
            guard !loading.isCancelled, !cancelled, offset < end else { return }
            let data = try await reader.read(offset: offset, end: end)
            try Task.checkCancellation()
            guard !loading.isCancelled, !cancelled, let total = reader.length else { return }
            end = min(end, total)
            if let information = loading.contentInformationRequest {
                let type = UTType(filenameExtension: resource.fileExtension)?.identifier ?? UTType.mpeg4Movie.identifier
                if let allowed = information.allowedContentTypes, !allowed.isEmpty, !allowed.contains(type) { throw MediaStreamingFailure.contentType }
                information.contentType = type
                information.contentLength = total
                information.isByteRangeAccessSupported = true
            }
            if let dataRequest, !data.isEmpty { dataRequest.respond(with: data) }
            else { return }
            offset += Int64(data.count)
        } while offset < end
    }
}
