import MBFoundation
import MBInterface
import SwiftUI

@main
struct MBiOSApp: App {
    @UIApplicationDelegateAdaptor(MBMobileAppDelegate.self) private var appDelegate
    private let demo: Bool
    private let source = DisabledMusicSource()

    init() {
        #if DEBUG
        demo = ProcessInfo.processInfo.arguments.contains("--demo")
        #else
        demo = false
        #endif
        if !demo { AppPersistence.shared.logPlayback(.launch) }
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--portrait-demo") { PortraitVideoDemoView() }
            else { root }
            #else
            root
            #endif
        }
    }
    @ViewBuilder private var root: some View {
            if demo { MBRootView(source: DemoSource(), demo: true) }
            else { MBRootView(source: source, sourceID: "") }
    }
}
