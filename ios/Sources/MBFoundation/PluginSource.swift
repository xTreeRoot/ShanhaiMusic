import Foundation

public actor PluginSource: PlaybackSource, VideoSource, AccountSource, AccountCacheResetSource, SignInMethodSource, InteractionSource, SuggestionSource, PlaybackRecorder {
    public nonisolated let manifest: PluginManifest
    public let runtime: PluginRuntime
    public nonisolated var displayName: String { manifest.displayName }
    public init(manifest: PluginManifest, runtime: PluginRuntime) { self.manifest = manifest; self.runtime = runtime }
    public func supports(_ name: String) async throws -> Bool { try await runtime.supports(name) }
    private var cacheAccount: String?
    private var accountGeneration = 0
    private var offsets: [String: [Int: Int]] = [:]
    private var commentOffsets: [String: [Int: Int]] = [:]
    public func suggestions(_ query: String) async throws -> [String] {
        guard try await supports("suggest") else { return [] }
        return try await runtime.invoke("suggest", arguments: .object(["query": .string(query)])).array.compactMap(\.string)
    }
    public func played(_ track: Track) async {
        guard (try? await supports("played")) == true else { return }
        _ = try? await runtime.invoke("played", arguments: .object(["id": .string(track.sourceTrackID)]))
    }
    public func search(query: String, page: Int) async throws -> TrackPage {
        if page == 1 { offsets[query] = [1: 0] }
        guard let offset = offsets[query]?[page] else { throw PluginFailure.invalid("Search page has no previous cursor") }
        let result = try await runtime.invoke("search", arguments: .object(["query": .string(query), "kind": .string("tracks"), "offset": .number(Double(offset))]))
        let tracks = try await tracks(result["tracks"])
        offsets[query]?[page + 1] = offset + result["tracks"].array.count
        return TrackPage(tracks: tracks, hasMore: result["more"] == .bool(true) && !tracks.isEmpty)
    }
    public func searchVideos(query: String, offset: Int) async throws -> (page: TrackPage, nextOffset: Int) {
        let result = try await runtime.invoke("searchVideos", arguments: .object(["query": .string(query), "offset": .number(Double(offset))]))
        let rawTracks = result["tracks"]
        let count = rawTracks.array.count
        let page = TrackPage(tracks: try await tracks(rawTracks), hasMore: result["more"] == .bool(true) && count > 0)
        return (page, offset + count)
    }
    func catalogInvoke(_ method: String, arguments: PluginJSON = .object([:]), refresh: Bool = false) async throws -> PluginJSON {
        guard case .object(var values) = arguments else { throw PluginFailure.invalid("Catalog arguments must be an object") }
        if refresh { values["refresh"] = .bool(true) }
        let requestArguments = PluginJSON.object(values)
        if cacheAccount == nil {
            do { _ = try await account(); if cacheAccount == nil { cacheAccount = "guest" } }
            catch {
                try Task.checkCancellation()
                // 无法确定账号时不读写个人缓存，公开页面仍按原合同加载。
                return try await runtime.invoke(method, arguments: requestArguments)
            }
        }
        let scope = cacheAccount ?? "guest", generation = accountGeneration
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let key = ResponseCache.key([manifest.id, manifest.version ?? "", scope, method, String(decoding: try encoder.encode(arguments), as: UTF8.self)])
        let runtime = runtime
        let data = try await runtime.catalogCache.data(key: key, lifetime: refresh ? 0 : 6 * 3600) {
            try await self.catalogData(method, arguments: requestArguments, generation: generation)
        }
        guard generation == accountGeneration else { throw CancellationError() }
        return try JSONDecoder().decode(PluginJSON.self, from: data)
    }
    private func catalogData(_ method: String, arguments: PluginJSON, generation: Int) async throws -> Data {
        guard generation == accountGeneration else { throw CancellationError() }
        let value = try await runtime.invoke(method, arguments: arguments)
        guard generation == accountGeneration else { throw CancellationError() }
        return try JSONEncoder().encode(value)
    }
    public func invalidateCatalog() async { await runtime.catalogCache.invalidate() }
    public func mutate(_ method: String, arguments: PluginJSON) async throws {
        _ = try await runtime.invoke(method, arguments: arguments)
        await invalidateCatalog()
    }
    public func home(refresh: Bool = false) async throws -> PluginBrowse {
        guard try await supports("home") else { return PluginBrowse(title: displayName) }
        return try await browseResult(try await catalogInvoke("home", refresh: refresh))
    }
    public func browse(_ page: String, refresh: Bool = false) async throws -> PluginBrowse {
        try await browseResult(catalogInvoke("browse", arguments: .object(["page": .string(page), "offset": .number(0)]), refresh: refresh))
    }
    public func browsePage(_ page: String, offset: Int = 0, refresh: Bool = false) async throws -> (PluginBrowse, Bool, Int) {
        let value = try await catalogInvoke("browse", arguments: .object(["page": .string(page), "offset": .number(Double(offset))]), refresh: refresh)
        var result = try await browseResult(value)
        let count = result.sections.reduce(0) { $0 + $1.tracks.count + $1.entities.count + $1.links.count }
        if count > 0, value["more"] == .bool(true) {
            result.nextBrowse = .init(page: page, offset: offset + count)
        }
        // 单列表 morePage 是旧插件的续页；首页各分区的“更多”仍保留导航语义。
        if result.sections.count == 1 {
            if result.nextBrowse == nil, count > 0, let next = result.sections[0].morePage,
               !next.isEmpty, next != page {
                result.nextBrowse = .init(page: next, offset: 0)
            }
            result.sections[0].morePage = nil
        }
        return (result, result.nextBrowse != nil, count)
    }
    public func audio(track: Track, quality: AudioQuality) async throws -> MediaResource {
        let result = try await runtime.invoke("stream", arguments: .object(["id": .string(track.sourceTrackID), "quality": .string(quality.rawValue)]))
        return try await resource(result)
    }
    public func video(track: Track, quality: AudioQuality) async throws -> MediaResource {
        let result = try await runtime.invoke("video", arguments: .object(["id": .string(track.sourceTrackID), "quality": .string(quality.rawValue)]))
        return try await resource(result)
    }
    public func video(track: Track, qualityID: String) async throws -> MediaResource {
        guard VideoQualityOption.valid(qualityID) else { throw PluginFailure.invalid("Invalid video quality") }
        return try await resource(runtime.invoke("video", arguments: .object(["id": .string(track.sourceTrackID), "quality": .string(qualityID), "network": await MediaNetworkConditions.shared.snapshot()])))
    }
    public func audio(trackID: String) async throws -> MediaResource {
        let result = try await runtime.invoke("stream", arguments: .object(["id": .string(trackID), "quality": .string("")]))
        return try await resource(result)
    }
    public func supportsVideo(track: Track) async -> Bool { (try? await supports("video")) == true }
    public func video(trackID: String) async throws -> MediaResource {
        let result = try await runtime.invoke("video", arguments: .object(["id": .string(trackID), "quality": .string("")]))
        return try await resource(result)
    }
    public func captions(trackID: String) async throws -> CaptionTimeline {
        guard try await supports("lyrics") else { return CaptionTimeline(lines: []) }
        let result = try await runtime.invoke("lyrics", arguments: .object(["id": .string(trackID)]))
        if !result["lines"].array.isEmpty {
            return .pluginLines(result["lines"].array)
        }
        return .lrc(result["lrc"].string ?? "", translation: result["translation"].string ?? "", durationMilliseconds: 0)
    }
    public func account() async throws -> SourceAccount? {
        guard try await supports("account") else { return nil }
        let generation = accountGeneration
        let found = try await account(runtime.invoke("account"))
        guard generation == accountGeneration else { throw CancellationError() }
        let scope = found.map { "user:" + $0.id } ?? "guest"
        if let previous = cacheAccount, previous != scope { accountGeneration += 1 }
        cacheAccount = scope
        return found
    }
    public nonisolated var signInMethods: [SignInMethod] { manifest.signInMethods ?? [] }
    public func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        try await signIn(arguments: .object([:]), prompt: prompt)
    }
    public func signIn(method: String, prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        guard signInMethods.contains(where: { $0.id == method }) else { throw PluginFailure.invalid("Unknown sign-in method") }
        return try await signIn(arguments: .object(["method": .string(method)]), prompt: prompt)
    }
    private func signIn(arguments: PluginJSON, prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        await runtime.setPrompt { value in Task { await prompt(value) } }
        do {
            let value = try await runtime.invoke("signIn", arguments: arguments, timeout: .seconds(300))
            await runtime.setPrompt(nil)
            guard let account = try await account(value) else { throw PluginFailure.execution("Sign-in returned no account") }
            accountGeneration += 1; cacheAccount = "user:" + account.id
            return account
        } catch { await runtime.setPrompt(nil); throw error }
    }
    public func signOut() async throws {
        _ = try await runtime.invoke("signOut")
        accountGeneration += 1; cacheAccount = "guest"
    }
    public func supportsAccountCacheReset() async -> Bool { true }
    public func signOutAndClearCache() async throws {
        accountGeneration += 1; cacheAccount = nil
        try await runtime.clearLoginCache()
        cacheAccount = "guest"; offsets.removeAll(); commentOffsets.removeAll()
        await invalidateCatalog()
    }
    public func capabilities() async -> InteractionCapabilities {
        InteractionCapabilities(likes: (try? await supports("like")) == true,
                                comments: (try? await supports("comments")) == true,
                                posting: (try? await supports("postComment")) == true)
    }
    public func supportsCommentLikes() async -> Bool { (try? await supports("likeComment")) == true }
    public func likeComment(trackID: String, commentID: String, enabled: Bool) async throws {
        _ = try await runtime.invoke("likeComment", arguments: .object(["trackId": .string(trackID), "commentId": .string(commentID), "on": .bool(enabled)]))
    }
    public func interactionSummary(trackID: String) async throws -> TrackInteractionSummary {
        func count(_ value: PluginJSON) -> Int? {
            guard case .number(let value) = value, value.isFinite, value >= 0,
                  value <= 9_007_199_254_740_991, value.rounded(.down) == value else { return nil }
            return Int(value)
        }
        guard try await supports("interactionSummary") else {
            guard try await supports("comments") else { return TrackInteractionSummary() }
            let page = try await runtime.invoke("comments", arguments: .object(["trackId": .string(trackID), "offset": .number(0)]))
            return TrackInteractionSummary(comments: count(page["total"]))
        }
        let value = try await runtime.invoke("interactionSummary", arguments: .object(["trackId": .string(trackID)]))
        return TrackInteractionSummary(likes: count(value["likes"]), comments: count(value["comments"]))
    }
    public func isLiked(trackID: String) async throws -> Bool {
        try await runtime.invoke("isLiked", arguments: .object(["ids": .array([.string(trackID)])]))[trackID] == .bool(true)
    }
    public func like(trackID: String, enabled: Bool) async throws {
        try await mutate("like", arguments: .object(["id": .string(trackID), "on": .bool(enabled)]))
    }
    public func comments(trackID: String, page: Int) async throws -> CommentPage {
        if page == 1 { commentOffsets[trackID] = [1: 0] }
        guard let offset = commentOffsets[trackID]?[page] else { throw PluginFailure.invalid("Comment page has no previous cursor") }
        let result = try await runtime.invoke("comments", arguments: .object(["trackId": .string(trackID), "offset": .number(Double(offset))]))
        commentOffsets[trackID]?[page + 1] = offset + result["comments"].array.count
        return CommentPage(comments: result["comments"].array.compactMap(comment), total: result["total"].integer ?? 0, hasMore: result["more"] == .bool(true) && !result["comments"].array.isEmpty)
    }
    public func postComment(trackID: String, text: String) async throws -> TrackComment {
        let value = try await runtime.invoke("postComment", arguments: .object(["trackId": .string(trackID), "text": .string(text)]))
        guard let result = comment(value) else { throw PluginFailure.execution("Invalid comment response") }
        return result
    }
    private func account(_ value: PluginJSON) async throws -> SourceAccount? {
        guard let id = value["id"].string, let name = value["name"].string else { return nil }
        let url = value["imageUrl"].string.flatMap(URL.init(string:))
        var headers: [String: String] = [:]
        if let url { headers = try await runtime.headers(for: url, kind: "image") }
        return SourceAccount(id: id, name: name, avatarURL: url, page: value["page"].string, avatarHeaders: headers)
    }
    private func comment(_ value: PluginJSON) -> TrackComment? {
        guard let id = value["id"].string, let text = value["text"].string else { return nil }
        var comment = TrackComment(id: id, author: value["author"].string ?? "", avatarURL: value["avatarUrl"].string.flatMap(URL.init(string:)), text: text, date: Date(timeIntervalSince1970: Double(value["time"].integer ?? 0) / 1000), likes: value["likes"].integer ?? 0)
        comment.liked = value["liked"] == .bool(true); return comment
    }
    func tracks(_ values: PluginJSON) async throws -> [Track] {
        var result: [Track] = []
        for value in values.array {
            guard let id = value["id"].string, let title = value["name"].string else { continue }
            let artists = value["artists"].array
            var track = Track(sourceID: manifest.id, sourceTrackID: id, title: title, artist: artists.compactMap { $0["name"].string }.joined(separator: ", "), artworkURL: value["album"]["imageUrl"].string.flatMap(URL.init(string:)), durationMilliseconds: Int64(value["duration"].integer ?? 0), pageURL: value["url"].string.flatMap(URL.init(string:)))
            track.unavailable = value["unavailable"] == .bool(true)
            track.unavailableReason = TrackProblem.declared(value["unavailableReason"].string)
            track.videoWidth = value["video"]["width"].integer
            track.videoHeight = value["video"]["height"].integer
            track.shortVideo = value["video"]["short"] == .bool(true)
            track.resourceType = value["resourceType"].string
            track.resourceURL = value["resourceUrl"].string.flatMap(URL.init(string:)).flatMap { $0.scheme == "https" && $0.host != nil ? $0 : nil }
            track.artistID = artists.first?["id"].string
            if let url = track.artworkURL { track.artworkHeaders = try await runtime.headers(for: url, kind: "image") }
            result.append(track)
        }
        return result
    }
    private func resource(_ value: PluginJSON) async throws -> MediaResource {
        guard let address = value["url"].string, !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw TrackProblem.noSource }
        guard let url = URL(string: address), ["https", "http"].contains(url.scheme ?? ""), url.host != nil, url.user == nil else { throw PluginFailure.execution("Invalid media URL") }
        var headers = try await runtime.headers(for: url, kind: "audio")
        if case .object(let own) = value["headers"] {
            for (name, value) in own { if let text = value.string { headers = headers.filter { $0.key.lowercased() != name.lowercased() }; headers[name] = text } }
        }
        var seen: Set<String> = []
        let qualities = value["videoQualities"].array.prefix(32).compactMap { item -> VideoQualityOption? in
            guard let id = item["id"].string, let label = item["label"].string,
                  let option = VideoQualityOption(id: id, label: label), seen.insert(id).inserted else { return nil }
            return option
        }
        let actual = value["videoQuality"].string.flatMap { VideoQualityOption.valid($0) ? $0 : nil }
        if value["encryption"] != .null {
            guard value["type"] == .string("m4a") else { throw PluginFailure.execution("Unsupported encrypted audio container") }
            let local = try await runtime.prepareCENCAudio(url: url, headers: headers, encryption: value["encryption"])
            return MediaResource(url: local, headers: [:], fileExtension: "m4a")
        }
        return MediaResource(url: url, headers: headers, fileExtension: value["type"].string ?? "m4a", videoQuality: actual, videoQualities: qualities)
    }
    func browseResult(_ value: PluginJSON) async throws -> PluginBrowse {
        var sections: [PluginBrowse.Section] = []
        for (index, section) in value["sections"].array.enumerated() {
            var links: [PluginBrowse.Link] = []
            for link in section["links"].array {
                guard let title = link["title"].string, let page = link["page"].string else { continue }
                let url = link["imageUrl"].string.flatMap(URL.init(string:))
                var headers: [String: String] = [:]
                if let url { headers = try await runtime.headers(for: url, kind: "image") }
                links.append(PluginBrowse.Link(title: title, subtitle: link["subtitle"].string ?? "", page: page, imageURL: url, artworkHeaders: headers))
            }
            sections.append(PluginBrowse.Section(id: String(index), entities: try await entities(section), empty: section["empty"].string ?? "", trackLayout: section["trackLayout"].string ?? "", title: section["title"].string ?? "", tracks: try await tracks(section["tracks"]), links: links, morePage: section["morePage"].string))
        }
        var tabPages: Set<String> = []
        let tabs = value["tabs"].array.prefix(32).compactMap { item -> PluginBrowse.Link? in
            guard let title = item["title"].string, let page = item["page"].string, !title.isEmpty, !page.isEmpty, tabPages.insert(page).inserted else { return nil }
            return PluginBrowse.Link(title: title, subtitle: "", page: page)
        }
        return PluginBrowse(title: value["title"].string ?? displayName, subtitle: value["subtitle"].string ?? "", description: value["description"].string ?? "", sections: sections, tabs: tabs, selectedTab: value["selectedTab"].string)
    }
}

