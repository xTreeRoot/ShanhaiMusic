import Foundation
import Testing
@testable import MBFoundation

private func withLikesPlugin(_ script: String, manifestJSON: String = #"{"id":"fixture"}"#, isolation: isolated (any Actor)? = #isolation,
                             perform: (PluginSource, PluginRuntime) async throws -> Void) async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try script.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(manifestJSON.utf8))
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, settings: [:])
    try await perform(PluginSource(manifest: manifest, runtime: runtime), runtime)
}

@Test func readOnlyLikesWalkAllPagesCacheAndResetAfterAccountChange() async throws {
    try await withLikesPlugin("""
    let who='first', reads=0;
    export function account(){return who ? {id:who,name:who} : null;}
    export function signOut(){who='';}
    export function signIn(){who='second';return {id:who,name:who};}
    export function liked({offset}){reads++;return !who ? {tracks:[],more:false} : offset===0 ? {tracks:[{id:who,name:who}],more:true,nextOffset:5} : {tracks:[{id:'tail'}],more:false};}
    export function probe(){return reads;}
    """) { source, runtime in
        _ = try await source.account()
        let caps = await source.capabilities()
        #expect(caps.likesReadable && !caps.likes)
        #expect(try await source.isLiked(trackID: "first"))
        #expect(try await source.isLiked(trackID: "tail"))
        #expect(try await !source.isLiked(trackID: "absent"))
        #expect(try await runtime.invoke("probe") == .number(2))
        try await source.signOut()
        #expect(try await !source.isLiked(trackID: "first"))
        _ = try await source.signIn { _ in }
        #expect(try await !source.isLiked(trackID: "first"))
        #expect(try await source.isLiked(trackID: "second"))
    }
}

@Test func brokenOrMissingLikeQueryUsesTheListAndInvalidPagesStayUnknown() async throws {
    for query in ["throw Error('unavailable')", "return {}"] {
        try await withLikesPlugin("export function isLiked(){\(query)};export function liked(){return {tracks:[{id:'saved'}],more:false};}") { source, _ in
            let liked = try await source.isLiked(trackID: "saved"); #expect(liked)
        }
    }
    for response in ["{}", "{tracks:[{id:'same'}],more:true,nextOffset:1}"] {
        try await withLikesPlugin("export function liked(){return \(response);}") { source, _ in
            await #expect(throws: (any Error).self) { try await source.isLiked(trackID: "unknown") }
        }
    }
}

@Test func loadingTheLikedCollectionRefreshesAnAlreadyOpenHeartAndUnlikeWins() async throws {
    try await withLikesPlugin("""
    let saved=false;
    export function account(){return {id:'one',name:'One'};}
    export function isLiked(){return {song:saved};}
    export function liked(){return {tracks:[{id:'song',name:'Saved'}],more:false};}
    export function like({on}){saved=on;}
    """) { source, _ in
        _ = try await source.account()
        #expect(try await !source.isLiked(trackID: "song"))
        _ = try await source.collection("liked")
        #expect(try await source.isLiked(trackID: "song"))
        try await source.like(trackID: "song", enabled: false)
        #expect(try await !source.isLiked(trackID: "song"))
    }
}

@Test func failedLikeQueriesCanBeRetriedWithoutCachingFalse() async throws {
    try await withLikesPlugin("export function isLiked(){if(!host.storage.get('tried')){host.storage.set('tried',true);throw Error('temporary');}return {song:true};}") { source, _ in
        await #expect(throws: (any Error).self) { try await source.isLiked(trackID: "song") }
        let liked = try await source.isLiked(trackID: "song"); #expect(liked)
    }
}

@Test func platformPlaylistTargetsKeepOwnershipAndExplicitSystemListPermission() async throws {
    try await withLikesPlugin("""
    let added=[];
    export function account(){return {id:'one',name:'One'};}
    export function playlists(){return [{id:'mine',name:'Mine',mine:true},{id:'foreign',name:'Foreign'},
      {id:'fixed',name:'Fixed',mine:true,fixed:true},{id:'system',name:'System',mine:true,fixed:true,addable:true}];}
    export function addToPlaylist(args){added.push(args);}
    export function probe(){return added;}
    """) { source, runtime in
        let targets = try await source.writablePlaylists()
        #expect(targets.map(\.sourceID) == ["mine", "system"])
        try await source.add(Track(sourceID: "fixture", sourceTrackID: "original:id", title: "Track", artist: "Artist"), toPlatformPlaylist: targets[1])
        let args = try await runtime.invoke("probe").array[0]
        #expect(args["id"] == .string("system")); #expect(args["trackIds"] == .array([.string("original:id")]))
        await #expect(throws: (any Error).self) {
            try await source.add(Track(sourceID: "other", sourceTrackID: "wrong", title: "Track", artist: "Artist"), toPlatformPlaylist: targets[0])
        }
    }
}

@Test func fixedFolderRemovalKeepsNativeIdentityAndRefreshesTheExistingHeart() async throws {
    try await withLikesPlugin("""
    let saved=true;
    export function account(){return {id:'one',name:'One'};}
    export function isLiked(){return {song:saved};}
    export function playlists(){return [{id:'folder',name:'My folder',mine:true,fixed:true,removable:true,addable:true}];}
    export function addToPlaylist(){}
    export function removeFromPlaylist({id,trackIds}){if(id!=='folder'||trackIds.join()!=='song')throw Error('wrong identity');saved=false;}
    """, manifestJSON: #"{"id":"fixture","playlistKind":"folder"}"#) { source, _ in
        let targets = try await source.writablePlaylists()
        #expect(targets[0].canRemoveTracks)
        #expect(try await source.isLiked(trackID: "song"))
        try await source.mutate("removeFromPlaylist", arguments: .object(["id":.string("folder"),"trackIds":.array([.string("song")])]))
        #expect(try await !source.isLiked(trackID: "song"))
    }
}
