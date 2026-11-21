import Foundation
import Observation

public struct PluginOAuthBrowserPage: Identifiable, Sendable {
    public let id: UUID
    public let host: String
}

/// 外部浏览器与应用 URL 回调之间的单次交接；取消、伪造或重复回调不会修改有效会话。
@MainActor @Observable final class PluginOAuthExternalSession {
    private(set) var page: PluginOAuthBrowserPage?
    @ObservationIgnored private var flow: PluginOAuthFlow?
    @ObservationIgnored private var pending: CheckedContinuation<URLRequest, any Error>?
    @ObservationIgnored private let open: @MainActor (URL) async -> Bool
    init(open: @escaping @MainActor (URL) async -> Bool) { self.open = open }

    func authorize(_ request: PluginOAuthRequest) async throws -> URLRequest {
        guard pending == nil else { throw PluginFailure.execution("Another system sign-in is already open") }
        let next = try PluginOAuthFlow(request: request)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                flow = next; pending = continuation
                page = PluginOAuthBrowserPage(id: request.id, host: request.authorizationEndpoint.host ?? "")
                Task { await reopen(request.id) }
            }
        } onCancel: { Task { @MainActor [weak self] in self?.cancel(request.id) } }
    }
    func reopen(_ id: UUID) async {
        guard page?.id == id, let flow else { return }
        let opened = await open(flow.authorizationURL)
        if !opened, page?.id == id { finish(.failure(PluginFailure.execution("无法打开系统浏览器，请检查默认浏览器设置"))) }
    }
    @discardableResult func handle(_ callback: URL) -> Bool {
        guard let flow, pending != nil, flow.matches(callback: callback) else { return false }
        do { finish(.success(try flow.tokenRequest(callback: callback))); return true }
        catch {
            // 有效 state 的拒绝授权可结束此次等待；畸形或重复参数不终止仍在进行的授权。
            let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
            if items.filter({ $0.name == "error" }).count == 1, !items.contains(where: { $0.name == "code" }) {
                finish(.failure(error)); return true
            }
            return false
        }
    }
    func cancel(_ id: UUID) { if page?.id == id { finish(.failure(CancellationError())) } }
    private func finish(_ result: Result<URLRequest, any Error>) {
        let continuation = pending; pending = nil; flow = nil; page = nil
        continuation?.resume(with: result)
    }
}
