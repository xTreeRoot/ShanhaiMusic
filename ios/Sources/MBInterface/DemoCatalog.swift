import MBFoundation
import CoreGraphics
import ImageIO
import Foundation

/// 仅供 Preview 和显式 --demo 启动；原创示例无媒体地址，不冒充平台结果。
public struct DemoSource: DiscoverySource, AccountSource, AccountCacheResetSource, SignInMethodSource, LibrarySource, InteractionSource {
    private let accountState = DemoAccountState()
    public let displayName = "本地示例"
    public init() {}

    public func search(query: String, page: Int) async throws -> TrackPage {
        if query == "error" { throw DemoFailure.network }
        if query == "empty" { return TrackPage(tracks: [], hasMore: false) }
        if query == "loading" { try await Task.sleep(for: .seconds(120)) }
        if ProcessInfo.processInfo.arguments.contains("--demo-pagination") {
            return TrackPage(tracks: paginationTracks(page), hasMore: page == 1)
        }
        return TrackPage(tracks: page == 1 ? DemoCatalog.tracks : [], hasMore: false)
    }

    public func home() async throws -> [DiscoverySection] {
        if ProcessInfo.processInfo.arguments.contains("--demo-error") { throw DemoFailure.network }
        if ProcessInfo.processInfo.arguments.contains("--demo-loading") { try await Task.sleep(for: .seconds(120)) }
        return DiscoveryCategory.allCases.map { DiscoverySection(category: $0, tracks: DemoCatalog.tracks) }
    }
    public func discovery(_ category: DiscoveryCategory) async throws -> DiscoverySection {
        DiscoverySection(category: category, tracks: DemoCatalog.tracks, subtitle: category == .chart ? "第 1 周 · 界面示例" : nil)
    }
    public func account() async throws -> SourceAccount? { await accountState.current() }
    public func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        await prompt(SignInPrompt(qrURL: URL(string: "https://example.test/mb-demo-login")!, message: "界面演示二维码，无需扫码"))
        try await Task.sleep(for: .seconds(120)); throw CancellationError()
    }
    public var signInMethods: [SignInMethod] {
        ProcessInfo.processInfo.arguments.contains("--demo-sign-in-methods") ? [SignInMethod(id: "first", label: "第一种扫码"), SignInMethod(id: "second", label: "第二种扫码")] : []
    }
    public func signIn(method: String, prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        guard let option = signInMethods.first(where: { $0.id == method }) else { throw DemoFailure.network }
        await prompt(SignInPrompt(qrURL: URL(string: "https://example.test/mb-demo-login/" + method)!, message: "已选择" + option.label))
        try await Task.sleep(for: .seconds(120)); throw CancellationError()
    }
    public func signOut() async throws { await accountState.signOut() }
    public func supportsAccountCacheReset() async -> Bool { true }
    public func signOutAndClearCache() async throws { await accountState.signOut() }
    public func library(_ route: LibraryRoute, cursor: String?) async throws -> LibraryPage {
        if ProcessInfo.processInfo.arguments.contains("--demo-pagination"), route == .liked {
            return LibraryPage(title: route.title, tracks: paginationTracks(cursor == nil ? 1 : 2), nextCursor: cursor == nil ? "2" : nil)
        }
        switch route {
        case .favorites: return LibraryPage(title: route.title, links: [LibraryLink(id: "folder", title: "音乐收藏 · 示例", subtitle: "3 个视频", route: .folder("folder"))])
        case .following: return LibraryPage(title: route.title, links: [LibraryLink(id: "up", title: "示例 UP 主", subtitle: "关注列表 · 演示数据", route: .creator("up"))])
        default: return LibraryPage(title: route.title, tracks: DemoCatalog.tracks)
        }
    }
    public func interactionSummary(trackID: String) async throws -> TrackInteractionSummary { TrackInteractionSummary(likes: 128, comments: 2) }
    public func isLiked(trackID: String) async throws -> Bool { await accountState.liked(trackID) }
    public func like(trackID: String, enabled: Bool) async throws { await accountState.like(trackID, enabled) }
    public func comments(trackID: String, page: Int) async throws -> CommentPage {
        CommentPage(comments: page == 1 ? DemoCatalog.comments : [], total: 2, hasMore: false)
    }
    public func postComment(trackID: String, text: String) async throws -> TrackComment {
        TrackComment(id: UUID().uuidString, author: "界面示例用户", text: text, date: Date(), likes: 0)
    }

    private func paginationTracks(_ page: Int) -> [Track] {
        guard page <= 2 else { return [] }
        return (page == 1 ? 1...18 : 19...21).map { index in
            Track(sourceID: "demo", sourceTrackID: "page-\(index)", title: "分页示例 \(index)", artist: "MB 界面示例", durationMilliseconds: 180_000)
        }
    }

}

