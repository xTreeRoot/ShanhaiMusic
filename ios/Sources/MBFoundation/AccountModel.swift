import Foundation
import Observation
#if os(iOS)
import UIKit
#endif

@MainActor @Observable
public final class AccountModel {
    public private(set) var account: SourceAccount?
    public private(set) var prompt: SignInPrompt?
    public private(set) var choosingSignInMethod = false
    public var signInMethods: [SignInMethod] { (source as? any SignInMethodSource)?.signInMethods ?? [] }
    public private(set) var isSigningIn = false
    public private(set) var isSigningOut = false
    public private(set) var errorMessage: String?
    public private(set) var revision = 0
    public let available: Bool
    public let displayName: String
    @ObservationIgnored private let source: (any AccountSource)?
    @ObservationIgnored private var attempt: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    #if os(iOS)
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    #endif
    public init(source: (any AccountSource)?) { self.source = source; available = source != nil; displayName = source?.displayName ?? "账号" }

    public func restore() async {
        let current = generation
        do {
            let account = try await source?.account()
            guard current == generation, !Task.isCancelled else { return }
            self.account = account; errorMessage = nil; revision += 1
        } catch {
            if current == generation && !Task.isCancelled { errorMessage = error.localizedDescription }
        }
    }

    public func signIn() {
        guard source != nil else { return }
        if !signInMethods.isEmpty { cancelSignIn(); choosingSignInMethod = true; errorMessage = nil }
        else { startSignIn(method: nil) }
    }
    public func chooseSignInMethod(_ method: String) {
        guard choosingSignInMethod, signInMethods.contains(where: { $0.id == method }) else { return }
        startSignIn(method: method)
    }
    private func startSignIn(method: String?) {
        guard let source else { return }
        cancelSignIn()
        let current = generation
        isSigningIn = true; errorMessage = nil
        attempt = Task { [self] in
            do {
                let receive: @Sendable (SignInPrompt) async -> Void = { [weak self] prompt in
                    await self?.receive(prompt, generation: current)
                }
                let found: SourceAccount
                if let method, let selectable = source as? any SignInMethodSource {
                    found = try await selectable.signIn(method: method, prompt: receive)
                } else { found = try await source.signIn(prompt: receive) }
                guard current == generation, !Task.isCancelled else { return }
                endBackgroundSignIn()
                account = found; revision += 1
                prompt = nil; isSigningIn = false; attempt = nil
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                endBackgroundSignIn()
                errorMessage = error.localizedDescription; isSigningIn = false; prompt = nil; attempt = nil
            }
        }
    }

    public func cancelSignIn() {
        generation += 1
        attempt?.cancel(); attempt = nil
        endBackgroundSignIn()
        isSigningIn = false; prompt = nil; choosingSignInMethod = false
    }

    public func signOut() async {
        cancelSignIn()
        isSigningOut = true
        defer { isSigningOut = false }
        let current = generation
        do {
            try await source?.signOut()
            guard current == generation else { return }
            account = nil; revision += 1; errorMessage = nil
        } catch { if current == generation { errorMessage = error.localizedDescription } }
    }

    /// 扫码需切换到官方 App，申请系统允许的短时后台执行，避免确认响应在挂起时丢失。
    private func beginBackgroundSignIn(generation: Int) {
        #if os(iOS)
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "MBSignIn") { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, generation == self.generation, self.isSigningIn else { return }
                self.cancelSignIn()
                self.errorMessage = AppLocalization.text("登录等待已超出系统后台时限，请回到 MB 重新登录")
            }
        }
        #endif
    }
    private func endBackgroundSignIn() {
        #if os(iOS)
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
        #endif
    }

    private func receive(_ prompt: SignInPrompt, generation: Int) {
        guard generation == self.generation, isSigningIn, !Task.isCancelled else { return }
        self.prompt = prompt
        if prompt.qrURL != nil { beginBackgroundSignIn(generation: generation) }
    }
    public func waitForSignIn() async { await attempt?.value }
}
