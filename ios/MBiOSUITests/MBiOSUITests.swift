import XCTest
import UIKit

final class MBiOSUITests: XCTestCase {
    @MainActor func testMusicMetadataAuthorLikesAndActionSheetShareQualityWithSettings() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("player fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let like = app.buttons["player-platform-like"]
        XCTAssertTrue(like.waitForExistence(timeout: 5))
        let loaded = NSPredicate(format: "value == %@", "128")
        expectation(for: loaded, evaluatedWith: like); waitForExpectations(timeout: 5)
        like.tap(); XCTAssertEqual(like.value as? String, "129")
        like.tap(); XCTAssertEqual(like.value as? String, "128")
        XCTAssertEqual(app.buttons["player-track-comments"].value as? String, "2")
        app.buttons["player-creator"].tap()
        XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5)); app.buttons["完成"].tap()
        capture("音乐播放器-作者与互动总数", app: app)
        app.buttons["player-track-more"].tap()
        XCTAssertTrue(app.buttons["player-collect"].waitForExistence(timeout: 5))
        app.buttons["player-quality"].tap(); app.buttons["player-quality-lossless"].tap()
        XCTAssertTrue(app.buttons["player-quality-lossless"].isSelected)
        capture("音乐播放器-底部操作与全局音质", app: app)
        app.buttons["关闭"].tap(); app.buttons["收起"].tap()
        selectTab("我的", app: app)
        app.navigationBars.buttons["open-settings"].firstMatch.tap()
        XCTAssertTrue(app.buttons["音质, 无损"].waitForExistence(timeout: 5) || app.staticTexts["无损"].exists)
    }

    @MainActor func testNodeFormClearsAddressAndRejectsHTTPWithoutChangingBinding() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("我的", app: app)
        let service = app.buttons["open-shanhai-service"]
        if !service.isHittable { app.swipeUp() }
        XCTAssertTrue(service.waitForExistence(timeout: 5)); service.tap()
        let address = app.textFields["node-address"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap(); address.typeText("https://synthetic.example")
        app.buttons["清空节点地址"].tap()
        XCTAssertEqual(address.value as? String, "域名或 HTTPS 地址")
        address.tap(); address.typeText("http://synthetic.example")
        let password = app.secureTextFields["node-password"]
        password.tap(); password.typeText("synthetic fixture")
        app.buttons["显示密码"].tap()
        XCTAssertEqual(app.textFields["node-password"].value as? String, "synthetic fixture")
        app.buttons["隐藏密码"].tap()
        app.buttons["node-connect"].tap()
        XCTAssertTrue(app.staticTexts["node-result"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["node-result"].label.contains("HTTPS"))
        XCTAssertTrue(app.buttons["node-connect"].isEnabled)
        capture("山海服务-节点表单与地址校验", app: app)
        app.buttons["完成"].tap()
        XCTAssertTrue(service.waitForExistence(timeout: 5))
    }

    @MainActor func testBackgroundCropPreviewAndConfirmationApplyOnlyAtTheEnd() {
        let app = backgroundEditorApp()
        app.sliders["背景缩放"].adjust(toNormalizedSliderPosition: 0.1)
        app.descendants(matching: .any)["background-crop-canvas"].firstMatch.swipeLeft()
        app.buttons["preview-background-image"].tap()
        let slider = app.sliders["background-transparency"]
        XCTAssertTrue(slider.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["choose-background-skin"].exists)
        XCTAssertTrue(app.buttons["切换播放模式"].exists)
        slider.adjust(toNormalizedSliderPosition: 0.35)
        app.sliders["background-image-zoom"].adjust(toNormalizedSliderPosition: 0.07)
        app.sliders["background-blur"].adjust(toNormalizedSliderPosition: 0.15)
        app.buttons["主题色 蔷薇"].tap()
        let playerSwitch = app.switches["background-preview-player-switch"]
        // SwiftUI 的 Toggle 可访问范围包含整行标题，真实开关位于行尾。
        playerSwitch.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5))
            .withOffset(CGVector(dx: -25, dy: 0)).tap()
        XCTAssertEqual(playerSwitch.value as? String, "1")
        let selectedZoom = app.sliders["background-image-zoom"].value as? String
        let selectedBlur = app.sliders["background-blur"].value as? String
        let selectedTransparency = slider.value as? String
        XCTAssertNotNil(selectedTransparency)
        capture("背景-发现页实时预览", app: app)
        app.buttons["apply-background-image"].tap()
        XCTAssertTrue(slider.waitForNonExistence(timeout: 5))
        selectTab("设置", app: app); app.buttons["background-skin-settings"].tap()
        if app.buttons["preview-background-image"].waitForExistence(timeout: 2) { app.buttons["cancel-background-edit"].tap() }
        XCTAssertTrue(app.buttons["clear-background-skin"].waitForExistence(timeout: 5))
        app.buttons["preview-current-background"].tap()
        XCTAssertTrue(slider.waitForExistence(timeout: 5))
        XCTAssertEqual(slider.value as? String, selectedTransparency)
        XCTAssertEqual(app.sliders["background-image-zoom"].value as? String, selectedZoom)
        XCTAssertEqual(app.sliders["background-blur"].value as? String, selectedBlur)
        XCTAssertEqual(app.switches["background-preview-player-switch"].value as? String, "1")
        capture("背景-确认后使用", app: app)
        app.sliders["background-blur"].adjust(toNormalizedSliderPosition: 0)
        app.buttons["cancel-background-edit"].tap()
        selectTab("搜索", app: app)
        capture("背景-搜索页连续背景", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("skin fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let player = app.descendants(matching: .any)["music-player-root"].firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 5))
        XCTAssertEqual(player.value as? String, "自定义播放器背景")
        capture("背景-音乐播放器应用皮肤", app: app)
        app.buttons["收起"].tap()
        selectTab("资料库", app: app); capture("背景-资料库连续背景", app: app)
        selectTab("我的", app: app); capture("背景-我的连续背景", app: app)
        selectTab("设置", app: app); capture("背景-设置连续背景", app: app)
        app.buttons["background-skin-settings"].tap()
        if app.buttons["preview-background-image"].waitForExistence(timeout: 2) { app.buttons["cancel-background-edit"].tap() }
        let savedSwitch = app.switches["background-player-switch"]
        XCTAssertTrue(savedSwitch.waitForExistence(timeout: 5))
        XCTAssertEqual(savedSwitch.value as? String, "1")
        savedSwitch.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5))
            .withOffset(CGVector(dx: -25, dy: 0)).tap()
        XCTAssertEqual(savedSwitch.value as? String, "0")
        selectTab("搜索", app: app)
        song.tap()
        XCTAssertTrue(player.waitForExistence(timeout: 5))
        XCTAssertEqual(player.value as? String, "默认播放器背景")
        capture("背景-关闭播放器应用皮肤", app: app)
        app.buttons["收起"].tap()
        selectTab("设置", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "外观")).firstMatch.tap()
        app.buttons["深色"].tap()
        capture("背景-深色设置连续背景", app: app)
        selectTab("资料库", app: app); capture("背景-深色资料库连续背景", app: app)
    }

    @MainActor func testCancellingBackgroundPreviewAndInvalidURLPreserveCurrentSkin() {
        let app = backgroundEditorApp()
        app.buttons["preview-background-image"].tap()
        let slider = app.sliders["background-transparency"]
        XCTAssertTrue(slider.waitForExistence(timeout: 5))
        slider.adjust(toNormalizedSliderPosition: 0.1)
        app.buttons["cancel-background-edit"].tap()
        XCTAssertTrue(slider.waitForNonExistence(timeout: 5))
        selectTab("设置", app: app); app.buttons["background-skin-settings"].tap()
        if app.buttons["preview-background-image"].waitForExistence(timeout: 2) { app.buttons["cancel-background-edit"].tap() }
        XCTAssertTrue(app.buttons["choose-background-skin"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["clear-background-skin"].exists)
        app.buttons["choose-background-skin"].tap(); app.buttons["网络图片地址"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("file:///invalid.png")
        app.alerts.buttons["下一步"].tap()
        XCTAssertTrue(app.staticTexts["无法更新背景图片"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["apply-background-image"].exists)
    }

    @MainActor private func backgroundEditorApp() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-background-editor", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch(); selectTab("设置", app: app)
        app.buttons["background-skin-settings"].tap()
        XCTAssertTrue(app.buttons["preview-background-image"].waitForExistence(timeout: 5))
        return app
    }

    @MainActor func testLandscapeQueueAndCommentsBlendWithPlayerAndRemainInteractive() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("player background fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        XCTAssertTrue(app.buttons["下一首"].waitForExistence(timeout: 5))
        if UIDevice.current.userInterfaceIdiom == .phone { app.buttons["横屏播放"].tap() }
        XCUIDevice.shared.orientation = .landscapeLeft
        let queue = app.buttons["wide-player-queue"]
        XCTAssertTrue(queue.waitForExistence(timeout: 8)); queue.tap()
        XCTAssertTrue(app.buttons["编辑队列"].waitForExistence(timeout: 5))
        capture("横屏播放器-透明待播清单", app: app)
        app.buttons["编辑队列"].tap()
        XCTAssertTrue(app.buttons["完成编辑"].waitForExistence(timeout: 5))
        app.buttons["完成编辑"].tap()
        let next = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "队列选择 ")).element(boundBy: 1)
        XCTAssertTrue(next.waitForExistence(timeout: 5)); next.tap()
        app.buttons["wide-player-comments"].tap()
        let comment = app.staticTexts["慢下来，听见晚风里的片刻。"]
        XCTAssertTrue(comment.waitForExistence(timeout: 5))
        capture("横屏播放器-透明评论", app: app)
        XCTAssertTrue(app.buttons["下一首"].isHittable)
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor func testStartupUpdateOptOutStopsForegroundPromptsAndKeepsManualEntry() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-startup-update", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.navigationBars["发现新版本"].waitForExistence(timeout: 10))
        let optOut = app.buttons["不再打扰"], update = app.buttons["更新"]
        XCTAssertLessThan(abs(optOut.frame.midY - update.frame.midY), 2)
        XCTAssertLessThan(optOut.frame.maxX, update.frame.minX)
        capture("更新-启动发现新版本", app: app)
        app.buttons["关闭"].tap()
        XCUIDevice.shared.press(.home); app.activate()
        XCTAssertTrue(app.navigationBars["发现新版本"].waitForExistence(timeout: 10))
        app.buttons["不再打扰"].tap()
        XCUIDevice.shared.press(.home); app.activate()
        XCTAssertFalse(app.navigationBars["发现新版本"].exists)
        selectTab("设置", app: app); app.buttons["settings-app-updates"].tap()
        XCTAssertTrue(app.buttons["check-app-version"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.switches["启动时检查版本"].value as? String, "0")
    }

    @MainActor func testPlayerStageSwipesChangeTracksWithoutOpeningVideo() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("swipe fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let stage = app.descendants(matching: .any)["music-player-swipe-stage"].firstMatch
        XCTAssertTrue(stage.waitForExistence(timeout: 5))
        let controls = app.otherElements["player-controls"]
        XCTAssertTrue(controls.staticTexts["晚风里的片刻"].exists)
        stage.swipeLeft()
        XCTAssertTrue(controls.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["收起"].exists)
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        stage.swipeRight()
        XCTAssertTrue(controls.staticTexts["晚风里的片刻"].waitForExistence(timeout: 5))
        capture("播放器-左右滑动切歌", app: app)
    }

    @MainActor func testLyricSelectionRequiresTheSeparatePlayConfirmation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("lyric fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        app.buttons["字幕"].tap()
        let line = app.buttons["选择歌词 把今天的喧闹放远"]
        XCTAssertTrue(line.waitForExistence(timeout: 5))
        let progress = app.sliders["字幕时间轴预览"]
        let original = progress.value as? String
        line.tap(); line.tap()
        let confirm = app.buttons["lyric-confirm-1"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertEqual(progress.value as? String, original, "重复点歌词不会跳转")
        capture("歌词-选中后单独确认", app: app)
        confirm.tap()
        let moved = expectation(for: NSPredicate { _, _ in progress.value as? String != original }, evaluatedWith: app)
        wait(for: [moved], timeout: 5)
        XCTAssertFalse(confirm.exists)
        line.tap(); XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        app.descendants(matching: .any)["music-player-swipe-stage"].firstMatch.swipeLeft()
        XCTAssertTrue(app.otherElements["player-controls"].staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        XCTAssertFalse(confirm.exists, "换歌后清除未确认的歌词选择")
    }

    @MainActor func testLoginButtonOffersMethodsBeforePreparingQRCode() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-sign-in-methods", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let accountTab = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons["我的"].firstMatch : app.tabBars.buttons["我的"].firstMatch
        XCTAssertTrue(accountTab.waitForExistence(timeout: 10)); accountTab.tap()
        let login = app.buttons["登录本地示例"]
        XCTAssertTrue(login.waitForExistence(timeout: 5)); login.tap()
        XCTAssertTrue(app.buttons["sign-in-method-second"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.images["登录二维码"].exists)
        capture("登录-直接选择扫码方式", app: app)
        app.buttons["sign-in-method-second"].tap()
        XCTAssertTrue(app.staticTexts["已选择第二种扫码"].waitForExistence(timeout: 5))
        app.buttons["取消"].tap(); login.tap()
        XCTAssertTrue(app.buttons["sign-in-method-first"].waitForExistence(timeout: 5))
        app.buttons["sign-in-method-first"].tap()
        XCTAssertTrue(app.staticTexts["已选择第一种扫码"].waitForExistence(timeout: 5))
        capture("登录-按选项生成二维码", app: app)
        app.buttons["取消"].tap()
    }

    @MainActor func testPluginNetworkPermissionsStartCollapsedAndCanExpand() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.navigationBars["安装插件"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        let plugin = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch
        XCTAssertTrue(plugin.waitForExistence(timeout: 5)); plugin.tap()
        let permissions = app.descendants(matching: .any)["plugin-network-permissions"].firstMatch
        for _ in 0..<4 { if permissions.exists && permissions.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(permissions.exists && permissions.isHittable)
        XCTAssertFalse(app.staticTexts["media.example.test"].exists)
        capture("插件-网络权限默认收起", app: app)
        permissions.tap()
        XCTAssertTrue(app.staticTexts["media.example.test"].waitForExistence(timeout: 5))
        permissions.tap(); XCTAssertFalse(app.staticTexts["media.example.test"].exists)
    }

    @MainActor func testSelectingMusicAutomaticallyOpensPlayer() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("player fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 5))
        capture("点击资源-自动展开播放器", app: app)
    }

    @MainActor func testMagnetDirectImportAndRecentSearchRemoval() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        app.buttons["search-platform-picker"].tap(); app.buttons["磁力链搜索"].tap()
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("magnet:?xt=urn:btih:30D1F776C01C1F67FA425D4FA683D0AFA21AA1E4&dn=Direct%20resource\n")
        XCTAssertTrue(app.buttons["选择 Direct resource"].waitForExistence(timeout: 5))
        capture("磁力链-直接识别", app: app)
        app.buttons["清空搜索"].tap()
        field.tap(); field.typeText("history one\n")
        app.buttons["清空搜索"].tap()
        field.tap(); field.typeText("history two\n")
        app.buttons["清空搜索"].tap()
        XCTAssertTrue(app.buttons["删除搜索记录 history one"].waitForExistence(timeout: 5))
        app.buttons["删除搜索记录 history one"].tap()
        XCTAssertFalse(app.buttons["删除搜索记录 history one"].exists)
        XCTAssertTrue(app.buttons["删除搜索记录 history two"].exists)
        capture("最近搜索-单条删除", app: app)
        app.buttons["全部清除"].tap()
        XCTAssertFalse(app.buttons["删除搜索记录 history two"].exists)
    }

    @MainActor func testPortraitVideoQueueSwipeAndCommentsCaptions() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--portrait-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        XCTAssertTrue(app.staticTexts["竖屏示例一"].waitForExistence(timeout: 5))
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["竖屏示例二"].waitForExistence(timeout: 5))
        app.buttons["上一个"].tap()
        XCTAssertTrue(app.staticTexts["竖屏示例一"].waitForExistence(timeout: 5))
        app.buttons["收藏"].tap(); XCTAssertTrue(app.buttons["已收藏"].exists)
        capture("竖屏-通用操作", app: app)
        app.buttons["评论"].tap()
        XCTAssertTrue(app.navigationBars["评论与点赞"].waitForExistence(timeout: 5))
        app.buttons["完成"].tap()
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.navigationBars["字幕"].waitForExistence(timeout: 5))
        capture("竖屏-字幕", app: app)
    }

    @MainActor func testAppUpdateSettingsCanOpenAndReturnWithoutLosingSettings() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let account = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons["我的"].firstMatch : app.tabBars.buttons["我的"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 10))
        // iOS 26 模拟器的极短合成点击可在玻璃导航层被丢弃，发送完整按下/抬起手势，不重试业务动作。
        account.press(forDuration: 0.15)
        let settings = app.buttons["open-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5)); settings.press(forDuration: 0.15)
        let entry = app.buttons["settings-app-updates"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.press(forDuration: 0.15)
        let check = app.buttons["check-app-version"]
        XCTAssertTrue(check.waitForExistence(timeout: 5))
        XCTAssertTrue(check.isEnabled)
        XCTAssertTrue(app.staticTexts["ios-update-signing-note"].exists)
        XCTAssertFalse(app.progressIndicators.firstMatch.exists, "进入页面不会自动发起下载")
        capture("版本更新-检查入口与签名交接说明", app: app)
        app.navigationBars["检查版本"].buttons["BackButton"].press(forDuration: 0.15)
        XCTAssertTrue(app.buttons["settings-app-updates"].waitForExistence(timeout: 5))
        app.buttons["dismiss-settings"].press(forDuration: 0.15)
        XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 5))
    }

    /// 手动联网回归，需先安装原版网易云插件；不扫码确认，不改变账号。
    @MainActor func testNeteaseQRCodeRetryAndPlaybackSurviveRelaunch() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let source = app.buttons.matching(NSPredicate(format: "label == %@", "网易云音乐")).firstMatch
        guard source.waitForExistence(timeout: 5) else {
            throw XCTSkip("需要预先安装 sogud/mygo-music-netease v0.1.14")
        }
        source.tap()
        app.tabBars.buttons["我的"].tap()
        let login = app.buttons["登录网易云音乐"]
        XCTAssertTrue(login.waitForExistence(timeout: 15))
        for attempt in 1...2 {
            login.tap()
            XCTAssertTrue(app.images["登录二维码"].waitForExistence(timeout: 20))
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Invalid sign-in prompt")).firstMatch.exists)
            capture("网易云-第\(attempt)次生成二维码-未扫码", app: app)
            app.buttons["取消"].tap()
            XCTAssertTrue(login.waitForExistence(timeout: 5))
        }
        app.tabBars.buttons["搜索"].tap()
        let search = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("夜曲\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "选择 夜曲", "Cover")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 20))
        song.tap()
        let player = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 15))
        let title = player.label
        let pause = app.buttons["暂停"]
        XCTAssertTrue(pause.waitForExistence(timeout: 30), "真实音频应进入播放状态")
        pause.tap()
        capture("网易云-真实曲目播放后暂停", app: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons[title].waitForExistence(timeout: 20))
        app.buttons[title].tap()
        XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
        capture("网易云-重启恢复并展开播放器", app: app)
    }

    @MainActor func testSearchKeyboardRequiresTapAndCanBeDismissed() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        XCTAssertFalse(app.keyboards.firstMatch.exists, "进入搜索不应自动弹出键盘")
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        let done = app.buttons["dismiss-search-keyboard"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.staticTexts["浏览音乐"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        let landing = app.scrollViews["search-landing"]
        XCTAssertTrue(landing.isHittable)
        capture("搜索-滚动收起键盘前-演示数据", app: app)
        // 滚动容器延伸到键盘后方，手势落在当前可见内容内。
        let dragStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let dragEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.30))
        dragStart.press(forDuration: 0.05, thenDragTo: dragEnd)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        selectTab("发现", app: app)
        selectTab("搜索", app: app)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        field.typeText("music")
        app.buttons["submit-search"].tap()
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        capture("搜索-手动输入与收起键盘-演示数据", app: app)
    }

    @MainActor func testPlayerControlsAndMiniQueueStayUsableAcrossTrackChanges() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
            app.buttons["为你推荐"].firstMatch.tap()
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["播放队列"].waitForExistence(timeout: 5))
        app.buttons["播放队列"].tap()
        XCTAssertTrue(app.navigationBars["播放队列 · 3"].waitForExistence(timeout: 5))
        capture("迷你播放器-直接打开队列-演示数据", app: app)
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.buttons["打开播放页，城市慢下来"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["全屏播放"].exists, "听歌模式中的队列切歌应保留当前界面")
        app.buttons["打开播放页，城市慢下来"].tap()
        for label in ["上一首", "下一首", "字幕", "队列", "player-track-comments"] {
            XCTAssertTrue(app.buttons[label].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons[label].isHittable, "\(label) 应无需滚动即可点击")
        }
        let dock = app.otherElements["player-controls"]
        XCTAssertTrue(dock.exists)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(dock.frame))
        capture("播放器-固定控制与评论入口-演示数据", app: app)
        app.buttons["player-creator"].tap()
        XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["播放 雨落在窗沿"].waitForExistence(timeout: 5))
        capture("播放器-查看作者其他内容-演示数据", app: app)
        app.buttons["完成"].tap()
        app.buttons["下一首"].tap()
        XCTAssertTrue(app.staticTexts["雨落在窗沿"].waitForExistence(timeout: 5))
        app.buttons["上一首"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["player-track-comments"].tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        capture("播放器-评论阅读-演示数据", app: app)
        app.buttons["完成"].tap()
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["选择歌词 让晚风经过耳边"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["下一首"].isHittable)
        XCTAssertTrue(app.buttons["队列"].isHittable)
        capture("播放器-歌词与固定控制-演示数据", app: app)
        app.buttons["队列"].tap()
        app.buttons["队列选择 晚风里的片刻"].tap()
        XCTAssertTrue(app.staticTexts["晚风里的片刻"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["player-track-comments"].isHittable)
        app.buttons["收起"].tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
    }

    @MainActor func testMiniPlayerDoesNotOverlapTabsAcrossPagesAndRotation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
            app.buttons["为你推荐"].firstMatch.tap()
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
        if UIDevice.current.userInterfaceIdiom == .pad { app.buttons["首页"].tap() }
        else { app.navigationBars.buttons.element(boundBy: 0).tap() }
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            // 手机浏览页按现有策略保持竖屏；平板跟随设备旋转。
            let landscape = UIDevice.current.userInterfaceIdiom == .pad && orientation == .landscapeLeft
            let rotated = expectation(for: NSPredicate { _, _ in
                let frame = app.windows.firstMatch.frame
                return landscape ? frame.width > frame.height : frame.height > frame.width
            }, evaluatedWith: app)
            wait(for: [rotated], timeout: 10)
            for title in ["发现", "搜索", "资料库", "我的"] {
                let tab = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons[title].firstMatch : app.tabBars.buttons[title].firstMatch
                XCTAssertTrue(tab.isHittable); tab.tap()
                let mini = app.otherElements["mini-player"].firstMatch
                XCTAssertTrue(mini.waitForExistence(timeout: 5))
                if UIDevice.current.userInterfaceIdiom == .pad {
                    // iPad 顶部标签在可访问树中是按钮，不归入 TabBar。
                    for label in ["发现", "搜索", "资料库", "我的"] {
                        XCTAssertFalse(mini.frame.intersects(app.buttons[label].firstMatch.frame), "迷你播放器不应覆盖导航按钮")
                    }
                } else {
                    XCTAssertFalse(mini.frame.intersects(app.tabBars.firstMatch.frame), "迷你播放器不应覆盖导航栏")
                }
                XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].isHittable)
                let layout = orientation == .portrait ? "竖屏" : landscape ? "横屏" : "设备横置保持竖屏"
                capture("迷你播放器-\(layout)-\(title)-演示数据", app: app)
            }
            app.buttons["打开播放页，晚风里的片刻"].tap()
            XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 5))
            app.buttons["收起"].tap()
        }
    }

    @MainActor func testNowPlayingLyricsSwitchCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app)
        let row = app.switches["在主标题显示当前歌词"].firstMatch
        for _ in 0..<5 {
            if row.exists && row.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(row.exists && row.isHittable)
        XCTAssertEqual(row.value as? String, "1")
        capture("CarPlay-逐句歌词开启-演示设置", app: app)
        let control = row.switches.firstMatch
        (control.exists ? control : row).tap()
        XCTAssertEqual(row.value as? String, "0")
        capture("CarPlay-逐句歌词关闭-演示设置", app: app)
        (control.exists ? control : row).tap()
        XCTAssertEqual(row.value as? String, "1")
    }

    @MainActor func testAccountCommentsAndAllDiscoveryPagesCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        capture("发现-首页上部-演示数据", app: app)
        app.swipeUp(); capture("发现-首页下部-演示数据", app: app); app.swipeDown()
        for title in ["为你推荐", "音乐榜", "音乐区热门", "新鲜投稿"] {
            let more = app.buttons["查看更多" + title]
            if !more.isHittable { app.swipeUp() }
            more.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("发现-" + title + "-演示数据", app: app)
            app.navigationBars[title].buttons.element(boundBy: 0).tap()
        }
        selectTab("我的", app: app)
        XCTAssertTrue(app.staticTexts["界面示例用户"].waitForExistence(timeout: 5))
        capture("我的-已登录-演示数据", app: app)
        for title in ["收藏夹", "点赞的视频", "观看历史", "我的关注", "我的投稿"] {
            app.buttons[title].tap()
            let pageTitle = title == "我的投稿" ? "UP 主投稿" : title
            XCTAssertTrue(app.navigationBars[pageTitle].waitForExistence(timeout: 5))
            capture("我的-" + title + "-演示数据", app: app)
            if title == "收藏夹" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "音乐收藏 · 示例")).firstMatch.tap()
                XCTAssertTrue(app.navigationBars["收藏夹内容"].waitForExistence(timeout: 5))
                capture("我的-收藏夹内容-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            if title == "我的关注" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例 UP 主")).firstMatch.tap()
                XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5))
                capture("我的-UP主投稿-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            if title == "点赞的视频" {
                app.buttons["播放 晚风里的片刻"].tap()
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.buttons["打开播放页，晚风里的片刻"].tap()
        let comments = app.buttons["player-track-comments"]
        if !comments.isHittable { app.swipeUp() }
        XCTAssertTrue(comments.waitForExistence(timeout: 5)); comments.tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        capture("互动-评论列表-演示数据", app: app)
        app.buttons["点赞视频"].tap()
        XCTAssertTrue(app.buttons["已点赞"].waitForExistence(timeout: 5))
        let draft = app.textFields["发送评论，需要登录"]
        draft.tap(); draft.typeText("UI 演示评论")
        capture("互动-输入评论-演示数据", app: app)
        app.buttons["发送"].tap()
        XCTAssertTrue(app.staticTexts["UI 演示评论"].waitForExistence(timeout: 5))
        capture("互动-发送成功-演示数据", app: app)
        app.buttons["完成"].tap(); app.buttons["收起"].tap()
        selectTab("我的", app: app)
        let signOut = app.buttons["退出登录"]
        for _ in 0..<4 {
            if signOut.frame.maxY >= app.otherElements["mini-player"].firstMatch.frame.minY { app.swipeUp() }
        }
        XCTAssertLessThan(signOut.frame.maxY, app.otherElements["mini-player"].firstMatch.frame.minY)
        capture("我的-底部操作与迷你播放器-演示数据", app: app)
        signOut.tap()
        let login = app.buttons["登录本地示例"]
        XCTAssertTrue(login.waitForExistence(timeout: 5))
        capture("我的-未登录-演示数据", app: app)
        login.tap()
        XCTAssertTrue(app.images["登录二维码"].waitForExistence(timeout: 5))
        capture("登录-二维码-演示数据", app: app)
        app.buttons["取消"].tap()
    }

    @MainActor func testSearchStatesAndSettingsCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        selectTab("搜索", app: app)
        capture("搜索-浏览分类-演示数据", app: app)
        for (query, marker) in [("music", "选择 晚风里的片刻"), ("empty", "无结果"), ("error", "搜索失败"), ("loading", "正在搜索")] {
            let search = app.textFields["搜索视频、音乐、UP 主"]
            if app.buttons["清空搜索"].exists { app.buttons["清空搜索"].tap() }
            search.tap(); search.typeText(query + "\n")
            if query == "music" { XCTAssertTrue(app.buttons[marker].waitForExistence(timeout: 5)) }
            else if query == "empty" { XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "结果")).firstMatch.waitForExistence(timeout: 5)) }
            else { XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 5)) }
            capture("搜索-" + query + "-演示状态", app: app)
        }
        app.buttons["清空搜索"].tap()
        selectTab("设置", app: app)
        capture("设置-上部-演示数据", app: app)
        for key in ["音质", "响度标准化", "缓存上限", "播放器样式", "歌词舞台"] {
            let picker = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", key)).firstMatch
            for _ in 0..<3 { if !picker.isHittable { app.swipeUp() } }
            picker.tap(); capture("设置-" + key + "选择-演示数据", app: app)
            if app.navigationBars.buttons.count > 0 { app.navigationBars.buttons.element(boundBy: 0).tap() }
            else { app.tap() }
        }
        capture("设置-下部-演示数据", app: app)
        let network = app.buttons["网络与系统代理"]
        if !network.isHittable { app.swipeUp() }; network.tap()
        XCTAssertTrue(app.textViews["系统代理域名白名单"].waitForExistence(timeout: 5))
        capture("设置-网络代理-演示数据", app: app)
        app.buttons["完成"].tap()
    }

    @MainActor func testPluginInstallSettingsSourceSwitchAndBrowse() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let pluginLink = app.buttons["settings-plugins"]
        XCTAssertTrue(pluginLink.waitForExistence(timeout: 5))
        XCTAssertTrue(pluginLink.isHittable, "设置首屏不滚动就能打开插件")
        capture("设置-首屏插件入口-演示数据", app: app)
        pluginLink.tap()
        XCTAssertTrue(app.navigationBars["安装插件"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["media.example.test"].exists)
        capture("插件-安装权限-演示数据", app: app)
        app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        capture("插件-列表-演示数据", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.tap()
        let server = app.textFields["服务地址"]
        XCTAssertTrue(server.waitForExistence(timeout: 5))
        let location = app.staticTexts["plugin-install-location"]
        XCTAssertTrue(location.waitForExistence(timeout: 5))
        XCTAssertTrue(location.label.hasSuffix("/installed/fixture"))
        server.tap(); server.typeText("https://media.example.test")
        let hideKeyboard = app.buttons["隐藏键盘"]
        if hideKeyboard.exists && hideKeyboard.isHittable { hideKeyboard.tap() }
        app.switches["启用示例"].tap()
        let save = app.buttons["保存"]
        for _ in 0..<3 {
            if save.exists && save.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(save.exists && save.isHittable)
        save.tap()
        capture("插件-设置-演示数据", app: app)
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let oldPlayer = app.buttons["打开播放页，晚风里的片刻"]
        XCTAssertTrue(oldPlayer.waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let pluginTab = app.buttons["source-tab-fixture"]
        XCTAssertTrue(pluginTab.waitForExistence(timeout: 5))
        let builtInTab = app.buttons["source-tab-demo"]
        let sources = app.scrollViews["source-tabs"]
        let mode = app.buttons["切换播放模式"]
        XCTAssertTrue(builtInTab.isHittable && pluginTab.isHittable, "空间足够时两个音源都可直接点击")
        XCTAssertTrue(sources.isHittable)
        XCTAssertLessThan(abs(pluginTab.frame.midY - mode.frame.midY), 30, "平台标签移动到顶部操作行")
        capture("插件-音源独占整行-演示数据", app: app)
        pluginTab.tap()
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].waitForExistence(timeout: 5))
        XCTAssertTrue(oldPlayer.waitForNonExistence(timeout: 5), "切换平台应清除上一平台暂停中的曲目")
        XCTAssertTrue(pluginTab.isSelected)
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].exists)
        capture("插件-首页-演示数据", app: app)
        let shelf = app.scrollViews["plugin-shelf-0"]
        let firstCard = app.buttons["播放 插件示例歌曲"]
        let secondRowCard = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例专辑")).firstMatch
        XCTAssertGreaterThan(secondRowCard.frame.minY, firstCard.frame.minY, "目录封面使用两行")
        let moreCard = app.buttons["更多示例"]
        shelf.swipeLeft()
        XCTAssertTrue(moreCard.isHittable)
        capture("插件-双行封面横滑到更多", app: app)
        moreCard.tap()
        XCTAssertTrue(app.navigationBars["示例分页"].waitForExistence(timeout: 5))
        capture("插件-分页-演示数据", app: app)
    }

    @MainActor func testCompactRootPagesKeepContentAndActionsVisible() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 10))
        capture("紧凑首页-发现", app: app)
        app.buttons["搜索"].firstMatch.tap()
        let search = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(search.waitForExistence(timeout: 5) && search.isHittable)
        capture("紧凑首页-搜索", app: app)
        selectTab("资料库", app: app)
        XCTAssertTrue(app.buttons["添加到资料库"].waitForExistence(timeout: 5))
        capture("紧凑首页-资料库", app: app)
        selectTab("我的", app: app)
        XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 5))
        capture("紧凑首页-我的", app: app)
    }

    @MainActor func testPluginTrackRowsExplainUnavailableAudio() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--plugin-track-problems", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let pluginLink = app.buttons["settings-plugins"]
        XCTAssertTrue(pluginLink.waitForExistence(timeout: 5)); pluginLink.tap()
        let install = app.buttons["安装"]
        XCTAssertTrue(install.waitForExistence(timeout: 5)); install.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        selectTab("发现", app: app)
        let pluginTab = app.buttons["source-tab-fixture"]
        XCTAssertTrue(pluginTab.waitForExistence(timeout: 5)); pluginTab.tap()
        XCTAssertTrue(pluginTab.isSelected)
        XCTAssertTrue(app.buttons["播放 会员示例曲目"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["播放 会员示例曲目"].value as? String, "需要 VIP")
        let missing = app.buttons["播放 无音源示例曲目"]
        XCTAssertTrue(missing.exists)
        XCTAssertNotEqual(missing.value as? String, "音源不可用")
        missing.tap()
        let play = app.buttons["播放"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5)); play.tap()
        let unavailable = expectation(for: NSPredicate(format: "value == %@", "音源不可用"), evaluatedWith: missing)
        wait(for: [unavailable], timeout: 5)
        capture("插件-曲目不可播原因", app: app)
    }

    @MainActor func testLanguageSwitchUpdatesPagesAndSettings() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["我的"].waitForExistence(timeout: 15))
        selectTab("设置", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语言")).firstMatch.tap()
        app.buttons["English"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Appearance")).firstMatch.exists)
        capture("设置-English", app: app)
        selectTab("Discover", app: app)
        XCTAssertTrue(app.staticTexts["Recommended"].waitForExistence(timeout: 5))
        selectTab("Library", app: app)
        let songs = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Songs")).firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 5))
        songs.tap()
        XCTAssertTrue(app.navigationBars["Songs"].waitForExistence(timeout: 5))
        capture("资料库-English", app: app)
        selectTab("Settings", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Language")).firstMatch.tap()
        app.buttons["简体中文"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        capture("设置-简体中文", app: app)
        app.buttons["dismiss-settings"].tap()
        XCTAssertTrue(app.tabBars.buttons["我的"].isSelected)
    }

    @MainActor func testPlayerStylesAndLibraryPagesCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        capture("首页-演示数据", app: app)
        selectTab("资料库", app: app)
        for title in ["歌曲", "专辑", "作者", "喜欢", "最近播放"] {
            let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            XCTAssertTrue(link.waitForExistence(timeout: 10))
            link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("本地-\(title)-演示数据", app: app)
            if title == "专辑" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "城市的声音 · 示例")).firstMatch.tap()
                XCTAssertTrue(app.buttons["播放 晚风里的片刻"].waitForExistence(timeout: 5))
                capture("本地-专辑详情-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        selectTab("设置", app: app)
        capture("设置-演示数据", app: app)
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["队列"].isHittable)
        capture("播放器-封面-演示数据", app: app)
        for (name, id) in [("黑胶", "vinyl"), ("磁带", "cassette")] {
            app.buttons["播放界面样式"].tap()
            if app.buttons["播放器样式"].exists { app.buttons["播放器样式"].tap() }
            app.buttons[name].tap()
            XCTAssertTrue(app.descendants(matching: .any)["player-artwork-\(id)"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["下一首"].isHittable)
            XCTAssertTrue(app.buttons["队列"].isHittable)
            capture("播放器-\(name)-演示数据", app: app)
        }
        app.buttons["字幕"].tap()
        for (name, id) in [("Flow · 流动", ""), ("Steps · 阶梯", "partita"), ("Tilt · 倾诉", "tilt")] {
            app.buttons["播放界面样式"].tap()
            if app.buttons["歌词舞台"].exists { app.buttons["歌词舞台"].tap() }
            app.buttons[name].tap()
            XCTAssertTrue(app.descendants(matching: .any)["lyric-style-\(id)"].waitForExistence(timeout: 5))
            capture("歌词-\(name)-演示数据", app: app)
        }
        app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["磁带"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["player-artwork-cassette"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["选择歌词 让晚风经过耳边"].exists)
        app.buttons["切换到实时视频"].tap()
        XCTAssertTrue(app.staticTexts["此音源不支持视频播放"].waitForExistence(timeout: 5))
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(app.buttons["播放界面样式"].waitForExistence(timeout: 5))
        app.buttons["播放界面样式"].tap(); app.buttons["歌词舞台"].tap(); app.buttons["Tilt · 倾诉"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["lyric-style-tilt"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["此音源不支持视频播放"].exists)
        app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["封面"].tap()
        XCTAssertTrue(app.buttons["切换到实时视频"].waitForExistence(timeout: 5))
        capture("播放器-歌词样式立即展示并返回封面-演示数据", app: app)
    }

    @MainActor func testLocalLibraryPlaylistCreateAddRemoveAndRename() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let libraryTab = app.tabBars.buttons["资料库"]
        XCTAssertTrue(libraryTab.waitForExistence(timeout: 15))
        libraryTab.tap()
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "晚间散步 · 示例")).firstMatch
        XCTAssertTrue(playlist.waitForExistence(timeout: 10))
        capture("资料库-演示数据", app: app)
        app.buttons["新建歌单"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("UI 回归歌单")
        capture("歌单-创建弹窗-演示数据", app: app)
        app.alerts.buttons["创建"].tap()
        let created = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "UI 回归歌单")).firstMatch
        XCTAssertTrue(created.waitForExistence(timeout: 5))
        created.tap()
        app.buttons["添加歌曲"].tap()
        let addTrack = app.buttons["选择 晚风里的片刻"]
        XCTAssertTrue(addTrack.waitForExistence(timeout: 5))
        addTrack.tap()
        capture("添加歌曲-演示数据", app: app)
        app.buttons["添加 1 首"].tap()
        let row = app.buttons["播放 晚风里的片刻"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        capture("新建歌单内容-演示数据", app: app)
        app.buttons["曲目操作 晚风里的片刻"].tap()
        capture("歌单-曲目菜单-演示数据", app: app)
        app.buttons["从歌单移除"].tap()
        XCTAssertTrue(app.staticTexts["暂无歌曲"].waitForExistence(timeout: 5))
        app.navigationBars["UI 回归歌单"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(created.waitForExistence(timeout: 5))
        app.buttons["歌单操作 UI 回归歌单"].tap()
        app.buttons["重命名"].tap()
        capture("歌单-重命名弹窗-演示数据", app: app)
        let rename = app.alerts.textFields.firstMatch
        XCTAssertTrue(rename.waitForExistence(timeout: 5))
        rename.tap(); rename.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "UI 回归歌单".count) + "已重命名")
        app.alerts.buttons["保存"].tap()
        let renamed = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "已重命名")).firstMatch
        XCTAssertTrue(renamed.waitForExistence(timeout: 5))
        app.buttons["歌单操作 已重命名"].tap()
        app.buttons["删除歌单"].tap()
        capture("歌单-删除确认-演示数据", app: app)
        app.buttons["删除歌单"].tap()
        XCTAssertTrue(renamed.waitForNonExistence(timeout: 5))
    }

    @MainActor func testPluginCheckPresentationRequiresConfirmationAndRecoversFromFailure() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-check-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        let github = app.buttons["plugin-install-github"]
        let checking = app.staticTexts["plugin-checking"]
        let install = app.buttons["安装"]
        for repo in ["fixture/failure", "fixture/success"] {
            XCTAssertTrue(github.waitForExistence(timeout: 5))
            github.tap()
            let alert = app.alerts.firstMatch
            let field = alert.textFields.firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            field.tap()
            if repo == "fixture/success" { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "fixture/failure".count)) }
            field.typeText(repo)
            alert.buttons["检查"].tap()
            XCTAssertTrue(checking.waitForExistence(timeout: 3))
            XCTAssertFalse(github.isHittable)
            XCTAssertFalse(install.exists)
            capture("插件-居中检查-\(repo.hasSuffix("failure") ? "失败路径" : "成功路径")", app: app)
            XCTAssertTrue(checking.waitForNonExistence(timeout: 10))
            if repo == "fixture/failure" {
                XCTAssertTrue(app.staticTexts["插件检查失败"].exists)
                XCTAssertFalse(install.exists)
                capture("插件-检查失败弹窗", app: app)
                app.buttons["dismiss-plugin-check-error"].tap()
            } else {
                XCTAssertTrue(install.waitForExistence(timeout: 5))
                capture("插件-权限确认", app: app)
                app.buttons["取消"].tap()
                XCTAssertTrue(install.waitForNonExistence(timeout: 5))
            }
        }
        XCTAssertTrue(github.isHittable)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.exists)
    }

    @MainActor func testPlatformNodeRequiresAddressAndPassword() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let address = app.textFields["HTTPS 节点地址"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        XCTAssertTrue(app.secureTextFields["节点密码"].exists)
        XCTAssertFalse(app.buttons["接入节点"].isEnabled)
        capture("设置-MB-Server-未接入", app: app)
    }

    @MainActor func testPluginsStarsImportAsksForAPublicList() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        let open = app.buttons["plugin-import-stars"]
        XCTAssertTrue(open.waitForExistence(timeout: 5)); open.tap()
        let field = app.textFields["plugin-stars-address"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("https://example.test/list")
        app.buttons["plugin-stars-read"].tap()
        let problem = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "请输入公开的 GitHub 星标列表地址")).firstMatch
        XCTAssertTrue(problem.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["plugin-stars-confirm"].exists)
        capture("插件-星标列表导入弹窗", app: app)
        app.buttons["关闭"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 5))
    }

    @MainActor func testDiscoverySwipeAndPluginLocalNamePropagate() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].press(forDuration: 0.15)
        let plugin = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch
        XCTAssertTrue(plugin.waitForExistence(timeout: 5)); plugin.press(forDuration: 0.15)
        app.buttons["plugin-local-name"].press(forDuration: 0.15)
        let name = app.alerts.textFields.firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 3)); name.tap(); name.typeText("My Platform")
        app.alerts.buttons["保存"].press(forDuration: 0.15)
        selectTab("发现", app: app)
        let target = app.buttons["source-tab-fixture"]
        XCTAssertTrue(target.waitForExistence(timeout: 5)); XCTAssertEqual(target.label, "My Platform")
        XCTAssertFalse(app.buttons["搜索音源"].exists)
        // iPad 有侧栏，分页手势限定在内容顶部，避免落入卡片横滚区域或侧栏。
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let surface: XCUIElement = isPad ? app.descendants(matching: .any)["discovery-platform-pager"].firstMatch : app
        XCTAssertTrue(surface.waitForExistence(timeout: 5))
        let start = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: isPad ? 0.02 : 0.18))
        let end = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: isPad ? 0.02 : 0.18))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].waitForExistence(timeout: 5))
        XCTAssertTrue(target.isSelected)
        chooseMode("视频模式", app: app)
        capture("发现-顶部平台与点击切换模式", app: app)
        chooseMode("听歌模式", app: app)
        let more = app.buttons["查看更多示例曲目"]
        XCTAssertEqual(app.buttons.matching(identifier: "查看更多示例曲目").count, 1)
        more.press(forDuration: 0.15)
        XCTAssertTrue(app.navigationBars["相同导航完整内容"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).press(forDuration: 0.15)
        selectTab("搜索", app: app)
        XCTAssertTrue(app.buttons["search-platform-picker"].label.contains("My Platform"))
    }

    @MainActor func testUnifiedSearchPlatformMenuAndResourceBookmark() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].press(forDuration: 0.15)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        selectTab("发现", app: app)
        app.buttons["source-tab-fixture"].press(forDuration: 0.15)
        selectTab("搜索", app: app)
        XCTAssertFalse(app.buttons["切换播放模式"].exists)
        XCTAssertFalse(app.segmentedControls["search-scope"].exists)
        let picker = app.buttons["search-platform-picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        XCTAssertTrue(picker.label.contains("示例插件"))
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("music\n")
        let music = app.buttons["选择 music · 插件结果"]
        let video = app.buttons["选择 music · 视频结果"]
        XCTAssertTrue(music.waitForExistence(timeout: 8)); XCTAssertTrue(video.exists)
        capture("统一搜索-平台类型与封面", app: app)
        app.buttons["曲目操作 music · 插件结果"].press(forDuration: 0.15)
        app.buttons["保存到资料库"].press(forDuration: 0.15)
        selectTab("资料库", app: app)
        XCTAssertTrue(app.staticTexts["已保存资源"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["music · 插件结果"].exists)
        selectTab("搜索", app: app)
        picker.press(forDuration: 0.15)
        XCTAssertTrue(app.buttons["全平台搜索"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["磁力链搜索"].exists)
        let choices = app.descendants(matching: .any)["search-platform-options"].firstMatch
        XCTAssertTrue(choices.waitForExistence(timeout: 3))
        XCTAssertGreaterThanOrEqual(choices.frame.minY, field.frame.maxY, "下拉选项不能遮挡搜索框")
        capture("统一搜索-平台下拉", app: app)
        app.buttons["全平台搜索"].press(forDuration: 0.15)
        XCTAssertTrue(music.waitForExistence(timeout: 5))
    }

    @MainActor func testPluginEntitiesPlaylistSelectionAndManagementCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["我的"].waitForExistence(timeout: 15))
        selectTab("设置", app: app)
        let plugins = app.buttons["settings-plugins"]
        for _ in 0..<5 { if !plugins.isHittable { app.swipeUp() } }; plugins.tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.tap()
        app.switches["启用音源"].tap()
        capture("插件-停用音源-演示数据", app: app)
        app.switches["启用音源"].tap()
        selectTab("发现", app: app)
        app.buttons["source-tab-fixture"].tap()
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].waitForExistence(timeout: 5))
        for (title, detail) in [("示例专辑", "专辑"), ("示例作者", "作者"), ("示例播客", "播客"), ("示例电台", "电台")] {
            let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            for _ in 0..<3 { if !link.isHittable { app.swipeUp() } }
            link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-" + detail + "详情-演示数据", app: app)
            if detail == "专辑" { app.buttons["收藏专辑"].tap(); XCTAssertTrue(app.buttons["取消收藏专辑"].waitForExistence(timeout: 5)) }
            if detail == "作者" { app.buttons["关注作者"].tap(); XCTAssertTrue(app.buttons["取消关注作者"].waitForExistence(timeout: 5)) }
            if detail == "播客" { app.buttons["订阅播客"].tap(); XCTAssertTrue(app.buttons["取消订阅"].waitForExistence(timeout: 5)) }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例歌单")).firstMatch
        if !playlist.isHittable { app.swipeDown() }; playlist.tap()
        XCTAssertTrue(app.buttons["添加平台歌曲"].waitForExistence(timeout: 5)); app.buttons["添加平台歌曲"].tap()
        let field = app.textFields["搜索音源"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("music\n")
        XCTAssertTrue(app.buttons["选择 music · 插件结果"].waitForExistence(timeout: 5))
        app.buttons["选择 music · 插件结果"].tap()
        capture("插件-歌单搜索选歌-演示数据", app: app)
        app.buttons["添加 1 首"].tap()
        XCTAssertTrue(app.buttons["播放 music · 插件结果"].waitForExistence(timeout: 5))
        capture("插件-歌单内容-演示数据", app: app)
        app.buttons["曲目操作 music · 插件结果"].tap()
        capture("插件-歌单曲目菜单-演示数据", app: app)
        app.buttons["从歌单移除"].tap()
        XCTAssertTrue(app.buttons["播放 music · 插件结果"].waitForNonExistence(timeout: 5))
        app.buttons["重命名歌单"].tap(); capture("插件-重命名歌单-演示数据", app: app); app.buttons["取消"].tap()
        app.buttons["删除平台歌单"].tap(); capture("插件-删除歌单确认-演示数据", app: app); app.buttons["取消"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        selectTab("我的", app: app)
        for title in ["平台歌单", "喜欢的歌曲", "最近播放", "收藏专辑", "订阅播客", "电台"] {
            let link = app.buttons[title]
            if !link.isHittable { app.swipeUp() }; link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-我的-" + title + "-演示数据", app: app)
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
    }

    @MainActor func testLibrarySystemSheetsQueueEditAndEffectsCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["资料库"].waitForExistence(timeout: 15)); selectTab("资料库", app: app)
        app.buttons["添加到资料库"].tap(); capture("本地-添加菜单-演示数据", app: app)
        app.buttons["导入音频"].tap()
        let cancel = app.descendants(matching: .any).matching(identifier: "取消").firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); capture("本地-导入音频系统选择器", app: app); try dismissDocumentPicker(app)
        app.buttons["添加到资料库"].tap(); app.buttons["导入文件夹"].tap()
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); capture("本地-导入文件夹系统选择器", app: app); try dismissDocumentPicker(app)
        let songs = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "歌曲")).firstMatch
        songs.tap()
        app.buttons["曲目操作 晚风里的片刻"].tap(); capture("本地-曲目菜单-演示数据", app: app)
        app.buttons["导出音频"].tap()
        capture("本地-导出音频分享-演示数据", app: app)
        let shareClose = app.buttons["关闭"].firstMatch
        if shareClose.waitForExistence(timeout: 5) {
            shareClose.tap()
        } else {
            app.swipeDown()
            if shareClose.waitForExistence(timeout: 3) { shareClose.tap() }
        }
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        app.buttons["队列"].tap(); app.buttons["编辑"].tap(); capture("播放-编辑队列-演示数据", app: app)
        app.buttons["完成"].firstMatch.tap()
        if app.buttons["完成"].exists { app.buttons["完成"].firstMatch.tap() }
        if app.buttons["收起"].exists { app.buttons["收起"].tap() }
        selectTab("设置", app: app)
        let spark = app.switches["歌词火花"]
        for _ in 0..<3 { if !spark.isHittable { app.swipeUp() } }
        capture("设置-播放效果开关-演示数据", app: app)
        for title in ["歌词火花", "默认打开歌词", "间奏显示热评", "进度条烟雾", "低音雾气背景", "从封面展开"] {
            let row = app.switches[title].firstMatch
            XCTAssertTrue(row.exists)
            let control = row.switches.firstMatch
            let target = control.exists ? control : row
            if target.value as? String != "1" { target.tap() }
            XCTAssertEqual(row.value as? String, "1")
        }
        capture("设置-播放效果已开启-演示数据", app: app)
        app.buttons["dismiss-settings"].tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        XCTAssertTrue(app.buttons["选择歌词 让晚风经过耳边"].waitForExistence(timeout: 5))
        capture("播放-默认歌词舞台-演示数据", app: app)
    }

    @MainActor func testGuestArtworkOpensLiveVideoAndReturnsToMusic() throws {
        guard ProcessInfo.processInfo.environment["MB_LIVE_MEDIA_UI"] == "1" else {
            throw XCTSkip("真实游客联网验证仅在显式开启 MB_LIVE_MEDIA_UI 时执行")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 30))
        capture("真实游客-首页", app: app)
        selectTab("搜索", app: app)
        let search = app.textFields["搜索视频、音乐、UP 主"]
        search.tap(); search.typeText("夜曲\n")
        let result = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 30))
        capture("真实游客-搜索结果", app: app)
        chooseMode("听歌模式", app: app)
        result.tap()
        let mini = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
        XCTAssertTrue(mini.waitForExistence(timeout: 10)); mini.tap()
        let controls = app.otherElements["player-controls"]
        let pause = controls.buttons["暂停"]
        XCTAssertTrue(pause.waitForExistence(timeout: 45)); pause.tap()
        // 暂停后操作样式，避免 XCTest 等待持续播放动画时跨到下一首。
        if !app.buttons["切换到实时视频"].exists {
            app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["封面"].tap()
        }
        app.buttons["切换到实时视频"].tap()
        let picture = app.descendants(matching: .any)["视频画面"].firstMatch
        XCTAssertTrue(picture.waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["正在缓冲"].waitForNonExistence(timeout: 45))
        let frameReady = expectation(for: NSPredicate(format: "value == %@", "视频画面已显示"), evaluatedWith: picture)
        wait(for: [frameReady], timeout: 45)
        capture("真实游客-点击封面实时视频", app: app)
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(app.buttons["切换到实时视频"].waitForExistence(timeout: 5))
        app.buttons["观看当前视频"].tap()
        XCTAssertTrue(picture.waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["正在缓冲"].waitForNonExistence(timeout: 45))
        let secondFrameReady = expectation(for: NSPredicate(format: "value == %@", "视频画面已显示"), evaluatedWith: picture)
        wait(for: [secondFrameReady], timeout: 45)
        capture("真实游客-再次切换视频", app: app)
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(controls.buttons["播放"].waitForExistence(timeout: 5))
        capture("真实游客-切回封面并暂停", app: app)
    }

    @MainActor func testAccessiblePlayerAndNavigationCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        for title in ["发现", "搜索", "资料库", "我的"] {
            selectTab(title, app: app)
            let mini = app.otherElements["mini-player"].firstMatch
            XCTAssertTrue(mini.waitForExistence(timeout: 5))
            XCTAssertFalse(mini.frame.intersects(app.tabBars.firstMatch.frame))
            XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].isHittable)
            capture("外观与字体-\(title)-演示数据", app: app)
        }
        app.buttons["打开播放页，晚风里的片刻"].tap()
        for label in ["下一首", "字幕", "队列"] {
            let control = app.buttons[label]
            for _ in 0..<4 { if !control.isHittable { app.swipeUp() } }
            XCTAssertTrue(control.isHittable)
        }
        capture("外观与字体-播放器-演示数据", app: app)
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["选择歌词 让晚风经过耳边"].exists)
        capture("外观与字体-歌词-演示数据", app: app)
        for _ in 0..<4 { if !app.buttons["收起"].isHittable { app.swipeDown() } }
        app.buttons["收起"].tap()
        selectTab("设置", app: app)
        capture("外观与字体-设置-演示数据", app: app)
    }

    @MainActor private func selectTab(_ title: String, app: XCUIApplication) {
        if title == "设置" || title == "Settings" {
            if app.buttons["dismiss-settings"].exists { return }
            selectTab(title == "设置" ? "我的" : "Account", app: app)
            let button = app.buttons["open-settings"]
            XCTAssertTrue(button.waitForExistence(timeout: 5)); button.press(forDuration: 0.15)
            XCTAssertTrue(app.buttons["dismiss-settings"].waitForExistence(timeout: 5))
            return
        }
        if app.descendants(matching: .any)["settings-sheet"].exists {
            for _ in 0..<5 {
                if app.buttons["dismiss-settings"].isHittable { break }
                let back = app.navigationBars.buttons["BackButton"].firstMatch
                if back.exists { back.press(forDuration: 0.15) }
            }
        }
        if app.buttons["dismiss-settings"].exists { app.buttons["dismiss-settings"].press(forDuration: 0.15) }
        if app.buttons["dismiss-plugins"].exists { app.buttons["dismiss-plugins"].press(forDuration: 0.15) }
        let tab = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons[title].firstMatch : app.tabBars.buttons[title].firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
    }

    @MainActor private func dismissDocumentPicker(_ app: XCUIApplication) throws {
        let button = app.buttons["取消"].firstMatch
        if button.waitForExistence(timeout: 3) { button.tap() }
        else {
            let other = app.otherElements["取消"].firstMatch
            XCTAssertTrue(other.waitForExistence(timeout: 5))
            let frame = other.frame
            XCTAssertGreaterThan(frame.width, 0)
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
        }
        let ready = expectation(for: NSPredicate { _, _ in app.buttons["添加到资料库"].isHittable }, evaluatedWith: app)
        wait(for: [ready], timeout: 5)
    }

    @MainActor private func capture(_ name: String, app: XCUIApplication) {
        // 采集屏幕坐标，避免 iPad 旋转后应用截图仍沿用旧方向而裁掉右侧。
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    @MainActor func testDiscoveryQueueModesAndProxyValidation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let more = app.buttons["查看更多为你推荐"]
        XCTAssertTrue(more.waitForExistence(timeout: 15))
        more.tap()
        let first = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        first.tap()
        let mini = app.buttons["打开播放页，晚风里的片刻"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        mini.tap()
        app.buttons["队列"].tap()
        XCTAssertTrue(app.navigationBars["播放队列 · 3"].waitForExistence(timeout: 5))
        capture("播放-队列-演示数据", app: app)
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["选择歌词 让晚风经过耳边"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        chooseMode("视频模式", app: app)
        XCTAssertTrue(first.isHittable, "有暂停曲目时切模式不应跳转")
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        XCTAssertTrue(app.buttons["打开播放页，城市慢下来"].exists)
        capture("视频-推荐浏览-演示数据", app: app)
        first.tap()
        XCTAssertTrue(app.buttons["全屏播放"].waitForExistence(timeout: 5))
        capture("视频-简介-演示数据", app: app)
        app.buttons["评论"].tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.sliders["视频播放进度"].isHittable)
        capture("视频-评论固定画面-演示数据", app: app)
        app.buttons["队列"].tap()
        XCTAssertTrue(app.buttons["队列选择 城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["全屏播放"].tap()
        XCTAssertTrue(app.buttons["退出全屏"].waitForExistence(timeout: 5))
        capture("视频-横屏全屏-演示状态", app: app)
        app.buttons["退出全屏"].tap()
        XCTAssertTrue(app.buttons["返回浏览"].waitForExistence(timeout: 5))
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(app.navigationBars["为你推荐"].waitForExistence(timeout: 5))
        XCTAssertTrue(first.exists)
        chooseMode("听歌模式", app: app)
        XCTAssertTrue(first.isHittable)
        selectTab("设置", app: app)
        let network = app.buttons["网络与系统代理"]
        for _ in 0..<5 { if !network.isHittable { app.swipeUp() } }
        network.tap()
        let editor = app.textViews["系统代理域名白名单"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText("https://bad.example")
        app.buttons["保存"].tap()
        XCTAssertTrue(app.staticTexts["请输入域名或 *.域名；不能包含 URL、端口、账号或全局通配符"].waitForExistence(timeout: 5))
        capture("网络-校验错误-演示数据", app: app)
        app.buttons["完成"].tap()
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "iOS-首页与迷你播放器"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        selectTab("搜索", app: app)
        let search = app.textFields["搜索视频、音乐、UP 主"]
        if !search.isHittable { app.swipeDown() }
        search.tap()
        search.typeText("music\n")
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].waitForExistence(timeout: 5))
        chooseMode("视频模式", app: app)
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].isHittable)
        XCTAssertFalse(app.buttons["返回浏览"].exists)
        app.buttons["选择 晚风里的片刻"].tap()
        XCTAssertTrue(app.staticTexts["此音源不支持视频播放"].waitForExistence(timeout: 5))
    }

    @MainActor private func chooseMode(_ label: String, app: XCUIApplication) {
        let toggle = app.buttons["切换播放模式"]
        if toggle.exists {
            if toggle.value as? String != label { toggle.press(forDuration: 0.15) }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", label), object: toggle)], timeout: 3), .completed)
        } else if app.buttons[label].firstMatch.isHittable { app.buttons[label].firstMatch.press(forDuration: 0.15) }
    }

    @MainActor func testTabletRotationKeepsBrowserAndPlayer() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("仅验证平板布局") }
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        for orientation in [UIDeviceOrientation.landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
            XCTAssertTrue(card.waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["首页"].isHittable)
            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshot.name = orientation == .portrait ? "iPad-竖屏" : "iPad-横屏"
            screenshot.lifetime = .keepAlways
            add(screenshot)
            app.buttons["为你推荐"].firstMatch.tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.waitForExistence(timeout: 5))
            capture(orientation == .portrait ? "iPad-竖屏-推荐页" : "iPad-横屏-推荐页", app: app)
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
            app.buttons["打开播放页，晚风里的片刻"].tap()
            XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
            capture(orientation == .portrait ? "iPad-竖屏-播放器" : "iPad-横屏-播放器", app: app)
            app.buttons["收起"].tap()
            app.buttons["首页"].tap()
        }
    }

    @MainActor func testTabletVideoDetailsFollowOrientation() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("仅验证平板布局") }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        XCUIDevice.shared.orientation = .portrait
        chooseMode("视频模式", app: app)
        let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(card.isHittable)
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        card.tap()
        let seek = app.sliders["视频播放进度"]
        let comments = app.buttons["评论"]
        XCTAssertTrue(comments.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(comments.frame.midY, seek.frame.maxY)
        capture("iPad-竖屏-视频简介-演示数据", app: app)
        comments.tap()
        let comment = app.staticTexts["慢下来，听见晚风里的片刻。"]
        XCTAssertTrue(comment.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(comment.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(comments.frame.minX, seek.frame.maxX)
        capture("iPad-横屏-视频评论-演示数据", app: app)
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .portrait
    }
}

extension MBiOSUITests {
    @MainActor func testPlayerModeLabelsAndLandscapeDesktopStages() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
            app.buttons["为你推荐"].firstMatch.tap()
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let open = app.buttons["打开播放页，晚风里的片刻"]
        XCTAssertTrue(open.waitForExistence(timeout: 5)); open.tap()
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
        for title in ["歌词", "评论点赞", "音频输出", "待播清单"] { XCTAssertTrue(app.staticTexts[title].exists, "底部小字：\(title)") }
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为随机播放"].waitForExistence(timeout: 3))
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为顺序播放"].waitForExistence(timeout: 3))
        app.buttons["顺序播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为列表循环"].waitForExistence(timeout: 3))
        app.buttons["列表循环"].tap()
        XCTAssertTrue(app.staticTexts["已切换为单曲循环"].waitForExistence(timeout: 3))
        capture("播放器-中文模式与底部小字", app: app)
        // iPad 直接验证设备旋转，避免按钮强制方向掩盖窄弹层问题；iPhone 另验证主动横屏入口。
        if UIDevice.current.userInterfaceIdiom == .phone, app.buttons["横屏播放"].exists {
            app.buttons["横屏播放"].tap()
        }
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.buttons["wide-player-lyrics"].waitForExistence(timeout: 8))
        for section in ["lyrics", "rhythm", "queue", "selection", "comments"] {
            let tab = app.buttons["wide-player-" + section]
            XCTAssertTrue(tab.isHittable); tab.tap()
            if section == "lyrics" { XCTAssertTrue(app.buttons["选择歌词 让晚风经过耳边"].waitForExistence(timeout: 5)) }
            if section == "queue" { XCTAssertTrue(app.buttons["队列选择 城市慢下来"].waitForExistence(timeout: 5)) }
            if section == "selection" {
                XCTAssertTrue(app.buttons["选歌 城市慢下来"].waitForExistence(timeout: 5))
                app.buttons["选歌 城市慢下来"].tap()
                XCTAssertTrue(app.staticTexts["城市慢下来"].exists)
            }
            if section == "comments" {
                let comment = app.staticTexts["慢下来，听见晚风里的片刻。"]
                XCTAssertTrue(comment.waitForExistence(timeout: 5)); XCTAssertTrue(comment.isHittable)
            }
            XCTAssertTrue(app.buttons["下一首"].isHittable)
            capture("横屏播放器-" + section, app: app)
        }
        if UIDevice.current.userInterfaceIdiom == .phone, app.buttons["竖屏播放"].exists {
            app.buttons["竖屏播放"].tap()
        }
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 8))
        app.buttons["收起"].tap()
        XCTAssertTrue(app.buttons["打开播放页，城市慢下来"].waitForExistence(timeout: 5))
    }
}