private actor DemoAccountState {
    private var signedIn = ProcessInfo.processInfo.arguments.contains("--demo-account")
    private var likes: Set<String> = []
    func current() -> SourceAccount? { signedIn ? SourceAccount(id: "preview", name: "界面示例用户") : nil }
    func signOut() { signedIn = false }
    func liked(_ id: String) -> Bool { likes.contains(id) }
    func like(_ id: String, _ enabled: Bool) { if enabled { likes.insert(id) } else { likes.remove(id) } }
}

enum DemoCatalog {
    static let comments = [
        TrackComment(id: "one", author: "示例听众", text: "慢下来，听见晚风里的片刻。", date: Date(timeIntervalSince1970: 1_794_960_000), likes: 128),
        TrackComment(id: "two", author: "山海界面示例", text: "这是供界面验收使用的演示评论。", date: Date(timeIntervalSince1970: 1_794_960_000), likes: 36),
    ]
    static let tracks = [
        Track(sourceID: "demo", sourceTrackID: "night", title: "晚风里的片刻", artist: "山海界面示例", durationMilliseconds: 180_000),
        Track(sourceID: "demo", sourceTrackID: "city", title: "城市慢下来", artist: "山海界面示例", durationMilliseconds: 210_000),
        Track(sourceID: "demo", sourceTrackID: "rain", title: "雨落在窗沿", artist: "山海界面示例", durationMilliseconds: 160_000),
    ].map { original in var track = original; track.artistID = "preview"; return track }
    static let timeline = CaptionTimeline(lines: [
        CaptionLine(startMilliseconds: 0, endMilliseconds: 4_000, text: "让晚风经过耳边"),
        CaptionLine(startMilliseconds: 5_000, endMilliseconds: 9_000, text: "把今天的喧闹放远"),
        CaptionLine(startMilliseconds: 10_000, endMilliseconds: 14_000, text: "留一点安静的时间"),
        CaptionLine(startMilliseconds: 15_000, endMilliseconds: 20_000, text: "听见下一段旋律"),
    ])
}

