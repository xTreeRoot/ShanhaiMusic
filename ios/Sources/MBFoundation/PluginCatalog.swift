import Foundation

func pluginPlainTitle(_ title: String) -> String {
    title.replacingOccurrences(of: #"(?is)<em\s+class\s*=\s*(?:"keyword"|'keyword'|keyword)\s*>(.*?)</em\s*>"#,
                               with: "$1", options: .regularExpression)
}

func catalogNextOffset(_ value: PluginJSON, offset: Int, count: Int) -> Int? {
    guard value["more"] == .bool(true) else { return nil }
    let (fallback, overflow) = offset.addingReportingOverflow(count)
    guard let next = value["nextOffset"].integer ?? (overflow ? nil : fallback) else { return nil }
    return next > offset ? next : nil
}

public struct PluginEntity: Identifiable, Sendable {
    public enum Kind: String, CaseIterable, Sendable { case album, artist, playlist, show, station }
    public let kind: Kind
    public let sourceID: String
    public let title: String
    public let subtitle: String
    public let value: PluginJSON
    public var artworkHeaders: [String: String] = [:]
    public func artworkTrack(sourceID: String) -> Track {
        var track = Track(sourceID: sourceID, sourceTrackID: id, title: title, artist: "", artworkURL: value["imageUrl"].string.flatMap(URL.init(string:)), durationMilliseconds: 0)
        track.artworkHeaders = artworkHeaders
        return track
    }
    public var id: String { kind.rawValue + ":" + sourceID }
    public static func creator(id: String, name: String) -> Self {
        Self(kind: .artist, sourceID: id, title: name, subtitle: "", value: .object(["id": .string(id), "name": .string(name)]))
    }
}

extension PluginSource {
    func entities(_ section: PluginJSON) async throws -> [PluginEntity] {
        var result: [PluginEntity] = PluginEntity.Kind.allCases.flatMap { kind in
            section[kind.rawValue + "s"].array.compactMap { value in
                guard let id = value["id"].string, let title = value["name"].string else { return nil }
                return PluginEntity(kind: kind, sourceID: id, title: pluginPlainTitle(title), subtitle: value["description"].string ?? value["publisher"].string ?? value["artists"].array.compactMap { $0["name"].string.map(pluginPlainTitle) }.joined(separator: ", "), value: value)
            }
        }
        for index in result.indices {
            if let url = result[index].value["imageUrl"].string.flatMap(URL.init(string:)) {
                result[index].artworkHeaders = try await runtime.headers(for: url, kind: "image")
            }
        }
        return result
    }
    public func collection(_ name: String, offset: Int = 0) async throws -> (PluginBrowse, Bool, Int) {
        let value = try await catalogInvoke(name, arguments: .object(["offset": .number(Double(offset))]))
        let section: PluginJSON
        switch name {
        case "playlists", "shows", "stations": section = .object([name: value])
        case "recent": section = .object(["tracks": value])
        default: section = value
        }
        var content = try await browseResult(.object(["sections": .array([section])]))
        let count = section["tracks"].array.count + section["albums"].array.count + section["playlists"].array.count + section["shows"].array.count + section["stations"].array.count
        content.nextOffset = catalogNextOffset(value, offset: offset, count: count)
        return (content, content.nextOffset != nil, count)
    }
    public func entity(_ entity: PluginEntity, offset: Int = 0) async throws -> (PluginBrowse, Bool, Int) {
        let method = entity.kind == .station ? "stationNext" : entity.kind.rawValue
        let args: PluginJSON = .object(["id": .string(entity.sourceID), "offset": .number(Double(offset))])
        let value = try await (entity.kind == .station ? runtime.invoke(method, arguments: args) : catalogInvoke(method, arguments: args))
        let section: PluginJSON
        if entity.kind == .station { section = .object(["tracks": value]) }
        else if entity.kind == .show { section = .object(["tracks": value["episodes"]]) }
        else { section = value }
        let details = value[entity.kind.rawValue]
        var content = try await browseResult(.object(["title": details["name"].string.map { .string(pluginPlainTitle($0)) } ?? .string(entity.title), "subtitle": .string(entity.subtitle), "description": details["description"], "sections": .array([section])]))
        let count = section["tracks"].array.count
        content.nextOffset = catalogNextOffset(value, offset: offset, count: count)
        return (content, entity.kind == .station || content.nextOffset != nil, count)
    }
    public func change(_ method: String, entity: PluginEntity, on: Bool) async throws {
        try await mutate(method, arguments: .object(["id": .string(entity.sourceID), "on": .bool(on)]))
    }
    public func searchCatalog(_ query: String, kind: String, offset: Int) async throws -> (PluginBrowse, Bool, Int) {
        let value = try await runtime.invoke("search", arguments: .object(["query": .string(query), "kind": .string(kind), "offset": .number(Double(offset))]))
        var content = try await browseResult(.object(["title": .string(query), "sections": .array([value])]))
        let count = value[kind].array.count
        content.nextOffset = catalogNextOffset(value, offset: offset, count: count)
        return (content, content.nextOffset != nil, count)
    }
}
