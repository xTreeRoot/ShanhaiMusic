import XCTest
import UIKit

final class MiniPlayerLayoutTests: XCTestCase {
    @MainActor func testMiniPlayerKeepsTabButtonsClearAndUsable() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let more = app.buttons["查看更多为你推荐"]
        XCTAssertTrue(more.waitForExistence(timeout: 15))
        more.tap()
        let track = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 5))
        track.tap()
        app.navigationBars["为你推荐"].buttons.element(boundBy: 0).tap()

        let player = app.buttons["打开播放页，晚风里的片刻"]
        for title in ["发现", "搜索", "资料库", "我的"] {
            let tab = app.tabBars.buttons[title]
            XCTAssertTrue(tab.isHittable)
            tab.tap()
            XCTAssertTrue(tab.isSelected)
            XCTAssertTrue(player.waitForExistence(timeout: 5))
            XCTAssertTrue(player.isHittable)
            for button in app.tabBars.buttons.allElementsBoundByIndex {
                XCTAssertFalse(player.frame.intersects(button.frame), "迷你播放器遮住了标签：\(button.label)")
                if UIDevice.current.userInterfaceIdiom == .phone {
                    XCTAssertLessThanOrEqual(player.frame.maxY, button.frame.minY, "迷你播放器应位于标签栏上方")
                }
            }
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "底部播放器-\(title)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        player.tap()
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        XCTAssertTrue(player.waitForExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["发现"].isHittable)
    }
}

