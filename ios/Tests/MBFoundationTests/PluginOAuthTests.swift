import CryptoKit
import Foundation
@testable import MBFoundation
import Testing

private func oauthOptions() -> PluginJSON {
    .object([
        "authorizationEndpoint": .string("https://api.example.test/authorize"),
        "tokenEndpoint": .string("https://api.example.test/token"),
        "clientID": .string("public-fixture-client"),
        "redirectURI": .string("mb-fixture:/oauthredirect"),
        "scopes": .array([.string("library")]),
    ])
}

private func oauthPermissions() throws -> PluginManifest.Permissions? {
    try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","permissions":{"network":["api.example.test"]}}"#.utf8)).permissions
}

@Test func oauthGeneratesIndependentPKCEAndExchangesOnlyMatchingCallback() throws {
    let request = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let flow = try PluginOAuthFlow(request: request)
    let other = try PluginOAuthFlow(request: request)
    #expect(flow.state != other.state && flow.verifier != other.verifier)
    #expect(flow.verifier.count == 43)
    let query = try #require(URLComponents(url: flow.authorizationURL, resolvingAgainstBaseURL: false)?.queryItems)
    let expected = Data(SHA256.hash(data: Data(flow.verifier.utf8))).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    #expect(query.first { $0.name == "code_challenge" }?.value == expected)
    #expect(query.first { $0.name == "code_challenge_method" }?.value == "S256")
    #expect(!flow.authorizationURL.absoluteString.contains(flow.verifier))
    let callback = try #require(URL(string: "mb-fixture:/oauthredirect?state=\(flow.state)&code=a%2Bb%26c"))
    let token = try flow.tokenRequest(callback: callback)
    #expect(token.httpMethod == "POST" && token.url == request.tokenEndpoint)
    let form = try #require(URLComponents(string: "https://fixture.test/?" + String(decoding: token.httpBody ?? Data(), as: UTF8.self))?.queryItems)
    #expect(form.first { $0.name == "code" }?.value == "a+b&c")
    #expect(form.first { $0.name == "code_verifier" }?.value == flow.verifier)
    #expect(!form.contains { $0.name == "client_secret" })
    for suffix in ["state=wrong&code=x", "state=\(flow.state)&state=\(flow.state)&code=x", "state=\(flow.state)&code=x&code=y", "state=\(flow.state)&error=access_denied"] {
        #expect(throws: PluginFailure.self) { try flow.tokenRequest(callback: URL(string: "mb-fixture:/oauthredirect?" + suffix)!) }
    }
    for base in ["other:/oauthredirect", "mb-fixture:/other", "mb-fixture://host/oauthredirect", "mb-fixture://user:secret@/oauthredirect"] {
        if let url = URL(string: base + "?state=\(flow.state)&code=x") {
            #expect(throws: PluginFailure.self) { try flow.tokenRequest(callback: url) }
        }
    }
}

@Test func oauthRejectsUndeclaredEndpointsAndInvalidRedirectsBeforeOpeningBrowser() throws {
    let permissions = try oauthPermissions()
    for (key, value): (String, PluginJSON) in [
        ("authorizationEndpoint", .string("https://other.test/authorize")),
        ("tokenEndpoint", .string("http://api.example.test/token")),
        ("tokenEndpoint", .string("https://user:password@api.example.test/token")),
        ("redirectURI", .string("https://api.example.test/callback")),
        ("redirectURI", .string("mb-fixture:/callback?state=fixed")),
        ("scopes", .array([.string("library"), .number(1)])),
        ("scopes", .array([.string("library"), .string("library")])),
    ] {
        guard case .object(var options) = oauthOptions() else { return }
        options[key] = value
        #expect(throws: PluginFailure.self) { try PluginOAuthRequest(.object(options), permissions: permissions) }
    }
}

@MainActor @Test func externalOAuthOpensBrowserAndAcceptsOnlyMatchingSingleUseCallback() async throws {
    var opened: URL?
    let browser = PluginOAuthExternalSession { url in opened = url; return true }
    let request = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let pending = Task { try await browser.authorize(request) }
    while opened == nil { await Task.yield() }
    let url = try #require(opened)
    let state = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "state" }?.value)
    #expect(browser.page?.id == request.id)
    #expect(!browser.handle(URL(string: "mb-fixture:/oauthredirect?state=wrong&code=x")!))
    #expect(!browser.handle(URL(string: "mb-fixture:/other?state=\(state)&code=x")!))
    #expect(!browser.handle(URL(string: "mb-fixture:/oauthredirect?state=\(state)&code=x&code=y")!))
    #expect(browser.page != nil)
    let callback = URL(string: "mb-fixture:/oauthredirect?state=\(state)&code=one-time")!
    #expect(browser.handle(callback))
    let token = try await pending.value
    #expect(token.url == request.tokenEndpoint)
    #expect(String(decoding: token.httpBody ?? Data(), as: UTF8.self).contains("code=one-time"))
    #expect(browser.page == nil && !browser.handle(callback))
}

@MainActor @Test func externalOAuthCancellationRejectsLateReturnAndKeepsNextAttemptActive() async throws {
    var opened: [URL] = []
    let browser = PluginOAuthExternalSession { url in opened.append(url); return true }
    let request = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let first = Task { try await browser.authorize(request) }
    while opened.isEmpty { await Task.yield() }
    let state = try #require(URLComponents(url: opened[0], resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "state" }?.value)
    first.cancel()
    do { _ = try await first.value; Issue.record("cancelled authorization completed") } catch is CancellationError {} catch { throw error }
    let nextRequest = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let second = Task { try await browser.authorize(nextRequest) }
    while opened.count < 2 { await Task.yield() }
    #expect(!browser.handle(URL(string: "mb-fixture:/oauthredirect?state=\(state)&code=old")!))
    #expect(browser.page?.id == nextRequest.id)
    second.cancel()
    do { _ = try await second.value; Issue.record("cancelled authorization completed") } catch is CancellationError {} catch { throw error }
}

@MainActor @Test func externalOAuthBrowserFailureAndExplicitDenialReleasePendingAttempt() async throws {
    let request = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let failed = PluginOAuthExternalSession { _ in false }
    do { _ = try await failed.authorize(request); Issue.record("browser failed to open") } catch { #expect(failed.page == nil) }
    var opened: URL?
    let browser = PluginOAuthExternalSession { url in opened = url; return true }
    let pending = Task { try await browser.authorize(request) }
    while opened == nil { await Task.yield() }
    let state = try #require(URLComponents(url: opened!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "state" }?.value)
    #expect(browser.handle(URL(string: "mb-fixture:/oauthredirect?state=\(state)&error=access_denied")!))
    do { _ = try await pending.value; Issue.record("denied authorization completed") } catch { #expect(browser.page == nil) }
}