extension MBiOSUITests {
    @MainActor func testPullRefreshReplacesPluginHomeAndIndependentShelf() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--refresh-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 8))
        selectTab("发现", app: app)
        let source = app.buttons["source-tab-fixture"]
        XCTAssertTrue(source.waitForExistence(timeout: 8)); source.tap()
        XCTAssertTrue(app.buttons["播放 首页第1次"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "栏目第1次")).firstMatch.waitForExistence(timeout: 8))
        let scroll = app.scrollViews["plugin-discovery-scroll"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 8))
        capture("下拉刷新-拉动前", app: app)
        scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.08)).press(forDuration: 0.05,
            thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.85)))
        capture("下拉刷新-拉动后", app: app)
        XCTAssertTrue(app.buttons["播放 首页第2次"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "栏目第2次")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["播放 首页第1次"].exists)
        capture("下拉刷新-首页和独立栏目更新", app: app)
    }

    @MainActor func testVideoQualitySelectionAppliesToFollowingVideo() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchEnvironment["MB_VIDEO_UI_FIXTURE"] = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Tests/MBFoundationTests/Fixtures/video-ui.mp4").path
        app.launch()
        if UIDevice.current.userInterfaceIdiom != .pad {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 8)); app.buttons["查看更多为你推荐"].tap()
        }
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let quality = app.buttons["video-quality"]
        XCTAssertTrue(quality.waitForExistence(timeout: 12)); quality.tap()
        let option = app.buttons["video-quality-720p"]
        XCTAssertTrue(option.waitForExistence(timeout: 5)); option.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "首选 720p，当前 720p"), object: quality)], timeout: 12), .completed)
        app.buttons["下一首"].tap()
        XCTAssertTrue(quality.waitForExistence(timeout: 12))
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "首选 720p，当前 720p"), object: quality)], timeout: 12), .completed)
        capture("视频画质-选择后下一条沿用", app: app)
    }

    @MainActor func testReadyVideoReachesTopAndReturningToFloatingWindowStaysResponsive() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchEnvironment["MB_VIDEO_UI_FIXTURE"] = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Tests/MBFoundationTests/Fixtures/video-ui.mp4").path
        XCUIDevice.shared.orientation = .portrait; app.launch()
        if UIDevice.current.userInterfaceIdiom != .pad {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 10))
            app.buttons["查看更多为你推荐"].tap()
        }
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let surface = app.otherElements["native-video-surface"]
        XCTAssertTrue(surface.waitForExistence(timeout: 12), "必须加载真实视频画面，空态不能覆盖播放器交接问题")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "视频画面已显示"), object: surface)], timeout: 12), .completed)
        app.buttons["播放"].tap()
        XCTAssertTrue(app.buttons["暂停"].waitForExistence(timeout: 8))
        capture("真实视频-返回前", app: app)
        for round in 0..<3 {
            app.buttons["返回浏览"].tap()
            XCTAssertTrue(app.buttons["展开视频"].waitForExistence(timeout: 6), "第 \(round + 1) 次返回应直接响应")
            XCTAssertTrue(surface.waitForExistence(timeout: 6))
            if round == 0 { capture("真实视频-返回后小窗", app: app) }
            app.buttons["展开视频"].tap()
            XCTAssertTrue(app.buttons["简介"].waitForExistence(timeout: 6))
            let top = surface.frame.minY
            XCTAssertLessThanOrEqual(top, app.frame.minY + 1, "视频内容应延伸到屏幕顶部，不留下原安全区黑边")
        }
        app.buttons["返回浏览"].tap()
        app.buttons["关闭视频小窗"].tap()
        XCTAssertTrue(surface.waitForNonExistence(timeout: 5))
        app.buttons["搜索"].firstMatch.tap()
        XCTAssertTrue(app.scrollViews["search-landing"].waitForExistence(timeout: 5))
    }

    @MainActor func testVideoDetailsSwipeAndFloatingWindowReturnExpandAndClose() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        if UIDevice.current.userInterfaceIdiom != .pad {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 10))
            app.buttons["查看更多为你推荐"].tap()
        }
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let info = app.buttons["简介"], comments = app.buttons["评论"], captions = app.buttons["字幕"], queue = app.buttons["队列"]
        XCTAssertTrue(info.waitForExistence(timeout: 5))
        let top = info.frame.maxY + 70
        func swipe(left: Bool) {
            let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.width * (left ? 0.8 : 0.2), dy: top))
            let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.width * (left ? 0.2 : 0.8), dy: top))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        swipe(left: true)
        XCTAssertTrue(comments.isSelected)
        swipe(left: true); XCTAssertTrue(captions.isSelected)
        swipe(left: true); XCTAssertTrue(queue.isSelected)
        swipe(left: false); XCTAssertTrue(captions.isSelected)
        info.tap(); XCTAssertTrue(info.isSelected)
        capture("视频-详情左右滑动", app: app)
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(app.buttons["展开视频"].waitForExistence(timeout: 5))
        capture("视频-应用内悬浮小窗", app: app)
        app.buttons["展开视频"].tap()
        XCTAssertTrue(info.waitForExistence(timeout: 5))
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(app.buttons["关闭视频小窗"].waitForExistence(timeout: 5))
        app.buttons["关闭视频小窗"].tap()
        XCTAssertTrue(app.buttons["展开视频"].waitForNonExistence(timeout: 5))
    }

    @MainActor func testVideoHidesStatusBarAndRestoresItAfterBrowsing() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        // iOS 26 把状态栏空容器保留在 SpringBoard，实际可见内容才表示显示。
        let statusBar = XCUIApplication(bundleIdentifier: "com.apple.springboard").statusBars.descendants(matching: .any).firstMatch
        XCTAssertTrue(statusBar.waitForExistence(timeout: 5))
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["video-open-music"].waitForExistence(timeout: 5))
        XCTAssertTrue(statusBar.waitForNonExistence(timeout: 5))
        capture("视频-详情隐藏系统状态栏", app: app)
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(statusBar.waitForExistence(timeout: 5))
        capture("视频-返回浏览恢复系统状态栏", app: app)
    }

    @MainActor func testVideoSingleTapHidesControlsDoubleTapKeepsChromeAndSmallSeekWorks() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let seek = app.sliders["视频播放进度"]
        XCTAssertTrue(seek.waitForExistence(timeout: 5)); XCTAssertTrue(seek.isHittable)
        seek.adjust(toNormalizedSliderPosition: 0.4)
        capture("视频-细进度条", app: app)
        let tap = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: seek.frame.midX, dy: seek.frame.minY - 40))
        tap.tap(); XCTAssertTrue(seek.waitForNonExistence(timeout: 4))
        tap.doubleTap(); XCTAssertFalse(seek.exists, "双击不应同时触发单击唤出控制条")
        tap.tap(); XCTAssertTrue(seek.waitForExistence(timeout: 4))
        app.buttons["全屏播放"].tap()
        XCTAssertTrue(app.buttons["退出全屏"].waitForExistence(timeout: 5))
        let fullscreenSeek = app.otherElements["fullscreen-video"].sliders["视频播放进度"]
        XCTAssertTrue(fullscreenSeek.waitForExistence(timeout: 5))
        let centre = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        centre.tap(); XCTAssertTrue(fullscreenSeek.waitForNonExistence(timeout: 4))
        capture("视频-全屏单击隐藏控制条", app: app)
        centre.doubleTap(); XCTAssertFalse(fullscreenSeek.exists)
        centre.tap(); XCTAssertTrue(fullscreenSeek.waitForExistence(timeout: 4))
        capture("视频-全屏单击唤出控制条", app: app)
        app.buttons["退出全屏"].tap()
        XCUIDevice.shared.orientation = .portrait
    }
}

