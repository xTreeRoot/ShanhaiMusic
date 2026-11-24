import Foundation
import MBJavaScript
import os

private final class PluginContext: @unchecked Sendable {
    final class HostCalls {
        let host: PluginHostState
        var storage: PluginSignInDiagnostics.StorageActivity?

        init(_ host: PluginHostState) { self.host = host }

        func call(_ operation: String, _ values: [PluginJSON]) throws -> PluginJSON {
            do {
                let value = try host.call(operation, values)
                switch operation {
                case "storage.get":
                    if value == .null { storage?.misses += 1 } else { storage?.hits += 1 }
                case "storage.set": storage?.writes += 1
                case "storage.remove": storage?.removals += 1
                default: break
                }
                return value
            } catch {
                if ["storage.get", "storage.set", "storage.remove"].contains(operation) { storage?.failures += 1 }
                throw error
            }
        }
    }
    let pointer: OpaquePointer
    let hostCalls: HostCalls
    private let lifetime: OSAllocatedUnfairLock<OpaquePointer?>
    init(_ pointer: OpaquePointer, hostCalls: HostCalls) {
        self.pointer = pointer; self.hostCalls = hostCalls; lifetime = OSAllocatedUnfairLock(uncheckedState: pointer)
    }
    func cancel() { lifetime.withLock { if let pointer = $0 { mbjs_cancel(pointer) } } }
    deinit { lifetime.withLock { $0 = nil }; mbjs_destroy(pointer) }
}

