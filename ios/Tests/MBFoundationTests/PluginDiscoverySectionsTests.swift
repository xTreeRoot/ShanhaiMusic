import Foundation
import Testing
@testable import MBFoundation

@Test func discoverySkipsDisabledRequestsKeepsOrderAndRefreshesOnlyEnabledRoutes() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try """
    let calls = [];
    export function home() { throw Error('aggregate home must not execute'); }
    export function browse({page,refresh}) { calls.push([page,refresh === true]); return {sections:[{tracks:[{id:page,name:page}],morePage:'next:'+page}]}; }
    export function probe() { return calls; }
    """.write(to: root.appendingPathComponent("index.js"), atomically: true, encoding: .utf8)
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","discovery":[{"id":"one","title":"One","page":"one"},{"id":"two","title":"Two","page":"two"},{"id":"three","title":"Three","page":"three"}]}"#.utf8))
    try manifest.validate()
    let runtime = try PluginRuntime(directory: root, dataDirectory: root.appendingPathComponent("data"), manifest: manifest, settings: [:])
    let source = PluginSource(manifest: manifest, runtime: runtime)
    let empty = DiscoveryPreferences(), before = empty.resolve(plugin: "fixture", sections: manifest.discoverySections.map(\.preferenceSection))
    var draft = before; draft.order = ["three", "two", "one"]; draft.enabled["two"] = false
    let doc = try empty.edited(plugin: "fixture", before: before, draft: draft, writer: PluginPreferences.newWriter())
    let page = try await source.discoveryHome(preferences: doc, refresh: true)
    #expect(page.sections.map(\.title) == ["Three", "One"])
    #expect(page.sections.first?.morePage == "next:three")
    #expect(page.artworkTracks(sourceID: "fixture").map(\.sourceTrackID) == ["three", "one"])
    #expect(try await runtime.invoke("probe") == .array([.array([.string("three"),.bool(true)]),.array([.string("one"),.bool(true)])]))
    var disabled = draft; disabled.enabled = ["one": false, "two": false, "three": false]
    let off = try doc.edited(plugin: "fixture", before: draft, draft: disabled, writer: PluginPreferences.newWriter())
    #expect(try await source.discoveryHome(preferences: off, refresh: true).sections.isEmpty)
    #expect(try await runtime.invoke("probe").array.count == 2)
}

@Test func discoveryManifestRejectsDuplicatesAndConflictingRoutes() throws {
    for item in [#"{"id":"one","title":"One","page":"one","builtin":"liked"}"#, #"{"id":"../one","title":"One","page":"one"}"#, #"{"id":"one","title":"One","builtin":"other"}"#] {
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data((#"{"id":"fixture","discovery":["# + item + "]}").utf8))
        #expect(throws: (any Error).self) { try manifest.validate() }
    }
}
