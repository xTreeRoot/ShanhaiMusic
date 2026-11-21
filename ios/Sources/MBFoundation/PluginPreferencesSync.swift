import Foundation

private struct PluginPreferencesPayload: Codable { let value: PluginPreferences }

extension PlatformDataClient {
    /// 拉取、在本地合并落盘，再以 ETag 写回。冲突或写入回执丢失时重新读取合并。
    @MainActor func syncPluginPreferences(_ node: PlatformNode,
        load: () async throws -> PluginPreferences,
        merge: (PluginPreferences) async throws -> PluginPreferences,
        stillCurrent: () -> Bool = { true }) async throws {
        func check() throws { try Task.checkCancellation(); guard stillCurrent() else { throw CancellationError() } }
        for _ in 0..<6 {
            try check()
            var remote = PluginPreferences()
            let revision: String
            do {
                let (envelope, tag) = try await get(node, platform: "app", key: "plugins")
                try check()
                guard envelope.format == 1 else { throw PlatformDataFailure.invalid }
                let plain = try PlatformDataCodec.open(envelope, recovery: node.grant.recovery, space: node.grant.space, platform: "app", key: "plugins")
                remote = try JSONDecoder().decode(PluginPreferencesPayload.self, from: plain).value.validated()
                revision = tag
            } catch PlatformDataFailure.http(404, "not_found") { revision = "*" }
            catch where Self.preferencesRetryable(error) { continue }
            try check()
            let merged = try await merge(remote).validated()
            try check()
            if merged == remote {
                let latest = try await load(); try check()
                if latest == merged { return }; continue
            }
            let plain = try JSONEncoder().encode(PluginPreferencesPayload(value: merged))
            let sealed = try PlatformDataCodec.seal(plain, recovery: node.grant.recovery, space: node.grant.space, platform: "app", key: "plugins")
            try check()
            do { try await change(node, platform: "app", key: "plugins", revision: revision, envelope: sealed) }
            catch PlatformDataFailure.http(412, _) { continue }
            catch where Self.preferencesRetryable(error) { continue }
            let latest = try await load(); try check()
            if latest == merged { return }
        }
        throw PlatformDataFailure.http(412, "preferences_busy")
    }
    private static func preferencesRetryable(_ error: any Error) -> Bool {
        if case PlatformDataFailure.http(let status, _) = error { return status == 408 || status == 429 || (500...599).contains(status) }
        guard let url = error as? URLError else { return false }
        return [.timedOut, .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost, .dnsLookupFailed].contains(url.code)
    }
}
