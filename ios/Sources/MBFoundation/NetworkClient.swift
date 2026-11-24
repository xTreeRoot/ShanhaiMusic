import Foundation

public struct ProxyPolicy: Sendable, Equatable {
    static let githubHosts = ["github.com", "api.github.com", "codeload.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "raw.githubusercontent.com"]
    public static let defaults = ["*.youtube.com", "*.googlevideo.com", "*.ytimg.com", "*.ggpht.com", "*.googleusercontent.com", "accounts.google.com", "oauth2.googleapis.com", "youtube.googleapis.com", "api.betterlyrics.org"] + githubHosts
    public let hosts: [String]

    public init() { hosts = Self.defaults }

    public init(_ input: String) throws {
        var entries: [String] = []
        for value in input.split(whereSeparator: \.isWhitespace) {
            var host = value.lowercased()
            if host.hasSuffix(".") { host.removeLast() }
            let bare = host.hasPrefix("*.") ? String(host.dropFirst(2)) : host
            let pattern = #"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"#
            guard bare.count <= 253, bare.range(of: pattern, options: .regularExpression) != nil else {
                throw NetworkFailure.invalidProxyDomain
            }
            if !entries.contains(host) { entries.append(host) }
        }
        hosts = entries
    }

    public func usesSystemProxy(for url: URL) -> Bool {
        var host = (url.host ?? "").lowercased()
        if host.hasSuffix(".") { host.removeLast() }
        guard host != "localhost", host != "127.0.0.1", host != "::1" else { return false }
        return hosts.contains { $0.hasPrefix("*.") ? host.hasSuffix(String($0.dropFirst())) : host == $0 }
    }
}

public enum NetworkFailure: LocalizedError {
    case invalidProxyDomain, response, redirect, http(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidProxyDomain: AppLocalization.text("请输入域名或 *.域名；不能包含 URL、端口、账号或全局通配符")
        case .response: AppLocalization.text("服务器响应格式不正确")
        case .redirect: AppLocalization.text("服务器重定向无效或次数过多")
        case .http(let status): AppLocalization.text("网络请求失败（HTTP %lld）", arguments: [status])
        }
    }
}