extension MBiOSUITests {
    @MainActor func testBottomPaginationLoadsSearchAndPersonalLibraryWithoutButton() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "--demo-pagination", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap(); field.typeText("分页\n")
        XCTAssertTrue(app.buttons["选择 分页示例 1"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["加载更多"].exists)
        let nextSearch = app.buttons["选择 分页示例 19"]
        for _ in 0..<8 {
            if nextSearch.exists && nextSearch.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(nextSearch.waitForExistence(timeout: 5))
        capture("分页-搜索触底自动追加", app: app)
        selectTab("我的", app: app)
        app.buttons["点赞的视频"].tap()
        XCTAssertTrue(app.buttons["播放 分页示例 1"].waitForExistence(timeout: 5))
        let nextLibrary = app.buttons["播放 分页示例 19"]
        for _ in 0..<8 {
            if nextLibrary.exists && nextLibrary.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(nextLibrary.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["加载更多"].exists)
        capture("分页-个人资源触底自动追加", app: app)
    }

    @MainActor func testPlayerActionCaptionsShareOneBaseline() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        let captions = ["歌词", "待播清单"].map { app.staticTexts[$0] }
        XCTAssertTrue(captions[0].waitForExistence(timeout: 5))
        let baseline = captions[0].frame.midY
        for caption in captions { XCTAssertEqual(caption.frame.midY, baseline, accuracy: 1) }
        let route = app.descendants(matching: .any)["player-audio-output"].firstMatch
        XCTAssertTrue(route.exists)
        // 底部歌词、输出和队列保留一致图标槽；评论已移到歌曲信息右侧。
        XCTAssertEqual(route.frame.midY, app.buttons["字幕"].frame.minY + 14, accuracy: 4)
        capture("播放-音频输出与操作图标对齐", app: app)
    }
}
