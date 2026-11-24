import Foundation

public struct DisabledMusicSource: MusicSource {
    public let displayName = "山海"
    public init() {}
    public func search(query: String, page: Int) async throws -> TrackPage { throw PluginFailure.invalid(AppLocalization.text("请安装并启用插件")) }
}
