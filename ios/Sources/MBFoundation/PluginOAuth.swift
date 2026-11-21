import CryptoKit
import Foundation
import Security

/// 原生插件 OAuth 授权合同。插件声明服务地址，宿主生成 PKCE 与 state，不读取浏览器 Cookie。
public struct PluginOAuthRequest: Sendable {
    public let id = UUID()
    public let authorizationEndpoint: URL
    public let tokenEndpoint: URL
    public let clientID: String
    public let redirectURI: URL
    public let scopes: [String]

    init(_ value: PluginJSON, permissions: PluginManifest.Permissions?) throws {
        func endpoint(_ name: String) throws -> URL {
            guard let text = value[name].string, let url = URL(string: text), url.scheme == "https",
                  url.user == nil, url.password == nil, url.fragment == nil,
                  url.port == nil || url.port == 443, permissions?.reaches(url.host ?? "") == true else {
                throw PluginFailure.invalid("OAuth endpoint outside plugin network permissions")
            }
            return url
        }
        authorizationEndpoint = try endpoint("authorizationEndpoint")
        tokenEndpoint = try endpoint("tokenEndpoint")
        guard let client = value["clientID"].string, !client.isEmpty, client.count <= 512,
              !client.contains(where: \.isWhitespace),
              let redirect = value["redirectURI"].string.flatMap(URL.init(string:)),
              let scheme = redirect.scheme, !["http", "https", "file", "javascript", "data"].contains(scheme.lowercased()),
              redirect.user == nil, redirect.password == nil, redirect.query == nil, redirect.fragment == nil else {
            throw PluginFailure.invalid("Invalid OAuth client or redirect URI")
        }
        let scopes = value["scopes"].array.compactMap(\.string)
        guard !scopes.isEmpty, scopes.count == value["scopes"].array.count, Set(scopes).count == scopes.count, scopes.count <= 16, scopes.allSatisfy({ !$0.isEmpty && !$0.contains(where: \.isWhitespace) }) else {
            throw PluginFailure.invalid("Invalid OAuth scopes")
        }
        clientID = client; redirectURI = redirect; self.scopes = scopes
    }
}

/// 每次授权独立生成校验值；回调必须同时匹配路径与 state，授权码只发送给声明的 token 服务。
struct PluginOAuthFlow: Sendable {
    let request: PluginOAuthRequest
    let verifier: String
    let state: String
    init(request: PluginOAuthRequest) throws {
        self.request = request
        verifier = try Self.random()
        state = try Self.random()
    }
    private static func random() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw PluginFailure.execution("Unable to prepare secure sign-in") }
        return base64URL(Data(bytes))
    }
    private static func base64URL(_ bytes: Data) -> String {
        bytes.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    var authorizationURL: URL {
        var parts = URLComponents(url: request.authorizationEndpoint, resolvingAgainstBaseURL: false)!
        parts.queryItems = [
            URLQueryItem(name: "client_id", value: request.clientID),
            URLQueryItem(name: "redirect_uri", value: request.redirectURI.absoluteString),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: request.scopes.joined(separator: " ")),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
        ]
        return parts.url!
    }
    func matches(callback: URL) -> Bool {
        let expected = request.redirectURI
        guard callback.absoluteString.utf8.count <= 8192,
              callback.scheme == expected.scheme, callback.host == expected.host, callback.port == expected.port,
              callback.path == expected.path, callback.user == nil, callback.password == nil, callback.fragment == nil,
              let parts = URLComponents(url: callback, resolvingAgainstBaseURL: false) else { return false }
        let states = (parts.queryItems ?? []).filter { $0.name == "state" }
        return states.count == 1 && states[0].value == state
    }
    func tokenRequest(callback: URL) throws -> URLRequest {
        let expected = request.redirectURI
        guard matches(callback: callback),
              let parts = URLComponents(url: callback, resolvingAgainstBaseURL: false) else { throw PluginFailure.invalid("OAuth callback did not match") }
        let items = parts.queryItems ?? []
        func single(_ key: String) -> String? {
            let found = items.filter { $0.name == key }
            return found.count == 1 ? found[0].value : nil
        }
        guard single("state") == state else { throw PluginFailure.invalid("OAuth state did not match") }
        if items.contains(where: { $0.name == "error" }) { throw PluginFailure.execution("系统授权未完成，请重新登录") }
        guard let code = single("code"), !code.isEmpty else { throw PluginFailure.invalid("OAuth callback has no code") }
        var body = URLComponents()
        body.queryItems = [
            URLQueryItem(name: "client_id", value: request.clientID), URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "redirect_uri", value: expected.absoluteString),
            URLQueryItem(name: "grant_type", value: "authorization_code"), URLQueryItem(name: "code_verifier", value: verifier),
        ]
        var token = URLRequest(url: request.tokenEndpoint)
        token.httpMethod = "POST"
        token.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        token.httpBody = Data((body.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
        return token
    }
}
