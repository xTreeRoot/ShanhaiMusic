import MBFoundation
import SwiftUI

struct PluginCommunityView: View {
    let installed: [InstalledPlugin]
    let busy: Bool
    let install: (String) -> Void
    let model: PluginCommunityModel
    @State private var query = ""

    var body: some View {
        List {
            HStack {
                TextField("搜索插件", text: $query).autocorrectionDisabled()
                    .accessibilityIdentifier("plugin-community-search")
                    .onSubmit { Task { await model.search(query) } }
                Button("搜索") { Task { await model.search(query) } }
                    .accessibilityIdentifier("plugin-community-submit")
            }
            ForEach(model.plugins) { plugin in
                HStack(spacing: 12) {
                    RemoteArtwork(track: Track(sourceID: "plugin-community", sourceTrackID: plugin.id, title: plugin.name, artist: "", artworkURL: plugin.avatar))
                        .frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 10))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(plugin.name).font(.headline)
                        Text(plugin.description.isEmpty ? plugin.repository : plugin.description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        Text("★ \(plugin.stars)").font(.caption2).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if installed.contains(where: { $0.repository?.caseInsensitiveCompare(plugin.repository) == .orderedSame }) {
                        Text("已安装").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Button("安装") { install(plugin.repository) }.buttonStyle(.bordered).disabled(busy)
                            .accessibilityIdentifier("plugin-community-install-\(plugin.id)")
                    }
                }.padding(.vertical, 4)
            }
            if model.loading { ProgressView().frame(maxWidth: .infinity) }
            if let error = model.error {
                Text(error).font(.caption).foregroundStyle(.secondary)
                Button("重试") { Task { await model.loadMore() } }
            } else if model.more && !model.loading && !model.plugins.isEmpty {
                Button("加载更多") { Task { await model.loadMore() } }
                    .accessibilityIdentifier("plugin-community-more")
            } else if model.plugins.isEmpty && !model.loading {
                Text("没有找到插件").foregroundStyle(.secondary)
            }
        }
        .onAppear { query = model.query }
        .task { if !model.hasLoaded && !model.loading { await model.search("") } }
    }
}

struct InstalledPluginArtwork: View {
    let plugin: InstalledPlugin
    @Environment(\.skinAccent) private var accent
    var body: some View {
        Group {
            if let url = plugin.iconURL {
                RemoteArtwork(track: Track(sourceID: "plugin-icon", sourceTrackID: plugin.id, title: plugin.manifest.displayName, artist: "", artworkURL: url))
            } else {
                Image(systemName: "puzzlepiece.extension.fill").font(.title2).foregroundStyle(accent)
                    .frame(width: 48, height: 48).background(accent.opacity(0.08))
            }
        }.frame(width: 48, height: 48).clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityHidden(true)
    }
}
