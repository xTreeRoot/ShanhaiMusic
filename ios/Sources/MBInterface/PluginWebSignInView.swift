import MBFoundation
import SwiftUI
import WebKit

private struct PluginUIKey: EnvironmentKey { static let defaultValue: PluginUIService? = nil }
extension EnvironmentValues {
    var pluginUI: PluginUIService? { get { self[PluginUIKey.self] } set { self[PluginUIKey.self] = newValue } }
}
struct PluginSignInPresentation: ViewModifier {
    let service: PluginUIService?
    func body(content: Content) -> some View {
        content.sheet(item: Binding(get: { service?.signIn }, set: { if $0 == nil, let request = service?.signIn { service?.cancel(request.id) } })) { request in
            NavigationStack {
                PluginWebPage(request: request, complete: { cookie in service?.finish(request.id, cookie: cookie) },
                              failure: { message in service?.fail(request.id, message: message) })
                    .navigationTitle(request.title)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { service?.cancel(request.id) } } }
            }
        }
    }
}

#if os(iOS)
private struct PluginWebPage: UIViewRepresentable {
    let request: PluginWebSignIn
    let complete: (String) -> Void
    let failure: (String) -> Void
    func makeCoordinator() -> CookieObserver { CookieObserver(request: request, complete: complete, failure: failure) }
    func makeUIView(context: Context) -> WKWebView { context.coordinator.makeView() }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    static func dismantleUIView(_ view: WKWebView, coordinator: CookieObserver) { coordinator.stop(view) }
}
#else
private struct PluginWebPage: NSViewRepresentable {
    let request: PluginWebSignIn
    let complete: (String) -> Void
    let failure: (String) -> Void
    func makeCoordinator() -> CookieObserver { CookieObserver(request: request, complete: complete, failure: failure) }
    func makeNSView(context: Context) -> WKWebView { context.coordinator.makeView() }
    func updateNSView(_ view: WKWebView, context: Context) {}
    static func dismantleNSView(_ view: WKWebView, coordinator: CookieObserver) { coordinator.stop(view) }
}
#endif

@MainActor private final class CookieObserver: NSObject, WKHTTPCookieStoreObserver, WKScriptMessageHandler, WKNavigationDelegate {
    let request: PluginWebSignIn
    let complete: (String) -> Void
    let failure: (String) -> Void
    private var active = true
    init(request: PluginWebSignIn, complete: @escaping (String) -> Void, failure: @escaping (String) -> Void) {
        self.request = request; self.complete = complete; self.failure = failure
    }
    func makeView() -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        if request.html != nil { config.userContentController.add(self, name: "mbSignIn") }
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = self
        view.customUserAgent = request.userAgent
        config.websiteDataStore.httpCookieStore.add(self)
        if let html = request.html { view.loadHTMLString(html, baseURL: request.url) }
        else { view.load(URLRequest(url: request.url)) }
        return view
    }
    func stop(_ view: WKWebView) {
        active = false; view.stopLoading(); view.configuration.websiteDataStore.httpCookieStore.remove(self)
        view.configuration.userContentController.removeScriptMessageHandler(forName: "mbSignIn")
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard active, message.frameInfo.isMainFrame, message.frameInfo.securityOrigin.protocol == "https",
              message.frameInfo.securityOrigin.host == request.url.host,
              (message.frameInfo.securityOrigin.port == 0 ? 443 : message.frameInfo.securityOrigin.port) == (request.url.port ?? 443),
              let body = message.body as? [String: String], let error = body["error"], !error.isEmpty, error.utf8.count <= 1_024 else { return }
        active = false; failure(error)
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard active else { return }; active = false; failure("登录网页已中断，请重新登录")
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error) {
        guard active else { return }; active = false; failure("登录网页无法加载，请检查网络后重试")
    }
    func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
        cookieStore.getAllCookies { [weak self] cookies in
            guard let self, self.active else { return }
            let host = self.request.cookiesOf.host?.lowercased() ?? "", path = self.request.cookiesOf.path.isEmpty ? "/" : self.request.cookiesOf.path
            let relevant = cookies.filter { cookie in
                let domain = cookie.domain.lowercased()
                let matches = domain.hasPrefix(".") ? host == String(domain.dropFirst()) || host.hasSuffix(domain) : host == domain
                let cookiePath = cookie.path
                let pathMatches = path == cookiePath || path.hasPrefix(cookiePath.hasSuffix("/") ? cookiePath : cookiePath + "/")
                return matches && pathMatches && (cookie.expiresDate == nil || cookie.expiresDate! > Date())
            }
            guard self.request.until.allSatisfy({ name in relevant.contains { $0.name == name && !$0.value.isEmpty } }) else { return }
            self.active = false
            let header = relevant.sorted { $0.path.count > $1.path.count }.map { $0.name + "=" + $0.value }.joined(separator: "; ")
            self.complete(header)
        }
    }
}
