import MBFoundation
import SwiftUI

/// 列表数据就绪后滚到当前曲目，分页沿用页面原有加载入口，不改播放队列。
struct PlaylistTrackScroll<Content: View>: View {
    let tracks: [Track]
    let current: Track?
    var enabled = true
    var loading = false
    var more = false
    var loadMore: (() async -> Bool)?
    @ViewBuilder var content: () -> Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var focus = PlaylistTrackFocus()
    @State private var fetching = false
    @State private var nextLoad: Task<Void, Never>?

    private struct Snapshot: Equatable {
        let target: String?
        let rows: [String]
        let loading, more, fetching: Bool
    }

    var body: some View {
        ScrollViewReader { proxy in
            content()
                .onAppear { fetching = false; focus = PlaylistTrackFocus(current: enabled ? current : nil) }
                .onDisappear { focus.stop(); nextLoad?.cancel(); nextLoad = nil }
                .simultaneousGesture(DragGesture(minimumDistance: 3).onChanged { _ in focus.stop(); nextLoad?.cancel() })
                .onChange(of: Snapshot(target: focus.target?.id, rows: tracks.map(\.id), loading: loading, more: more, fetching: fetching), initial: true) { _, _ in
                    guard !focus.finished, !loading, !fetching else { return }
                    if let index = focus.locate(in: tracks, hasMore: more) {
                        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.26)) {
                            proxy.scrollTo(tracks[index].id, anchor: .center)
                        }
                    } else if !focus.finished, let loadMore {
                        fetching = true
                        nextLoad = Task {
                            let progressed = await loadMore()
                            guard !Task.isCancelled else { return }
                            if !progressed { focus.stop() }
                            fetching = false
                        }
                    }
                }
        }
    }
}
