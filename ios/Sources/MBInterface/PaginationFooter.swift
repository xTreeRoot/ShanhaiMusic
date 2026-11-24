import SwiftUI

private struct PaginationViewportKey: EnvironmentKey {
    static let defaultValue = CGRect.zero
}

private extension EnvironmentValues {
    var paginationViewport: CGRect {
        get { self[PaginationViewportKey.self] }
        set { self[PaginationViewportKey.self] = newValue }
    }
}

/// 以滚动容器的实际可见范围判断触底，兼容 iOS 17，避免 List 预创建行时提前遍历所有分页。
struct PaginationViewport: ViewModifier {
    @State private var frame = CGRect.zero
    func body(content: Content) -> some View {
        content.environment(\.paginationViewport, frame)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
    }
}

private struct PaginationPosition<Page: Hashable & Sendable>: Equatable, Sendable {
    let page: Page
    let frame: CGRect
    let viewport: CGRect
    let blocked: Bool
}

/// 同一游标自动请求一次；失败后由页面提供重试，离开列表时取消未完成任务。
struct PaginationFooter<Page: Hashable & Sendable>: View {
    let page: Page
    let loading: Bool
    var blocked = false
    let load: () async -> Void
    @Environment(\.paginationViewport) private var viewport
    @State private var requested: Page?
    @State private var pending: Page?
    @State private var frame = CGRect.zero

    var body: some View {
        ProgressView().opacity(loading ? 1 : 0).frame(maxWidth: .infinity, minHeight: 32)
            .accessibilityHidden(!loading)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
            .onChange(of: PaginationPosition(page: page, frame: frame, viewport: viewport, blocked: blocked || loading), initial: true) { _, position in
                guard !position.blocked, !position.viewport.isEmpty,
                      !position.frame.isEmpty, position.frame.intersects(position.viewport), requested != position.page else { return }
                pending = position.page
            }
            .task(id: pending) {
                guard let pending, requested != pending else { return }
                requested = pending
                await load()
                if Task.isCancelled, requested == pending { requested = nil }
            }
            .onDisappear { pending = nil; requested = nil }
    }
}
