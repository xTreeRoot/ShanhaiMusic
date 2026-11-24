import Foundation
import Observation

public enum AppAppearance: String, Codable, CaseIterable, Identifiable, Sendable {
    case system, light, dark
    public var id: String { rawValue }
    public var title: String { switch self { case .system: "跟随系统"; case .light: "浅色"; case .dark: "深色" } }
}

public struct AppPreferences: Codable, Equatable, Sendable {
    public var selectedSourceID: String? = nil
    public var appearance = AppAppearance.system
    public var inAppVideoWindow = true
    public var systemVideoWindow = true
    public var pauseOtherSourcesOnSwitch = false
    public var backgroundOpacity: Double? = nil
    public var backgroundStyle = BackgroundSkinStyle()
    public var volume = 0.8
    public var cacheMegabytes = 2_048
    public var searchHistory: [String] = []
    public var playerStyle = PlayerVisualStyle.cover
    public var lyricStyle = LyricVisualStyle.flow
    public var normalization = AudioNormalization.standard
    public var language = AppLanguage.system
    public var quality = AudioQuality.high
    public var videoQuality = "auto"
    public var smoke = false
    public var fog = false
    public var growFromCover = false
    public var hotComments = false
    public var stageSparks = false
    public var lyricsStage = false
    public var automaticUpdateChecks = true
    public var nowPlayingLyrics = true
    public init() {}
    private enum CodingKeys: String, CodingKey { case pauseOtherSourcesOnSwitch, videoQuality, inAppVideoWindow, systemVideoWindow, backgroundOpacity, backgroundStyle, automaticUpdateChecks, selectedSourceID, appearance, volume, cacheMegabytes, searchHistory, playerStyle, lyricStyle, normalization, language, quality, stageSparks, lyricsStage, nowPlayingLyrics, smoke, fog, growFromCover, hotComments }
    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        inAppVideoWindow = try values.decodeIfPresent(Bool.self, forKey: .inAppVideoWindow) ?? true
        systemVideoWindow = try values.decodeIfPresent(Bool.self, forKey: .systemVideoWindow) ?? true
        pauseOtherSourcesOnSwitch = try values.decodeIfPresent(Bool.self, forKey: .pauseOtherSourcesOnSwitch) ?? false
        backgroundOpacity = try values.decodeIfPresent(Double.self, forKey: .backgroundOpacity).map { min(1, max(0, $0)) }
        backgroundStyle = try values.decodeIfPresent(BackgroundSkinStyle.self, forKey: .backgroundStyle) ?? BackgroundSkinStyle()
        automaticUpdateChecks = try values.decodeIfPresent(Bool.self, forKey: .automaticUpdateChecks) ?? true
        selectedSourceID = try values.decodeIfPresent(String.self, forKey: .selectedSourceID)
        appearance = try values.decodeIfPresent(AppAppearance.self, forKey: .appearance) ?? .system
        volume = min(1, max(0, try values.decodeIfPresent(Double.self, forKey: .volume) ?? 0.8))
        cacheMegabytes = min(50_000, max(0, try values.decodeIfPresent(Int.self, forKey: .cacheMegabytes) ?? 2_048))
        searchHistory = Array((try values.decodeIfPresent([String].self, forKey: .searchHistory) ?? []).prefix(20))
        playerStyle = try values.decodeIfPresent(PlayerVisualStyle.self, forKey: .playerStyle) ?? .cover
        lyricStyle = try values.decodeIfPresent(LyricVisualStyle.self, forKey: .lyricStyle) ?? .flow
        normalization = try values.decodeIfPresent(AudioNormalization.self, forKey: .normalization) ?? .standard
        smoke = try values.decodeIfPresent(Bool.self, forKey: .smoke) ?? false
        fog = try values.decodeIfPresent(Bool.self, forKey: .fog) ?? false
        growFromCover = try values.decodeIfPresent(Bool.self, forKey: .growFromCover) ?? false
        hotComments = try values.decodeIfPresent(Bool.self, forKey: .hotComments) ?? false
        stageSparks = try values.decodeIfPresent(Bool.self, forKey: .stageSparks) ?? false
        lyricsStage = try values.decodeIfPresent(Bool.self, forKey: .lyricsStage) ?? false
        nowPlayingLyrics = try values.decodeIfPresent(Bool.self, forKey: .nowPlayingLyrics) ?? true
        quality = try values.decodeIfPresent(AudioQuality.self, forKey: .quality) ?? .high
        let savedVideoQuality = try values.decodeIfPresent(String.self, forKey: .videoQuality) ?? "auto"
        videoQuality = VideoQualityOption.valid(savedVideoQuality) ? savedVideoQuality : "auto"
        language = try values.decodeIfPresent(AppLanguage.self, forKey: .language) ?? .system
    }
}

@MainActor @Observable
public final class PreferencesModel {
    public var values = AppPreferences()
    public private(set) var isRestored = false
    public private(set) var errorMessage: String?
    @ObservationIgnored private let store: AppPersistence
    @ObservationIgnored private let media: MediaLibrary
    @ObservationIgnored private var writing: Task<Void, Never>?
    @ObservationIgnored private var revision = 0
    public init(store: AppPersistence = .shared, media: MediaLibrary = .shared) { self.store = store; self.media = media }
    public func restore() async {
        do {
            if let saved = try await store.loadPreferences() { values = saved }
            try await media.setCacheLimit(megabytes: values.cacheMegabytes)
            isRestored = true
        } catch { errorMessage = error.localizedDescription }
    }
    public func recordSearch(_ query: String) {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        values.searchHistory.removeAll { $0 == query }
        values.searchHistory.insert(query, at: 0)
        values.searchHistory = Array(values.searchHistory.prefix(20))
        save()
    }
    public func save() {
        guard isRestored else { return }
        revision += 1
        guard writing == nil else { return }
        writing = Task {
            while !Task.isCancelled {
                let current = revision, snapshot = values
                do {
                    try await store.savePreferences(snapshot)
                    try await media.setCacheLimit(megabytes: snapshot.cacheMegabytes)
                    errorMessage = nil
                } catch { errorMessage = error.localizedDescription }
                if current == revision { break }
            }
            writing = nil
        }
    }
    public func flush() async { save(); await writing?.value }
}
