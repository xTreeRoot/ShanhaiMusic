import SwiftUI

#if os(iOS)
import UIKit

/// 将刷新控件绑定到内容自己的滚动容器，避免分页容器和横向卡片继承刷新手势。
struct ScrollRefreshControl: UIViewRepresentable {
    let action: () async -> Void
    func makeCoordinator() -> Coordinator { Coordinator(action: action) }
    func makeUIView(context: Context) -> Marker {
        let marker = Marker(); marker.coordinator = context.coordinator
        marker.isUserInteractionEnabled = false
        return marker
    }
    func updateUIView(_ marker: Marker, context: Context) {
        context.coordinator.action = action
        marker.connect()
    }
    static func dismantleUIView(_ marker: Marker, coordinator: Coordinator) { coordinator.detach() }

    final class Marker: UIView {
        weak var coordinator: Coordinator?
        override func didMoveToWindow() { super.didMoveToWindow(); connect() }
        func connect() {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.window != nil else { return }
                self.coordinator?.attach(from: self)
            }
        }
    }
    @MainActor final class Coordinator: NSObject {
        var action: () async -> Void
        private let control = UIRefreshControl()
        private weak var scroll: UIScrollView?
        private var task: Task<Void, Never>?
        init(action: @escaping () async -> Void) {
            self.action = action; super.init()
            control.addTarget(self, action: #selector(refresh), for: .valueChanged)
        }
        func attach(from marker: UIView) {
            var ancestor = marker.superview
            while let view = ancestor {
                if let found = view as? UIScrollView {
                    if scroll !== found { detach(); scroll = found }
                    if found.refreshControl !== control { found.refreshControl = control }
                    found.alwaysBounceVertical = true
                    return
                }
                ancestor = view.superview
            }
        }
        @objc private func refresh() {
            guard task == nil else { return }
            task = Task { [weak self] in
                guard let self else { return }
                await action()
                control.endRefreshing(); task = nil
            }
        }
        func detach() {
            task?.cancel(); task = nil
            control.endRefreshing()
            if scroll?.refreshControl === control { scroll?.refreshControl = nil }
            scroll = nil
        }
    }
}
#else
struct ScrollRefreshControl: View {
    let action: () async -> Void
    var body: some View { EmptyView() }
}
#endif