/// QuickJS 仅在本 actor 串行执行。长请求异步离开解释器，取消可直接中断同步 JS 循环。
public actor PluginRuntime {
    /// 单跳网络传输；由运行时执行 fetch 的重定向模式和逐跳权限检查。
    public typealias Transport = @Sendable (URLRequest, PluginManifest.Permissions) async throws -> (Data, HTTPURLResponse)
    public nonisolated let catalogCache: ResponseCache
    private let host: PluginHostState
    /// 两个 VM 均只在本 actor 执行，共享宿主存储；目录 IO 不再挡住播放解析。
    private final class Lane {
        var diagnostics: PluginSignInDiagnostics?
        var context: PluginContext?
        var events: [Int32: Task<Void, Never>] = [:]
        var failure: (any Error)?
        var loaded = false
        var contentDigest: String?
        var busy = false
        var waiters: [CheckedContinuation<Void, Never>] = []
        deinit { for task in events.values { task.cancel() } }
    }
    private let catalog = Lane()
    private let playback = Lane()
    private struct Completed: Sendable { let id: Int32; let success: Bool; let value: PluginJSON }
    private func lane(for name: String) -> Lane { ["stream", "video"].contains(name) ? playback : catalog }
    private let transport: Transport
    private let webSignIn: @Sendable (PluginWebSignIn) async throws -> PluginJSON
    private let oauthSignIn: @Sendable (PluginOAuthRequest) async throws -> PluginJSON
    private let openURL: @Sendable (URL, String) async throws -> Void
    public init(directory: URL, dataDirectory: URL, manifest: PluginManifest, settings: [String: String] = [:],
                webSignIn: @escaping @Sendable (PluginWebSignIn) async throws -> PluginJSON = { _ in throw PluginFailure.unavailable },
                oauthSignIn: @escaping @Sendable (PluginOAuthRequest) async throws -> PluginJSON = { _ in throw PluginFailure.unavailable },
                openURL: @escaping @Sendable (URL, String) async throws -> Void = { _, _ in throw PluginFailure.unavailable },
                transport: @escaping Transport = { request, permissions in
                    let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 16 * 1_024 * 1_024, followRedirects: false, permitting: { url in
                        ["http", "https"].contains(url.scheme ?? "") && url.user == nil && permissions.reaches(url.host ?? "")
                    })
                    guard let response = response as? HTTPURLResponse else { throw NetworkFailure.response }
                    return (data, response)
                }) throws {
        try manifest.validate()
        catalogCache = ResponseCache(directory: dataDirectory.appendingPathComponent("catalog-cache", isDirectory: true))
        host = try PluginHostState(root: directory, data: dataDirectory, manifest: manifest, settings: settings)
        self.transport = transport; self.webSignIn = webSignIn; self.oauthSignIn = oauthSignIn; self.openURL = openURL
    }
    public func headers(for url: URL, kind: String) throws -> [String: String] { try host.headers(for: url, kind: kind) }
    func nodeContentDigest() throws -> String {
        guard let loaded = catalog.contentDigest, loaded == (try? NodePluginContent.digest(host.root)) else {
            throw NodeSessionFailure.untrustedPlugin
        }
        return loaded
    }
    /// 信息源只交付授权资源与明文内容密钥，宿主仅处理通用 CENC 容器。
    func prepareCENCAudio(url: URL, headers: [String: String], encryption: PluginJSON) async throws -> URL {
        guard encryption["scheme"] == .string("cenc"), let hex = encryption["key"].string,
              let permissions = host.manifest.permissions else { throw PluginFailure.execution("Unsupported media encryption") }
        let key = try CENCAudio.key(hex), transport = transport
        // 命中缓存前同样核对权限，停用网络权限不能继续准备新资源。
        try Self.checkCENCMediaURL(url, permissions: permissions, redirect: false)
        return try await PreparedCENCAudio.shared.prepare(url: url, headers: headers, key: key) {
            var request = URLRequest(url: url); request.allHTTPHeaderFields = headers
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue(nil, forHTTPHeaderField: "Range"); request.setValue(nil, forHTTPHeaderField: "If-Range")
            for _ in 0..<10 {
                try Task.checkCancellation()
                guard let destination = request.url else { throw NetworkFailure.response }
                try Self.checkCENCMediaURL(destination, permissions: permissions, redirect: destination != url)
                let (data, response) = try await transport(request, permissions)
                guard data.count <= 16 << 20 else { throw PluginFailure.invalid("Encrypted audio exceeds preparation budget") }
                if [301, 302, 303, 307, 308].contains(response.statusCode) {
                    guard let next = try NetworkClient.redirect(request, response: response) else { throw NetworkFailure.redirect }
                    request = next; continue
                }
                guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
                return data
            }
            throw NetworkFailure.redirect
        }
    }
    private nonisolated static func checkCENCMediaURL(_ url: URL, permissions: PluginManifest.Permissions, redirect: Bool) throws {
        guard url.scheme == "https", url.user == nil, url.password == nil else {
            throw PluginFailure.invalid(AppLocalization.text("音频地址必须使用 HTTPS，且不能包含账号密码"))
        }
        let host = url.host?.lowercased() ?? ""
        guard !host.isEmpty, permissions.reaches(host) else {
            // 只报告主机与阶段，不输出带授权参数的 URL、Cookie 或内容密钥。
            let label = redirect ? AppLocalization.text("音频重定向域名不在插件网络权限内：%@", arguments: [host]) : AppLocalization.text("音频域名不在插件网络权限内：%@", arguments: [host])
            throw PluginFailure.invalid(label)
        }
    }
    public func updateSettings(_ settings: [String: String]) async {
        reset(playback)
        host.settings = settings
        await catalogCache.invalidate()
    }
    public func setPrompt(_ prompt: (@Sendable (SignInPrompt) -> Void)?) { host.prompt = prompt }
    /// 所有插件共用的宿主重置；先终止旧 VM，阻止迟到登录、定时器或请求恢复旧凭据。
    public func clearLoginCache() async throws {
        let diagnostics = accountDiagnostics(.clearLoginCache)
        reset(catalog); reset(playback)
        await acquire(catalog)
        await acquire(playback)
        do { try Task.checkCancellation(); try host.clearLoginStorage() }
        catch {
            release(playback); release(catalog)
            await diagnostics.finish(error is CancellationError ? .cancelled : .failed, error: error)
            throw error
        }
        release(playback); release(catalog)
        await catalogCache.invalidate()
        await diagnostics.finish(.completed)
    }
    public func supports(_ name: String) async throws -> Bool {
        let lane = lane(for: name)
        await acquire(lane); defer { release(lane) }
        try Task.checkCancellation()
        try await load(lane)
        return name.withCString { mbjs_has_export(lane.context!.pointer, $0) != 0 }
    }
    public func invoke(_ name: String, arguments: PluginJSON = .object([:]), timeout: Duration = .seconds(30),
                       verifiedNodeDigest: String? = nil) async throws -> PluginJSON {
        let lane = lane(for: name)
        await acquire(lane); defer { release(lane) }
        try Task.checkCancellation()
        try await load(lane)
        if let verifiedNodeDigest, try nodeContentDigest() != verifiedNodeDigest { throw NodeSessionFailure.untrustedPlugin }
        guard let context = lane.context else { throw PluginFailure.execution("Runtime unavailable") }
        let json = try arguments.encoded()
        let operation = PluginSignInDiagnostics.Operation(rawValue: name)
        let diagnostics = operation.map(accountDiagnostics)
        if diagnostics != nil { context.hostCalls.storage = .init() }
        lane.diagnostics = diagnostics
        defer {
            context.hostCalls.storage = nil
            lane.diagnostics = nil
            if ["signIn", "signOut", "signOutAndClearCache", "commitSession"].contains(name) { reset(playback) }
            if name == "signOutAndClearCache" { reset(catalog) }
        }
        do {
            let value = try await withTaskCancellationHandler {
                let status = name.withCString { name in json.withCString { mbjs_start(context.pointer, name, $0, 100) } }
                return try await finish(status, context: context, lane: lane, timeout: timeout)
            } onCancel: { context.cancel() }
            if let operation {
                await diagnostics?.finish(.init(operation: operation, result: value), storage: context.hostCalls.storage)
            }
            return value
        } catch {
            let outcome: PluginSignInDiagnostics.Outcome = error is CancellationError || Task.isCancelled ? .cancelled : .failed
            await diagnostics?.finish(outcome, storage: context.hostCalls.storage, error: error)
            reset(lane)
            throw error
        }
    }
    private func accountDiagnostics(_ operation: PluginSignInDiagnostics.Operation) -> PluginSignInDiagnostics {
        PluginSignInDiagnostics(directory: host.data, operation: operation, pluginVersion: host.manifest.version)
    }
    private func acquire(_ lane: Lane) async {
        if lane.busy { await withCheckedContinuation { lane.waiters.append($0) } } else { lane.busy = true }
    }
    private func release(_ lane: Lane) {
        if lane.waiters.isEmpty { lane.busy = false } else { lane.waiters.removeFirst().resume() }
    }
    private func load(_ lane: Lane) async throws {
        guard !lane.loaded else { return }
        let digest = lane === catalog ? try? NodePluginContent.digest(host.root) : nil
        let hostCalls = PluginContext.HostCalls(host)
        let pointer = mbjs_create({ opaque, operation, arguments in
            guard let opaque, let operation, let arguments else { return nil }
            let hostCalls = Unmanaged<PluginContext.HostCalls>.fromOpaque(opaque).takeUnretainedValue()
            let response: PluginJSON
            do {
                let values = try JSONDecoder().decode([PluginJSON].self, from: Data(String(cString: arguments).utf8))
                response = .object(["value": try hostCalls.call(String(cString: operation), values)])
            } catch { response = .object(["error": .string(error.localizedDescription)]) }
            return strdup((try? response.encoded()) ?? "{\"error\":\"Invalid host response\"}")
        }, Unmanaged.passUnretained(hostCalls).toOpaque(), 64 * 1_024 * 1_024)
        guard let pointer else { throw PluginFailure.execution("Runtime allocation failed") }
        let context = PluginContext(pointer, hostCalls: hostCalls); lane.context = context
        do {
            guard let bootstrap = Bundle.module.url(forResource: "PluginBootstrap", withExtension: "js") else { throw PluginFailure.execution("Missing host bootstrap") }
            let source = try String(contentsOf: bootstrap, encoding: .utf8)
            try await withTaskCancellationHandler {
                let setup = source.withCString { mbjs_evaluate(pointer, $0, "host.js", 0, 100) }
                _ = try await finish(setup, context: context, lane: lane, timeout: .seconds(5))
                let entry = host.manifest.entry
                let module = try host.call("module", [.string(entry)]).string ?? ""
                // 模块初始化可能建立 AES 查找表，允许最多 500ms；导出动作仍使用 100ms 执行片段。
                let status = entry.withCString { name in module.withCString { mbjs_evaluate(pointer, $0, name, 1, 500) } }
                _ = try await finish(status, context: context, lane: lane, timeout: .seconds(10))
            } onCancel: { context.cancel() }
            if let digest, "validateSession".withCString({ mbjs_has_export(pointer, $0) != 0 }),
               digest == (try? NodePluginContent.digest(host.root)) { lane.contentDigest = digest }
            lane.loaded = true
        } catch { reset(lane); throw error }
    }
    private func reset(_ lane: Lane) {
        lane.context?.cancel()
        for task in lane.events.values { task.cancel() }
        lane.events = [:]; lane.context = nil; lane.loaded = false; lane.failure = nil; lane.contentDigest = nil
    }
    private func finish(_ initialStatus: Int32, context: PluginContext, lane: Lane, timeout: Duration) async throws -> PluginJSON {
        var status = initialStatus
        let deadline = ContinuousClock.now + timeout
        while true {
            try Task.checkCancellation()
            guard lane.context === context else { throw CancellationError() }
            if let failure = lane.failure { throw failure }
            if status < 0 { throw readFailure(context) }
            try scheduleEvents(context, lane: lane)
            if status > 0 { return try JSONDecoder().decode(PluginJSON.self, from: Data(readResult(context).utf8)) }
            guard ContinuousClock.now < deadline else { context.cancel(); throw PluginFailure.execution("Execution timed out") }
            try await Task.sleep(for: .milliseconds(5))
            status = mbjs_poll(context.pointer, 100)
        }
    }
    /// 宿主任务随 VM 存活；函数返回后，未 await 的 timer/fetch 仍能推进模块状态。
    private func scheduleEvents(_ context: PluginContext, lane: Lane) throws {
        while let pointer = mbjs_take_event(context.pointer) {
            let text = String(cString: pointer); mbjs_free_string(pointer)
            let event = try JSONDecoder().decode(PluginJSON.self, from: Data(text.utf8))
            if event["kind"].string == "cancel", let id = event["request"].integer { lane.events.removeValue(forKey: Int32(id))?.cancel(); continue }
            guard let id = event["id"].integer, lane.events.count < 512 else { throw PluginFailure.execution("Host request limit exceeded") }
            let permissions = host.manifest.permissions, transport = transport, webSignIn = webSignIn, oauthSignIn = oauthSignIn, openURL = openURL, name = host.manifest.displayName, diagnostics = lane.diagnostics
            lane.events[Int32(id)] = Task { [weak self] in
                let completed: Completed
                let started = diagnostics == nil ? nil : Date()
                do {
                    let value = try await Self.process(event, permissions: permissions, name: name, webSignIn: webSignIn, oauthSignIn: oauthSignIn, openURL: openURL, transport: transport)
                    if let started { await diagnostics?.record(event, started: started, result: value, error: nil) }
                    completed = Completed(id: Int32(id), success: true, value: value)
                } catch {
                    if let started { await diagnostics?.record(event, started: started, result: nil, error: error) }
                    completed = Completed(id: Int32(id), success: false, value: .object([
                        "message": .string(error.localizedDescription),
                        "code": TrackProblem.isConnectionError(error) ? .string("network") : .null,
                    ]))
                }
                guard !Task.isCancelled else { return }
                await self?.complete(completed, context: context)
            }
        }
    }
    private func complete(_ completed: Completed, context: PluginContext) {
        let lane = catalog.context === context ? catalog : playback
        guard lane.context === context else { return }
        lane.events[completed.id] = nil
        do {
            let json = try completed.value.encoded()
            let budget: Int32 = json.utf8.count > 64 * 1024 ? 2_000 : 100
            let status = json.withCString { mbjs_settle(context.pointer, completed.id, completed.success ? 1 : 0, $0, budget) }
            if status < 0 { lane.failure = readFailure(context) }
            else { try scheduleEvents(context, lane: lane) }
        } catch { lane.failure = error }
    }
    private func readFailure(_ context: PluginContext) -> any Error {
        let message = readResult(context)
        if let pointer = mbjs_error_code(context.pointer) {
            defer { mbjs_free_string(pointer) }
            let code = String(cString: pointer)
            if let problem = TrackProblem.declared(code) { return PluginFailure.playback(problem, message) }
            if code == "network" { return PluginFailure.playback(.network, message) }
        }
        return PluginFailure.execution(message)
    }
    private func readResult(_ context: PluginContext) -> String {
        guard let result = mbjs_result(context.pointer) else { return "null" }
        defer { mbjs_free_string(result) }; return String(cString: result)
    }
    nonisolated static func setCookies(_ header: String) -> [String] {
        // 仅在逗号后紧接 cookie-name= 时切开，保留 Expires 中的日期逗号。
        guard let pattern = try? NSRegularExpression(pattern: #",(?=\s*[^=;,\s]+\s*=)"#) else { return [header] }
        let ranges = pattern.matches(in: header, range: NSRange(header.startIndex..., in: header))
        var start = header.startIndex, result: [String] = []
        for match in ranges {
            guard let range = Range(match.range, in: header) else { continue }
            result.append(header[start..<range.lowerBound].trimmingCharacters(in: .whitespaces)); start = range.upperBound
        }
        result.append(header[start...].trimmingCharacters(in: .whitespaces)); return result
    }
    private nonisolated static func process(_ event: PluginJSON, permissions: PluginManifest.Permissions?, name: String, webSignIn: @Sendable (PluginWebSignIn) async throws -> PluginJSON, oauthSignIn: @Sendable (PluginOAuthRequest) async throws -> PluginJSON, openURL: @Sendable (URL, String) async throws -> Void, transport: Transport) async throws -> PluginJSON {
        if event["kind"].string == "oauthSignIn" {
            return try await oauthSignIn(PluginOAuthRequest(event["options"], permissions: permissions))
        }
        if event["kind"].string == "openURL" {
            guard let address = event["url"].string, let url = URL(string: address), ["http", "https"].contains(url.scheme ?? ""), url.user == nil, url.host != nil else { throw PluginFailure.invalid("Not a web address") }
            try await openURL(url, name); return .null
        }
        if event["kind"].string == "webSignIn" {
            let value = event["options"]
            guard let address = value["url"].string, let url = URL(string: address), url.scheme == "https", url.user == nil,
                  let target = value["cookiesOf"].string.flatMap(URL.init(string:)), target.scheme == "https", target.user == nil,
                  let permissions, permissions.reaches(url.host ?? ""), permissions.reaches(target.host ?? "") else { throw PluginFailure.invalid("Sign-in page outside plugin network permissions") }
            let until = value["until"].array.compactMap(\.string)
            guard !until.isEmpty, until.allSatisfy({ !$0.isEmpty && !$0.contains(where: { "=;\r\n".contains($0) }) }) else { throw PluginFailure.invalid("Sign-in requires completion cookie names") }
            let html = value["html"].string
            if value["html"] != .null {
                guard let html, !html.isEmpty, html.utf8.count <= 256 * 1_024,
                      url.host == target.host, url.port == target.port else { throw PluginFailure.invalid("Invalid sign-in document or cookie origin") }
            }
            return try await webSignIn(PluginWebSignIn(title: name, url: url, cookiesOf: target, until: until, userAgent: value["userAgent"].string, html: html))
        }
        if event["kind"].string == "timer" {
            try await Task.sleep(for: .milliseconds(min(300_000, max(0, event["delay"].integer ?? 0)))); return .null
        }
        guard event["kind"].string == "fetch", let address = event["url"].string, let url = URL(string: address),
              ["http", "https"].contains(url.scheme ?? ""), url.user == nil, let permissions, permissions.reaches(url.host ?? "") else { throw PluginFailure.invalid("Request outside plugin network permissions") }
        var request = URLRequest(url: url)
        request.httpMethod = event["method"].string ?? "GET"
        if case .object(let headers) = event["headers"] {
            for (name, value) in headers {
                guard !["host", "content-length"].contains(name.lowercased()), !name.contains(where: { "\r\n".contains($0) }), let text = value.string, !text.contains(where: { "\r\n".contains($0) }) else { throw PluginFailure.invalid("Invalid request header") }
                request.setValue(text, forHTTPHeaderField: name)
            }
        }
        request.httpBody = event["body"].string.map { Data($0.utf8) }
        guard (request.httpBody?.count ?? 0) <= 8 * 1_024 * 1_024 else { throw PluginFailure.invalid("Request body too large") }
        let redirect = event["redirect"].string ?? "follow"
        guard ["follow", "manual", "error"].contains(redirect) else { throw PluginFailure.invalid("Invalid fetch redirect mode") }
        // transport 只执行单跳，先让插件决定是否消费 3xx 和其中的会话响应头。
        for _ in 0..<10 {
            try Task.checkCancellation()
            guard let target = request.url, ["http", "https"].contains(target.scheme ?? ""),
                  target.user == nil, permissions.reaches(target.host ?? "") else { throw PluginFailure.invalid("Request outside plugin network permissions") }
            let (data, response) = try await transport(request, permissions)
            guard data.count <= 16 * 1_024 * 1_024 else { throw PluginFailure.invalid("Response body too large") }
            if redirect != "manual", [301, 302, 303, 307, 308].contains(response.statusCode) {
                guard redirect == "follow", let next = try NetworkClient.redirect(request, response: response) else { throw NetworkFailure.redirect }
                request = next
                continue
            }
            let headers = Dictionary(uniqueKeysWithValues: response.allHeaderFields.compactMap { key, value -> (String, PluginJSON)? in
                guard let key = key as? String else { return nil }; return (key.lowercased(), .string(String(describing: value)))
            })
            let cookies = response.value(forHTTPHeaderField: "Set-Cookie").map(Self.setCookies) ?? []
            return .object(["setCookies": .array(cookies.map(PluginJSON.string)), "status": .number(Double(response.statusCode)), "url": .string(response.url?.absoluteString ?? target.absoluteString), "headers": .object(headers), "body": .string(String(decoding: data, as: UTF8.self)), "base64": .string(data.base64EncodedString())])
        }
        throw NetworkFailure.redirect
    }
}
