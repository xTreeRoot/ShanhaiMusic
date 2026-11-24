#if os(iOS)
import UIKit

/// 手机允许横竖屏，全屏视频优先横屏；平板始终随设备方向和窗口尺寸布局。
@MainActor public final class MBMobileAppDelegate: NSObject, UIApplicationDelegate {
    public func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : MobileOrientation.mask
    }
}

@MainActor enum MobileOrientation {
    static var mask: UIInterfaceOrientationMask = .allButUpsideDown
    static var isPhone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
    static func fullscreen(_ active: Bool) {
        guard isPhone else { return }
        mask = active ? .landscape : .allButUpsideDown
        request(mask)
    }
    static func player(landscape: Bool) {
        mask = .allButUpsideDown
        request(landscape ? .landscape : .portrait)
    }
    /// 封面缩放转场的承载控制器不会总是自动旋转，按真实设备方向更新当前场景。
    static func followDevice() {
        guard isPhone else { return }
        switch UIDevice.current.orientation {
        case .landscapeLeft: request(.landscapeRight)
        case .landscapeRight: request(.landscapeLeft)
        case .portrait: request(.portrait)
        case .portraitUpsideDown where !isPhone: request(.portraitUpsideDown)
        default: break
        }
    }
    private static func request(_ orientation: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        for window in scene.windows {
            window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            window.rootViewController?.presentedViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientation))
    }
}
#endif
