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
    public private(set) var canClearLoginCache = false
    public private(set) var errorMessage: String?
    public private(set) var revision = 0
    public private(set) var canUseCloud = false
    public private(set) var checkedLocalAccount = false
    public private(set) var pendingCloudUpload = false
    public let available: Bool
    public let displayName: String
    @ObservationIgnored private let source: (any AccountSource)?
    @ObservationIgnored private let platformID: String?
    @ObservationIgnored private let persistence: AppPersistence
    @ObservationIgnored private var attempt: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    #if os(iOS)
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    #endif
    public init(source: (any AccountSource)?, platformID: String? = nil, persistence: AppPersistence = .shared) {
        self.source = source; self.platformID = platformID; self.persistence = persistence
        available = source != nil; displayName = source?.displayName ?? "账号"
    }

    public func restore() async {
        let current = generation
        let canClear = await (source as? any AccountCacheResetSource)?.supportsAccountCacheReset() ?? false
        let cloud = (try? await (source as? PluginSource)?.supportsSessionSync()) == true
        guard current == generation, !Task.isCancelled else { return }
        canClearLoginCache = canClear
        canUseCloud = cloud
        do {
            let account = try await source?.account()
            guard current == generation, !Task.isCancelled else { return }
            self.account = account; checkedLocalAccount = true; errorMessage = nil; revision += 1
        } catch {
            if current == generation && !Task.isCancelled { checkedLocalAccount = false; errorMessage = error.localizedDescription }
        }
    }

    func verifyCloudPlugin(client: PlatformDataClient, node: PlatformNode) async throws -> String {
        guard let source = source as? PluginSource else { throw NodeSessionFailure.untrustedPlugin }
        return try await source.verifyNodeSessionPlugin(client: client, node: node)
    }

    func cloudUploadCompleted() { pendingCloudUpload = false }

    func exportCloudSignIn(digest: String) async throws -> (Int, NodeSessionPayload)? {
        guard let source = source as? PluginSource, let account, !isSigningIn, !isSigningOut else { return nil }
        return try await source.exportNodeSession(accountID: account.id, digest: digest)
    }

    func restoreCloudSignIn(_ payload: (Int, NodeSessionPayload), digest: String, stillCurrent: @escaping @MainActor @Sendable () -> Bool) async throws -> SourceAccount? {
        guard let source = source as? PluginSource, checkedLocalAccount, account == nil, !isSigningIn, !isSigningOut else { return nil }
        let current = generation
        let restored = try await source.applyNodeSession(payload.1, format: payload.0, digest: digest, stillCurrent: { [weak self] in
            self?.generation == current && self?.account == nil && stillCurrent()
        })
        guard generation == current, stillCurrent(), let restored else { return nil }
        account = restored; errorMessage = nil; revision += 1
        return restored
    }

    public func signIn() {
        guard source != nil, !isSigningOut else { return }
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
                var allowUpload = canUseCloud
                if canUseCloud, let platformID {
                    do { try await persistence.setCloudSignInBlocked(platformID, blocked: false) }
                    catch { allowUpload = false }
                }
                guard current == generation, !Task.isCancelled else { return }
                endBackgroundSignIn()
                pendingCloudUpload = allowUpload
                account = found; checkedLocalAccount = true; revision += 1
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

    public func signOut(clearCache: Bool = false) async {
        guard !isSigningOut, let source else { return }
        if clearCache && !canClearLoginCache { return }
        cancelSignIn()
        isSigningOut = true
        defer { isSigningOut = false }
        let current = generation
        do {
            if canUseCloud, let platformID { try await persistence.setCloudSignInBlocked(platformID, blocked: true) }
            if clearCache, let resettable = source as? any AccountCacheResetSource {
                try await resettable.signOutAndClearCache()
            } else { try await source.signOut() }
            guard current == generation else { return }
            pendingCloudUpload = false
            account = nil; checkedLocalAccount = true; revision += 1; errorMessage = nil
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