final class PlayerNavigationRegressionTests: XCTestCase {
    @MainActor func testMusicToVideoKeepsSafeAreaAndButtonsResponsive() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchEnvironment["MB_VIDEO_UI_FIXTURE"] = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Tests/MBFoundationTests/Fixtures/video-ui.mp4").path
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let more = app.buttons["查看更多为你推荐"]
        XCTAssertTrue(more.waitForExistence(timeout: 15)); more.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let openPlayer = app.buttons["打开播放页，晚风里的片刻"]
        if !app.buttons["观看当前视频"].waitForExistence(timeout: 3) {
            XCTAssertTrue(openPlayer.waitForExistence(timeout: 5)); openPlayer.tap()
        }
        for round in 1...3 {
            let watch = app.buttons["观看当前视频"]
            XCTAssertTrue(watch.waitForExistence(timeout: 5))
            if round == 2 {
                // 普通收起也要清掉保留的视频路径，再从迷你播放器重新打开。
                app.buttons["收起"].tap()
                let mini = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
                XCTAssertTrue(mini.waitForExistence(timeout: 5)); mini.tap()
                XCTAssertTrue(watch.waitForExistence(timeout: 5))
            }
            watch.tap()
            let surface = app.otherElements["native-video-surface"]
            XCTAssertTrue(surface.waitForExistence(timeout: 12))
            // 切换完成后暂停再检查位置，避免检查期间控制条按产品设计自动收起。
            let pause = app.buttons["暂停"]
            if pause.exists && pause.isHittable { pause.tap() }
            XCTAssertTrue(app.buttons["video-open-music"].waitForExistence(timeout: 5))
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "听歌切视频-第\(round)轮"; shot.lifetime = .keepAlways; add(shot)
            let status = XCUIApplication(bundleIdentifier: "com.apple.springboard").statusBars.firstMatch
            XCTAssertTrue(status.waitForExistence(timeout: 5))
            XCTAssertGreaterThanOrEqual(surface.frame.minY, status.frame.maxY - 1, "视频不能覆盖系统状态栏")
            XCTAssertTrue(app.buttons["返回浏览"].isHittable)
            XCTAssertTrue(app.buttons["video-open-music"].isHittable)
            if round < 3 { app.buttons["video-open-music"].tap() }
        }
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(app.buttons["展开视频"].waitForExistence(timeout: 5))
        app.buttons["关闭视频小窗"].tap()
        let reopen = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
        XCTAssertTrue(reopen.waitForExistence(timeout: 5)); reopen.tap()
        // 迷你播放器沿用当前视频展示模式；切回听歌后再验证普通收起。
        XCTAssertTrue(app.buttons["video-open-music"].waitForExistence(timeout: 5))
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(app.buttons["观看当前视频"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        XCTAssertTrue(reopen.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["返回浏览"].exists)
    }
}

final class SidebarNavigationRegressionTests: XCTestCase {
    @MainActor func testPluginSidebarSelectionEmptyAndRetry() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("仅验证平板侧栏") }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--sidebar-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .landscapeLeft
        app.launch()
        XCTAssertTrue(app.buttons["sidebar-home"].waitForExistence(timeout: 15))
        app.buttons["我的"].firstMatch.tap()
        app.buttons["open-settings"].tap()
        app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        let back = app.navigationBars.buttons["BackButton"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap()
        XCTAssertTrue(app.buttons["dismiss-settings"].waitForExistence(timeout: 5)); app.buttons["dismiss-settings"].tap()
        app.buttons["发现"].firstMatch.tap()
        let plugin = app.buttons["source-tab-fixture"]
        XCTAssertTrue(plugin.waitForExistence(timeout: 5)); plugin.tap()
        let content = app.buttons["sidebar-content"], empty = app.buttons["sidebar-empty"], failure = app.buttons["sidebar-failure"]
        XCTAssertTrue(content.waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["sidebar-recommended"].exists, "插件侧栏不能保留旧来源的固定分类")
        for orientation in [UIDeviceOrientation.landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            // 点击行尾空白区域，验证整行命中，不只文字可点击。
            content.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.5)).tap()
            XCTAssertTrue(app.buttons["播放 侧栏独立内容"].waitForExistence(timeout: 8))
            XCTAssertTrue(content.isSelected)
            empty.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.5)).tap()
            XCTAssertTrue(app.staticTexts["暂无内容"].waitForExistence(timeout: 8))
            XCTAssertTrue(empty.isSelected); XCTAssertFalse(content.isSelected)
            let reload = app.buttons["重新加载"]
            XCTAssertTrue(reload.isHittable); reload.tap()
            XCTAssertTrue(app.staticTexts["暂无内容"].waitForExistence(timeout: 8))
            capture(orientation == .portrait ? "iPad-侧栏空态-竖屏" : "iPad-侧栏空态-横屏")
            app.buttons["sidebar-home"].tap()
            XCTAssertTrue(app.buttons["sidebar-home"].isSelected)
            XCTAssertFalse(empty.isSelected)
        }
        failure.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "侧栏加载失败")).firstMatch.waitForExistence(timeout: 8))
        XCTAssertTrue(failure.isSelected)
        capture("iPad-侧栏失败与重试")
        app.buttons["重试"].tap()
        XCTAssertTrue(app.buttons["播放 重试成功"].waitForExistence(timeout: 8))
        XCTAssertTrue(failure.isSelected)
        content.tap()
        XCTAssertTrue(app.buttons["播放 侧栏独立内容"].waitForExistence(timeout: 8))
        XCTAssertTrue(content.isSelected)
        capture("iPad-侧栏切回内容")
        XCUIDevice.shared.orientation = .landscapeLeft
        app.buttons["sidebar-home"].tap()
        app.buttons["收起侧边栏"].tap()
        capture("iPad-收起侧栏")
        let expand = app.buttons["显示边栏"]
        XCTAssertTrue(expand.waitForExistence(timeout: 5)); XCTAssertTrue(expand.isHittable); expand.tap()
        XCTAssertTrue(content.waitForExistence(timeout: 5)); XCTAssertTrue(content.isHittable)
        XCTAssertTrue(app.buttons["sidebar-home"].isSelected)
        capture("iPad-重新展开侧栏")
    }

    @MainActor private func capture(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
}