public struct PluginBrowse: Sendable {
    public struct Cursor: Hashable, Sendable {
        public let page: String
        public let offset: Int
        public init(page: String, offset: Int) { self.page = page; self.offset = offset }
    }
    public struct Link: Identifiable, Sendable { public let title: String; public let subtitle: String; public let page: String; public var imageURL: URL? = nil; public var artworkHeaders: [String: String] = [:]; public var id: String { page } }
    public struct Section: Identifiable, Sendable { public let id: String; public var entities: [PluginEntity] = []; public var empty = ""; public var trackLayout = ""; public let title: String; public let tracks: [Track]; public let links: [Link]; public var morePage: String?; public var moreCollection: String? = nil }
    public let title: String
    public var subtitle = ""
    public var description = ""
    public var sections: [Section] = []
    public var tabs: [Link] = []
    public var selectedTab: String? = nil
    public var nextBrowse: Cursor? = nil
}

public extension PluginBrowse {
    /// 旧式续页可能重复首屏摘要，按资源身份追加，保持原顺序和图片上下文。
    func appending(_ page: Self, offset: Int) -> Self {
        var result = self
        var tracks = Set(sections.flatMap(\.tracks).map(\.id))
        var entities = Set(sections.flatMap(\.entities).map(\.id))
        var links = Set(sections.flatMap(\.links).map(\.id))
        result.sections += page.sections.enumerated().map { index, section in
            Section(id: "\(offset)-\(index)", entities: section.entities.filter { entities.insert($0.id).inserted },
                    empty: section.empty, trackLayout: section.trackLayout, title: section.title,
                    tracks: section.tracks.filter { tracks.insert($0.id).inserted },
                    links: section.links.filter { links.insert($0.id).inserted }, morePage: section.morePage,
                    moreCollection: section.moreCollection)
        }
        return result
    }
    func artworkTracks(sourceID: String) -> [Track] {
        sections.flatMap { section in
            section.tracks + section.entities.map { $0.artworkTrack(sourceID: sourceID) } + section.links.map { link in
                var track = Track(sourceID: sourceID, sourceTrackID: link.page, title: link.title, artist: "", artworkURL: link.imageURL, durationMilliseconds: 0)
                track.artworkHeaders = link.artworkHeaders; return track
            }
        }
    }
}
