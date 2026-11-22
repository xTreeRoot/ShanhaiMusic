import Foundation
@testable import MBFoundation
import Testing

private actor DelayedAccount: AccountSource {
    nonisolated let displayName = "测试来源"
    var continuation: CheckedContinuation<SourceAccount, Never>?
    var observer: CheckedContinuation<Void, Never>?
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func account() -> SourceAccount? { nil }
    func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async -> SourceAccount {
        await prompt(SignInPrompt(qrURL: URL(string: "https://example.test/qr")!, message: "扫码"))
        return await withCheckedContinuation { continuation = $0; observer?.resume(); observer = nil }
    }
    func signOut() {}
    func wait() async { if continuation == nil { await withCheckedContinuation { observer = $0 } } }
    func finish() { continuation?.resume(returning: SourceAccount(id: "old", name: "旧账号")); continuation = nil }
}

@Test @MainActor func cancellingAccountPromptIgnoresNonCooperativeLateAccountResult() async {
    let source = DelayedAccount()
    let active = AccountModel(source: source)
    active.signIn()
    await source.wait()
    #expect(active.prompt != nil)
    let pending = Task { await active.waitForSignIn() }
    await Task.yield()
    active.cancelSignIn()
    await source.finish(); await pending.value
    #expect(active.account == nil)
    #expect(active.prompt == nil)
    #expect(!active.isSigningIn)
}

private actor LibraryFixture: LibrarySource {
    nonisolated let displayName = "test"
    var calls: [String?] = []
    var fail = false
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func library(_ route: LibraryRoute, cursor: String?) throws -> LibraryPage {
        calls.append(cursor)
        if fail { fail = false; throw URLError(.networkConnectionLost) }
        let ids = cursor == nil ? ["one"] : ["one", "two"]
        return LibraryPage(title: "测试列表", tracks: ids.map { Track(sourceID: "test", sourceTrackID: $0, title: $0, artist: "") }, nextCursor: cursor == nil ? "next" : nil)
    }
    func failOnce() { fail = true }
}

@Test @MainActor func personalLibraryPaginationRetainsRowsOnFailureRetriesCursorAndDeduplicates() async {
    let source = LibraryFixture()
    let active = LibraryModel(source: source, route: .history)
    await active.refresh()
    await source.failOnce(); await active.loadMore()
    #expect(active.tracks.map(\.title) == ["one"])
    #expect(active.nextCursor == "next")
    #expect(active.errorMessage != nil)
    await active.loadMore()
    #expect(active.tracks.map(\.title) == ["one", "two"])
    #expect(active.nextCursor == nil)
    #expect(await source.calls == [nil, "next", "next"])
}

@Test @MainActor func failedRefreshRetriesFirstPageRatherThanAppendingOldNextCursor() async {
    let source = LibraryFixture()
    let active = LibraryModel(source: source, route: .history)
    await active.refresh()
    await source.failOnce(); await active.refresh()
    #expect(active.tracks.map(\.title) == ["one"])
    #expect(active.nextCursor == "next")
    await active.retry()
    #expect(await source.calls == [nil, nil, nil])
    #expect(active.tracks.map(\.title) == ["one"])
    await active.loadMore()
    #expect(active.tracks.map(\.title) == ["one", "two"])
}

private actor MethodAccountFixture: SignInMethodSource {
    nonisolated let displayName = "方式测试"
    nonisolated let signInMethods = [SignInMethod(id: "first", label: "第一种"), SignInMethod(id: "second", label: "第二种")]
    var calls: [String] = []
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func account() -> SourceAccount? { nil }
    func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) throws -> SourceAccount { throw URLError(.unsupportedURL) }
    func signIn(method: String, prompt: @escaping @Sendable (SignInPrompt) async -> Void) -> SourceAccount {
        calls.append(method); return SourceAccount(id: method, name: method)
    }
    func signOut() {}
}

@Test @MainActor func choosingLoginMethodWaitsForInputAndOnlyStartsSelectedProvider() async {
    let source = MethodAccountFixture(), model = AccountModel(source: nil)
    model.signIn(); #expect(!model.choosingSignInMethod)
    let active = AccountModel(source: source)
    active.signIn(); #expect(active.choosingSignInMethod); #expect(await source.calls.isEmpty)
    active.chooseSignInMethod("invalid"); #expect(active.choosingSignInMethod); #expect(await source.calls.isEmpty)
    active.chooseSignInMethod("second"); await active.waitForSignIn()
    #expect(await source.calls == ["second"]); #expect(active.account?.id == "second")
    #expect(!active.choosingSignInMethod)
}

@Test @MainActor func cancellingMethodPickerDoesNotStartOrReplaceAccount() async {
    let source = MethodAccountFixture()
    let other = AccountModel(source: source)
    other.signIn(); other.cancelSignIn(); other.chooseSignInMethod("first")
    await other.waitForSignIn()
    #expect(await source.calls.isEmpty); #expect(other.account == nil); #expect(!other.choosingSignInMethod)
}


private actor ResettableAccountFixture: AccountCacheResetSource {
    nonisolated let displayName = "测试账号"
    var resets = 0
    var signedIn = true
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func account() -> SourceAccount? { signedIn ? SourceAccount(id: "user", name: "测试账号") : nil }
    func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) -> SourceAccount { signedIn = true; return account()! }
    func signOut() { signedIn = false }
    func supportsAccountCacheReset() -> Bool { true }
    func signOutAndClearCache() { resets += 1; signedIn = false }
}

@Test @MainActor func accountExplicitResetIsSeparateFromOrdinarySignOut() async {
    let source = ResettableAccountFixture(), model = AccountModel(source: source)
    await model.restore()
    #expect(model.canClearLoginCache)
    await model.signOut()
    #expect(await source.resets == 0)
    model.signIn(); await model.waitForSignIn()
    #expect(model.account != nil)
    await model.signOut(clearCache: true)
    #expect(await source.resets == 1)
    #expect(model.account == nil)
    #expect(!model.isSigningOut && model.errorMessage == nil)
}