/// 演示资料与实际 App 沙盒资料库隔离；测试歌曲为程序生成的静音 WAV。
@MainActor final class DemoLibrary {
    var pluginDirectory: URL { directory.appendingPathComponent("plugins") }
    var skinDirectory: URL { directory.appendingPathComponent("background-skin") }
    let media: MediaLibrary
    let store: LocalLibraryStore
    private let directory: URL
    private var prepared = false
    init() {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("MB-Preview-\(UUID())")
        media = MediaLibrary(directory: directory.appendingPathComponent("media"))
        store = LocalLibraryStore(directory: directory)
    }
    func pluginFixture(includeTrackProblems: Bool = false, refreshFixture: Bool = false, filterFixture: Bool = false) throws -> URL {
        let folder = directory.appendingPathComponent("plugin")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var manifest = #"{"id":"fixture","name":"示例插件","version":"1.0.0","minAppVersion":"0.1.0","settings":[{"key":"server","label":"服务地址","type":"text"},{"key":"enabled","label":"启用示例","type":"boolean"}],"permissions":{"network":["media.example.test"]},"navigation":[{"title":"示例曲目","page":"duplicate"},{"title":"示例分页","page":"more"}]}"#
        if refreshFixture { manifest = #"{"id":"fixture","name":"示例插件","version":"1.0.0","navigation":[{"title":"独立栏目","page":"extra"}]}"# }
        if filterFixture { manifest = #"{"id":"fixture","name":"示例插件","version":"1.0.0","navigation":[{"title":"订阅","page":"subscriptions","placement":"personal"},{"title":"我的频道","page":"channel","placement":"personal"},{"title":"点赞","builtin":"liked","placement":"personal"},{"title":"音乐专区","page":"music","placement":"personal"}]}"# }
        if ProcessInfo.processInfo.arguments.contains("--discovery-sections-fixture") {
            manifest = #"{"id":"fixture","name":"示例插件","version":"1.0.0","discovery":[{"id":"one","title":"第一栏目","page":"one"},{"id":"two","title":"第二栏目","page":"two"},{"id":"three","title":"第三栏目","page":"three"}]}"#
        }
        let shelfFixture = ProcessInfo.processInfo.arguments.contains("--shelf-layout-fixture")
        if shelfFixture { manifest = #"{"id":"fixture","name":"示例插件","version":"1.0.0","discovery":[{"id":"small","title":"少量资源","page":"shelf"},{"id":"people","title":"我的关注","page":"people"}]}"# }
        try manifest.write(to: folder.appendingPathComponent("plugin.json"), atomically: true, encoding: .utf8)
        let paginationFixture = ProcessInfo.processInfo.arguments.contains("--catalog-pagination-fixture")
        let refreshScript = "const filterFixture = \(filterFixture), refreshFixture = \(refreshFixture), paginationFixture = \(paginationFixture);"
        let script = refreshScript + (includeTrackProblems ? "const extraTracks = [{id:'vip',name:'会员示例曲目',unavailableReason:'vip'}, {id:'missing',name:'无音源示例曲目'}];\n" : "const extraTracks = [];\n") + #"""
        let listName='示例歌单', listTracks=[], homeCalls=0, extraCalls=0, savedHome, savedExtra;
        const track = {id:'sample',name:'插件示例歌曲',artists:[{id:'artist',name:'示例作者'}],album:{},duration:180000};
        const tabs=[{title:'全部',page:'all'},{title:'音乐',page:'music'},{title:'游戏',page:'games'}];
        export function home({refresh}={}){if(filterFixture)return {tabs,selectedTab:'all',sections:[{title:'推荐',tracks:[{...track,name:'全部内容'}]}]};if(refreshFixture){if(!savedHome||refresh)savedHome={sections:[{title:'刷新首页',tracks:[{...track,name:'首页第'+(++homeCalls)+'次'}]}]};return savedHome;} return {title:'示例插件首页',sections:[{title:'示例曲目',tracks:[track,...extraTracks],links:[{title:'更多示例',page:'more'}],albums:[{id:'al',name:'示例专辑'}],artists:[{id:'ar',name:'示例作者'}],playlists:[{id:'pl',name:listName,mine:true}],shows:[{id:'sh',name:'示例播客'}],stations:[{id:'st',name:'示例电台'}]}]};}
        export function browse({page,offset=0,refresh}) {
          if (paginationFixture && (page === 'duplicate' || page === 'duplicate:2')) {
            const first = page === 'duplicate';
            const tracks = Array.from({length:first?20:7}, (_,i) => {
              const number = i + (first ? 1 : 20);
              return {...track,id:'work-'+number,name:number===21?'《无名的人》致所有顶天立地却平凡普通':'分页作品 '+number};
            });
            return {title:'示例分页',sections:[{title:'分页作品',tracks,morePage:first?'duplicate:2':undefined}]};
          }
          if(['one','two','three'].includes(page))return {sections:[{tracks:Array.from({length:6},(_,i)=>({...track,id:page+i,name:page+'资源'+i}))}]};
          if(filterFixture)return {tabs,selectedTab:page,sections:[{title:page,tracks:[{...track,id:page+offset,name:page+'内容第'+(++extraCalls)+'次'}]}],more:offset===0};
          if(refreshFixture){if(!savedExtra||refresh)savedExtra={sections:[{tracks:[{...track,name:'栏目第'+(++extraCalls)+'次'}]}]};return savedExtra;}
          return {title:page==='duplicate'?'相同导航完整内容':'示例分页',sections:[{title:'分页曲目',tracks:[track]}]};
        }
        export function search({query,kind}){return {tracks:kind==='tracks'?[{...track,name:query+' · 插件结果'}]:[],albums:kind==='albums'?[{id:'al',name:'示例专辑'}]:[],artists:kind==='artists'?[{id:'ar',name:'示例作者'}]:[],playlists:kind==='playlists'?[{id:'pl',name:listName,mine:true}]:[],shows:kind==='shows'?[{id:'sh',name:'示例播客'}]:[],more:false};}
        export function searchVideos({query,offset}){return offset===0?{tracks:[{...track,id:'sample-video',name:query+' · 视频结果'},{}],more:true}:offset===2?{tracks:[{...track,id:'sample-video-next',name:query+' · 更多视频'}],more:false}:{tracks:[],more:false};}
        export function album(){return {album:{id:'al',name:'示例专辑',description:'专辑详情 · 演示数据'},tracks:[track]};}
        export function artist(){return {artist:{id:'ar',name:'示例作者'},tracks:[track]};}
        export function playlist(){return {playlist:{id:'pl',name:listName,mine:true},tracks:listTracks};}
        export function show(){return {show:{id:'sh',name:'示例播客'},episodes:[track]};}
        export function stationNext(){return [track];}
        export function playlists(){return [{id:'pl',name:listName,mine:true}];}
        export function liked(){return {tracks:[track]};}
        export function recent(){return [track];}
        export function savedAlbums(){return {albums:[{id:'al',name:'示例专辑'}]};}
        export function shows(){return [{id:'sh',name:'示例播客'}];}
        export function stations(){return [{id:'st',name:'示例电台'}];}
        export function createPlaylist({name}){listName=name;return {id:'pl',name,mine:true};}
        export function renamePlaylist({name}){listName=name;}
        export function deletePlaylist(){listTracks=[];}
        export function addToPlaylist(){listTracks=[{...track,name:'music · 插件结果'}];}
        export function removeFromPlaylist(){listTracks=[];}
        export function movePlaylistTrack(){}
        export function saveAlbum(){}
        export function followArtist(){}
        export function subscribeShow(){}
        export function savePlaylist(){}
        export function stream({id}){return id==='missing'?{url:''}:{url:'https://media.example.test/sample.mp3',type:'mp3'};}
        """#
        if shelfFixture {
            let cover = folder.appendingPathComponent("avatar.png")
            if let image = DemoCatalog.backgroundImage,
               let output = CGImageDestinationCreateWithURL(cover as CFURL, "public.png" as CFString, 1, nil) {
                CGImageDestinationAddImage(output, image, nil); CGImageDestinationFinalize(output)
            }
            let address = String(data: try JSONEncoder().encode(cover.absoluteString), encoding: .utf8)!
            let fixture = """
            const imageUrl = \(address);
            export function artist({id}) { return {artist:{id,name:'插件作者主页'},tracks:[{id:'creator-song',name:'作者的另一首歌'}]}; }
            export function browse({page}) {
              if(page.startsWith('people')) {
                const start=page==='people'?1:19;
                return {title:'我的关注',sections:[{links:Array.from({length:start===1?18:3},(_,i)=>({title:'关注作者'+(start+i),subtitle:'这是作者的完整介绍，文字需要使用右侧剩余宽度，抵达右边后再换行。第二行继续显示介绍内容。',page:'profile:'+(start+i),imageUrl})),morePage:start===1?'people:2':undefined}]};
              }
              if(page.startsWith('profile:'))return {title:'作者主页',description:page,sections:[]};
              const count=page==='shelf'?2:Number(page.split(':')[1]);
              return {tabs:[1,2,3,8].map(n=>({title:n+'项',page:'shelf:'+n})),selectedTab:'shelf:'+count,sections:[{tracks:Array.from({length:count},(_,i)=>({id:'card'+i,name:'封面资源'+(i+1),artists:[{id:'creator',name:'示例歌手'}],album:{imageUrl}}))}]};
            }
            """
            try fixture.write(to: folder.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
        } else {
        try script.write(to: folder.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
        }
        return folder
    }

    func prepare() async {
        guard !prepared else { return }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let file = directory.appendingPathComponent("preview.wav")
            var bytes = Data()
            func append<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) } }
            let count: UInt32 = 88_200
            bytes.append(Data("RIFF".utf8)); append(count + 36); bytes.append(Data("WAVEfmt ".utf8))
            append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(44_100)); append(UInt32(88_200)); append(UInt16(2)); append(UInt16(16))
            bytes.append(Data("data".utf8)); append(count); bytes.append(Data(repeating: 0, count: Int(count)))
            try bytes.write(to: file)
            var tracks: [Track] = []
            for (index, original) in DemoCatalog.tracks.enumerated() {
                var track = Track(sourceID: "local", sourceTrackID: original.sourceTrackID, title: original.title, artist: "山海界面示例", durationMilliseconds: 1_000)
                track.localFileID = original.sourceTrackID
                track.localMetadata = LocalAudioMetadata(album: "城市的声音 · 示例", albumArtist: "山海界面示例", trackNumber: index + 1, folderID: "preview")
                try await media.saveDownload(file: file, track: track)
                tracks.append(track)
            }
            let state = try await store.createPlaylist("晚间散步 · 示例")
            if let id = state.playlists.first?.id { _ = try await store.addTracks(tracks.map(\.id), to: id) }
            _ = try await store.setFavorite(tracks[0].id, on: true)
            _ = try await store.recordPlay(tracks[1].id)
            prepared = true
        } catch { /* 演示失败保留空态，测试通过页面数据断言报告。 */ }
    }
}

#if DEBUG
import SwiftUI
#Preview("山海本地示例") { MBRootView(source: DemoSource(), demo: true) }
#endif


private enum DemoFailure: LocalizedError {
    case network
    var errorDescription: String? { "网络连接失败，请重试（界面演示）" }
}

extension DemoCatalog {
    /// 独立演示背景，便于验证移动、裁剪和确认，不读取用户相册。
    static var backgroundImage: CGImage? {
        guard let context = CGContext(data: nil, width: 600, height: 1000, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [
                CGColor(red: 0.20, green: 0.28, blue: 0.45, alpha: 1),
                CGColor(red: 0.81, green: 0.50, blue: 0.40, alpha: 1)
              ] as CFArray, locations: [0, 1]) else { return nil }
        context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 600, y: 1000), options: [])
        context.setFillColor(CGColor(red: 0.98, green: 0.78, blue: 0.52, alpha: 1))
        context.fillEllipse(in: CGRect(x: 300, y: 580, width: 220, height: 220))
        for index in 0..<4 {
            context.setFillColor(CGColor(red: 0.10, green: 0.20, blue: 0.24, alpha: 0.2))
            context.fillEllipse(in: CGRect(x: -300 + index * 100, y: -400 + index * 85, width: 1200, height: 700))
        }
        return context.makeImage()
    }
}

extension DemoCatalog {
    @MainActor static func startupUpdateModel() -> AppUpdateModel {
        let data = Data("""
        [{"tag_name":"mb-ios-v0.1.0-build999","html_url":"https://github.com/xTreeRoot/ShanhaiMusic/releases/tag/mb-ios-v0.1.0-build999","body":"演示更新：保留资料库和插件数据。","draft":false,"prerelease":false,"assets":[{"name":"MB-iOS-v0.1.0-build999-unsigned.ipa","size":3,"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","browser_download_url":"https://github.com/xTreeRoot/ShanhaiMusic/releases/download/mb-ios-v0.1.0-build999/MB-iOS-v0.1.0-build999-unsigned.ipa"}]}]
        """.utf8)
        return AppUpdateModel(version: "0.1.0", build: 30, fetch: { try AppRelease.latest(in: data) },
                              receive: { _, _ in throw AppUpdateFailure.archive })
    }
}
