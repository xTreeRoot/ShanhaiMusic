import MBFoundation
import SwiftUI

func resourceTypeName(_ type: String?) -> String {
    switch type {
    case "video": AppLocalization.text("视频")
    case "book": AppLocalization.text("图书")
    case "software": AppLocalization.text("软件")
    case "music", nil: AppLocalization.text("音乐")
    default: AppLocalization.text("其他")
    }
}

struct ResourceLibrarySection: View {
    let library: LocalLibraryModel
    let playback: PlaybackModel
    @Environment(\.sourceNames) private var names
    var body: some View {
        if !library.resources.isEmpty {
            Section("已保存资源") {
                ForEach(library.resources) { track in
                    HStack {
                        if track.resourceURL != nil {
                            NavigationLink { ResourceDetailView(track: track, library: library) } label: { row(track) }
                        } else {
                            Button { playback.select([track], index: 0) } label: { row(track) }.buttonStyle(.plain)
                        }
                        Menu {
                            if let url = track.resourceURL ?? track.pageURL { ShareLink(item: url) }
                            Button("移除", role: .destructive) { Task { await library.removeResource(track.id) } }
                        } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                    }
                }
            }
        }
    }
    private func row(_ track: Track) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            TrackRow(track: track)
            Text("\(names[track.sourceID] ?? track.artist) · \(resourceTypeName(track.resourceType))")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct ResourceDetailView: View {
    let track: Track
    let library: LocalLibraryModel
    @Environment(\.openURL) private var openURL
    @State private var openError = false
    var body: some View {
        List {
            Group {
                TrackRow(track: track)
                LabeledContent("平台", value: track.artist)
                LabeledContent("资源类型", value: resourceTypeName(track.resourceType))
                if let url = track.resourceURL {
                    Button("打开资源链接") { openURL(url) { accepted in openError = !accepted } }
                    ShareLink("分享资源链接", item: url)
                }
                if let page = track.pageURL { Link("查看来源", destination: page) }
                Button(library.resources.contains(where: { $0.id == track.id }) ? "已保存到资料库" : "保存到资料库") {
                    Task { await library.saveResource(track) }
                }
                if openError { Text("未找到可打开此链接的应用，请使用分享资源链接。") }

            }.modifier(SkinRow())
        }.modifier(SkinSurface(defaultColor: MBTheme.background, list: true)).navigationTitle(track.title)
    }
}
