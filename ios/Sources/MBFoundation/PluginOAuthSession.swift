import AuthenticationServices
import Foundation
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// 系统浏览器完成授权后仅交换一次性授权码；取消或迟到回调不能完成另一次登录。
@MainActor final class PluginOAuthSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    #if os(iOS)
    private let external = PluginOAuthExternalSession { url in await UIApplication.shared.open(url) }
    var browserPage: PluginOAuthBrowserPage? { external.page }
    func handle(_ url: URL) -> Bool { external.handle(url) }
    func reopen(_ id: UUID) async { await external.reopen(id) }
    #else
    var browserPage: PluginOAuthBrowserPage? { nil }
    func handle(_ url: URL) -> Bool { false }
    func reopen(_ id: UUID) async {}
    #endif
    private var session: ASWebAuthenticationSession?
    private var pending: CheckedContinuation<URL, any Error>?
    private var active: UUID?

    func signIn(_ request: PluginOAuthRequest) async throws -> PluginJSON {
        guard session == nil else { throw PluginFailure.execution("Another system sign-in is already open") }
        let scheme = request.redirectURI.scheme ?? ""
        let entries = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        let registered = entries.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        guard registered.contains(scheme) else { throw PluginFailure.execution("当前 MB 尚未配置此登录回调，请更新应用") }
        #if os(iOS)
        let token = try await external.authorize(request)
        #else
        let flow = try PluginOAuthFlow(request: request)
        let callback: URL = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, any Error>) in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                active = request.id; pending = continuation
                let session = ASWebAuthenticationSession(url: flow.authorizationURL, callbackURLScheme: scheme) { [weak self] url, error in
                    Task { @MainActor in self?.finish(request.id, url: url, error: error) }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                self.session = session
                if !session.start() { finish(request.id, url: nil, error: PluginFailure.execution("无法打开系统授权页面")) }
            }
        } onCancel: { Task { @MainActor [weak self] in self?.cancel(request.id) } }
        try Task.checkCancellation()
        let token = try flow.tokenRequest(callback: callback)
        #endif
        let (data, response) = try await NetworkClient.shared.data(for: token, maximumBytes: 64 << 10, followRedirects: false)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw PluginFailure.execution("系统授权交换失败，请重新登录") }
        let value = try JSONDecoder().decode(PluginJSON.self, from: data)
        guard let access = value["access_token"].string, !access.isEmpty, value["token_type"].string?.lowercased() == "bearer" else {
            throw PluginFailure.execution("授权服务器未返回有效登录信息")
        }
        return value
    }
    private func finish(_ id: UUID, url: URL?, error: (any Error)?) {
        guard active == id else { return }
        let continuation = pending; pending = nil; active = nil; session = nil
        if let url { continuation?.resume(returning: url) }
        else { continuation?.resume(throwing: error ?? CancellationError()) }
    }
    private func cancel(_ id: UUID) {
        guard active == id else { return }
        session?.cancel()
        finish(id, url: nil, error: CancellationError())
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if os(iOS)
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
        #else
        NSApplication.shared.keyWindow ?? ASPresentationAnchor()
        #endif
    }
}
