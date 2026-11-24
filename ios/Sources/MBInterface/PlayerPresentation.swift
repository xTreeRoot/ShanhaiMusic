import SwiftUI

extension View {
    /// 播放器占满可用屏幕，避免横屏仍受竖屏半屏卡片宽度限制。
    @ViewBuilder func playerPresentation<Content: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) -> some View {
        #if os(iOS)
        fullScreenCover(isPresented: isPresented, content: content)
        #else
        sheet(isPresented: isPresented, content: content)
        #endif
    }
}
