import Foundation
import Testing
@testable import MBFoundation

@Test func browsePagesContinueAtTwentyOneAndKeepFirstSectionIdentity() throws {
    let tracks = (1...23).map { Track(sourceID: "fixture", sourceTrackID: String($0), title: "作品 \($0)", artist: "") }
    let previous = PluginBrowse(title: "作者", sections: [
        .init(id: "0", empty: "暂无作品", title: "视频", tracks: Array(tracks.prefix(20)), links: [])
    ])
    let creator = PluginBrowse.Link(title: "关注的作者", subtitle: "简介", page: "creator:1",
                                    imageURL: URL(string: "https://example.test/avatar.png"))
    let second = PluginBrowse(title: "作者第 2 页", sections: [
        .init(id: "0", title: "视频", tracks: Array(tracks[19...21]), links: [creator])
    ])
    let third = PluginBrowse(title: "作者第 3 页", sections: [
        .init(id: "0", title: "第三页", tracks: Array(tracks.suffix(2)), links: [creator])
    ])
    let combined = previous.appending(second, offset: 20).appending(third, offset: 23)
    #expect(combined.title == "作者")
    #expect(combined.sections.map(\.title) == ["视频"])
    let section = try #require(combined.sections.first)
    #expect(section.id == "0")
    #expect(section.empty == "暂无作品")
    #expect(section.tracks == tracks)
    #expect(section.tracks[20].sourceTrackID == "21")
    #expect(section.links.map(\.imageURL) == [creator.imageURL])
}

@Test func browsePagesKeepIndependentSectionsAndDeduplicateWithinEachSection() throws {
    let first = Track(sourceID: "fixture", sourceTrackID: "first", title: "First", artist: "")
    let second = Track(sourceID: "fixture", sourceTrackID: "second", title: "Second", artist: "")
    let previous = PluginBrowse(title: "Catalog", sections: [
        .init(id: "0", title: "Videos", tracks: [first], links: []),
        .init(id: "1", title: "Artists", tracks: [], links: [])
    ])
    let next = PluginBrowse(title: "Catalog", sections: [
        .init(id: "0", trackLayout: "videos", title: "Videos", tracks: [first], links: []),
        .init(id: "1", title: "Videos", tracks: [first, second], links: [])
    ])
    let sections = previous.appending(next, offset: 1).sections
    #expect(sections.map(\.title) == ["Videos", "Artists", "Videos"])
    #expect(sections.map(\.trackLayout) == ["", "", "videos"])
    #expect(sections.map(\.tracks) == [[first, second], [], [first]])
    #expect(Set(sections.map(\.id)).count == 3)
}