private final class RedirectGate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// 每一跳重新按目标域名选代理；非白名单关闭 URLSession 代理，不修改系统网络设置。
public actor NetworkClient {
    public static let shared = NetworkClient()
    private var policy: ProxyPolicy
    private let direct: URLSession
    private let system: URLSession

    public init() {
        policy = ProxyPolicy()
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        system = URLSession(configuration: config, delegate: RedirectGate(), delegateQueue: nil)
        config.connectionProxyDictionary = [:]
        direct = URLSession(configuration: config, delegate: RedirectGate(), delegateQueue: nil)
    }

    public func setProxyHosts(_ input: String) throws { policy = try ProxyPolicy(input) }

    /// 验证最终响应头后持续交付小块；每跳仍执行代理选择、重定向清理和权限校验。
    func consume(for original: URLRequest, maximumBytes: Int,
                 response: @Sendable (HTTPURLResponse) async throws -> Void,
                 chunk: @Sendable (Data) async throws -> Void) async throws {
        var request = original
        for _ in 0..<10 {
            try Task.checkCancellation()
            let session = policy.usesSystemProxy(for: try Self.url(request)) ? system : direct
            let began = ContinuousClock.now
            let (stream, received) = try await session.bytes(for: request)
            guard let http = received as? HTTPURLResponse else { throw NetworkFailure.response }
            if let next = try Self.redirect(request, response: http) { request = next; continue }
            try await response(http)
            guard received.expectedContentLength <= Int64(maximumBytes) else { throw NetworkFailure.response }
            var buffer = Data(), count = 0
            buffer.reserveCapacity(16 << 10)
            for try await byte in stream {
                guard count < maximumBytes else { throw NetworkFailure.response }
                buffer.append(byte); count += 1
                if buffer.count == 16 << 10 {
                    try Task.checkCancellation()
                    try await chunk(buffer)
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            try Task.checkCancellation()
            if !buffer.isEmpty { try await chunk(buffer) }
            guard received.expectedContentLength < 0 || received.expectedContentLength == Int64(count) else { throw MediaStreamingFailure.rangeData }
            let duration = began.duration(to: .now).components
            await MediaNetworkConditions.shared.sample(bytes: count, seconds: Double(duration.seconds) + Double(duration.attoseconds) / 1e18)
            return
        }
        throw NetworkFailure.redirect
    }

    public func data(for original: URLRequest, maximumBytes: Int = 32 * 1_024 * 1_024, followRedirects: Bool = true,
                     acceptingBody: @Sendable (Int) -> Bool = { _ in true }, permitting: @Sendable (URL) -> Bool = { _ in true }) async throws -> (Data, URLResponse) {
        var request = original
        for _ in 0..<10 {
            try Task.checkCancellation()
            let destination = try Self.url(request)
            guard permitting(destination) else { throw URLError(.noPermissionsToReadFile) }
            let session = policy.usesSystemProxy(for: destination) ? system : direct
            let (stream, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw NetworkFailure.response }
            if followRedirects, let next = try Self.redirect(request, response: http) { request = next; continue }
            guard acceptingBody(http.statusCode) else { return (Data(), response) }
            guard response.expectedContentLength <= Int64(maximumBytes) else { throw NetworkFailure.response }
            var data = Data(); data.reserveCapacity(min(maximumBytes, max(0, Int(response.expectedContentLength))))
            for try await byte in stream {
                guard data.count < maximumBytes else { throw NetworkFailure.response }
                data.append(byte)
            }
            return (data, response)
        }
        throw NetworkFailure.redirect
    }

    /// 使用下载任务避免把完整音频放进内存；返回独立临时文件，由播放器负责回收。
    public func download(_ resource: MediaResource, maximumBytes: Int? = nil) async throws -> URL {
        var request = URLRequest(url: resource.url)
        request.allHTTPHeaderFields = resource.headers
        request.setValue(nil, forHTTPHeaderField: "Range")
        request.setValue(nil, forHTTPHeaderField: "If-Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let ext = ["m4a", "aac", "mp4", "mp3", "flac", "wav", "aiff", "aif", "caf", "ogg"].contains(resource.fileExtension) ? resource.fileExtension : "m4a"
        let temporaryDirectory = FileManager.default.temporaryDirectory
        if resource.url.isFileURL {
            return try await Task.detached(priority: .utility) {
                try Task.checkCancellation()
                let size = try resource.url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard size.isRegularFile == true, let bytes = size.fileSize, bytes > 0,
                      maximumBytes.map({ bytes <= $0 }) ?? true else { throw MediaStreamingFailure.fileBudget }
                let target = temporaryDirectory.appendingPathComponent("mb-media-\(UUID()).\(ext)")
                try FileManager.default.copyItem(at: resource.url, to: target)
                return target
            }.value
        }
        for _ in 0..<10 {
            try Task.checkCancellation()
            let session = policy.usesSystemProxy(for: try Self.url(request)) ? system : direct
            if let maximumBytes {
                let (stream, response) = try await session.bytes(for: request)
                guard let http = response as? HTTPURLResponse else { throw NetworkFailure.response }
                if let next = try Self.redirect(request, response: http) { request = next; continue }
                guard http.statusCode == 200 else { throw NetworkFailure.http(http.statusCode) }
                let free = (try? temporaryDirectory.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity) ?? 0
                let budget = min(maximumBytes, max(0, free - (64 << 20)))
                guard budget > 0, response.expectedContentLength <= Int64(budget) else { throw MediaStreamingFailure.fileBudget }
                let target = temporaryDirectory.appendingPathComponent("mb-media-\(UUID()).\(ext)")
                guard FileManager.default.createFile(atPath: target.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
                do {
                    let file = try FileHandle(forWritingTo: target)
                    defer { try? file.close() }
                    var buffer = Data(), written = 0
                    buffer.reserveCapacity(64 << 10)
                    for try await byte in stream {
                        guard written < budget else { throw MediaStreamingFailure.fileBudget }
                        buffer.append(byte); written += 1
                        if buffer.count == 64 << 10 {
                            try Task.checkCancellation(); try file.write(contentsOf: buffer); buffer.removeAll(keepingCapacity: true)
                        }
                    }
                    try Task.checkCancellation()
                    guard written > 0, response.expectedContentLength < 0 || response.expectedContentLength == Int64(written) else { throw MediaStreamingFailure.rangeData }
                    try file.write(contentsOf: buffer)
                    return target
                } catch { try? FileManager.default.removeItem(at: target); throw error }
            }
            let (temporary, response) = try await session.download(for: request)
            defer { try? FileManager.default.removeItem(at: temporary) }
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse else {
                try? FileManager.default.removeItem(at: temporary)
                throw NetworkFailure.response
            }
            if let next = try Self.redirect(request, response: http) {
                try? FileManager.default.removeItem(at: temporary)
                request = next
                continue
            }
            guard (200..<300).contains(http.statusCode) else {
                try? FileManager.default.removeItem(at: temporary)
                throw NetworkFailure.http(http.statusCode)
            }
            let target = temporaryDirectory.appendingPathComponent("mb-audio-\(UUID()).\(ext)")
            try FileManager.default.moveItem(at: temporary, to: target)
            return target
        }
        throw NetworkFailure.redirect
    }

    /// 保留逐跳代理与取消策略，流式写入并校验公开发布资产；失败不留下可分享文件。
    public func downloadVerifiedFile(for original: URLRequest, size: Int, sha256: String,
                                     progress: @escaping @Sendable (Double) -> Void,
                                     permitting: @Sendable (URL) -> Bool) async throws -> URL {
        var request = original
        for _ in 0..<10 {
            try Task.checkCancellation()
            let destination = try Self.url(request)
            guard permitting(destination) else { throw URLError(.noPermissionsToReadFile) }
            let session = policy.usesSystemProxy(for: destination) ? system : direct
            let (stream, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw NetworkFailure.response }
            if let next = try Self.redirect(request, response: http) { request = next; continue }
            guard http.statusCode == 200 else { throw NetworkFailure.http(http.statusCode) }
            var verifier = try VerifiedDownload(size: size, sha256: sha256)
            guard response.expectedContentLength <= Int64(size) else { throw AppUpdateFailure.archive }
            let directory = FileManager.default.temporaryDirectory
            let free = try directory.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity ?? 0
            guard free > size + (64 << 20) else { throw CocoaError(.fileWriteOutOfSpace) }
            let target = directory.appendingPathComponent("mb-update-\(UUID()).download")
            guard FileManager.default.createFile(atPath: target.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
            do {
                let file = try FileHandle(forWritingTo: target)
                defer { try? file.close() }
                var buffer = Data(); buffer.reserveCapacity(64 << 10)
                for try await byte in stream {
                    buffer.append(byte)
                    if buffer.count == 64 << 10 {
                        try Task.checkCancellation()
                        try verifier.consume(buffer); try file.write(contentsOf: buffer)
                        progress(Double(verifier.written) / Double(size))
                        buffer.removeAll(keepingCapacity: true)
                    }
                }
                try Task.checkCancellation()
                try verifier.consume(buffer); try verifier.finish()
                try file.write(contentsOf: buffer); progress(1)
                return target
            } catch {
                try? FileManager.default.removeItem(at: target)
                throw error
            }
        }
        throw NetworkFailure.redirect
    }


    static func redirect(_ request: URLRequest, response: HTTPURLResponse) throws -> URLRequest? {
        guard [301, 302, 303, 307, 308].contains(response.statusCode) else { return nil }
        guard let location = response.value(forHTTPHeaderField: "Location"),
              let url = URL(string: location, relativeTo: response.url)?.absoluteURL,
              url.scheme == "https" || (request.url?.scheme == "http" && url.scheme == "http") else { throw NetworkFailure.redirect }
        var next = URLRequest(url: url)
        let sameOrigin = request.url?.host == url.host && request.url?.scheme == url.scheme && request.url?.port == url.port
        if sameOrigin {
            next.allHTTPHeaderFields = request.allHTTPHeaderFields
        }
        if request.httpMethod == nil || request.httpMethod == "GET" {
            for header in ["Range", "If-Range", "Accept-Encoding"] { next.setValue(request.value(forHTTPHeaderField: header), forHTTPHeaderField: header) }
        }
        if [307, 308].contains(response.statusCode) {
            guard sameOrigin || request.httpBody == nil else { throw NetworkFailure.redirect }
            next.httpMethod = request.httpMethod
            next.httpBody = request.httpBody
        } else if response.statusCode != 303 && request.httpMethod != "POST" {
            next.httpMethod = request.httpMethod
        }
        if next.httpBody == nil { next.setValue(nil, forHTTPHeaderField: "Content-Type") }
        return next
    }

    private static func url(_ request: URLRequest) throws -> URL {
        guard let url = request.url else { throw NetworkFailure.response }
        return url
    }
}
