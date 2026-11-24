import Foundation
@testable import MBFoundation
import Testing

private final class PromptRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [SignInPrompt] = []
    func append(_ value: SignInPrompt) { lock.lock(); defer { lock.unlock() }; values.append(value) }
    var prompts: [SignInPrompt] { lock.lock(); defer { lock.unlock() }; return values }
}

@Test func pluginSignInAcceptsTextBeforeQRCodeAndAfterConfirmation() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try """
    export function signIn() {
      host.signInPrompt({text:'正在生成网易云音乐登录二维码…'});
      host.signInPrompt({qr:'https://music.163.com/login?codekey=fixture',text:'请扫码'});
      host.signInPrompt({text:'登录成功'});
      return {id:'42',name:'Fixture'};
    }
    """.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"prompt-fixture"}"#.utf8))
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest)
    let recorder = PromptRecorder()
    await runtime.setPrompt { recorder.append($0) }
    #expect(try await runtime.invoke("signIn")["id"] == .string("42"))
    #expect(recorder.prompts.map(\.message) == ["正在生成网易云音乐登录二维码…", "请扫码", "登录成功"])
    #expect(recorder.prompts.map(\.qrURL) == [nil, URL(string: "https://music.163.com/login?codekey=fixture"), nil])
}

@Test(arguments: ["file:///etc/passwd", "javascript:alert(1)", "https://"])
func pluginSignInStillRejectsInvalidQRCodeAddresses(_ address: String) throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"prompt-fixture"}"#.utf8))
    let host = try PluginHostState(root: root, data: root, manifest: manifest, settings: [:])
    #expect(throws: PluginFailure.self) {
        try host.call("prompt", [.object(["qr": .string(address), "text": .string("请扫码")])])
    }
}

@Test func declaredSignInMethodReachesPluginAndInvalidChoiceDoesNotExecute() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try """
    let calls = 0;
    export function signIn({method}) { calls++; return {id:method,name:method}; }
    export function count() { return calls; }
    """.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"choice","signInMethods":[{"id":"first","label":"First"},{"id":"second","label":"Second"}]}"#.utf8))
    try manifest.validate()
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest)
    let source = PluginSource(manifest: manifest, runtime: runtime)
    #expect(try await source.signIn(method: "second") { _ in }.id == "second")
    await #expect(throws: PluginFailure.self) { try await source.signIn(method: "invalid") { _ in } }
    #expect(try await runtime.invoke("count") == .number(1))
}
