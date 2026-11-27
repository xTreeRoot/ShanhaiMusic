import XCTest
import UIKit

final class MBiOSUITests: XCTestCase {
    @MainActor func testLocalPlaylistRevealsPausedCurrentTrack() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--playlist-focus-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("资料库", app: app)
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "晚间散步 · 示例")).firstMatch
        XCTAssertTrue(playlist.waitForExistence(timeout: 15)); playlist.tap()
        let target = app.buttons["播放 定位曲目 36"]
        let revealed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in target.exists && target.isHittable }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [revealed], timeout: 8), .completed)
        capture("本地歌单-自动定位暂停曲目", app: app)
        app.swipeDown(); app.swipeDown(); app.swipeDown()
        XCTAssertFalse(target.isHittable, "用户滚动后不能再次拉回当前曲目")
    }

    @MainActor func testPluginPlaylistRevealsPausedCurrentTrack() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--playlist-focus-plugin", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        selectTab("发现", app: app)
        let source = app.buttons["source-tab-fixture"]
        XCTAssertTrue(source.waitForExistence(timeout: 5)); source.tap()
        let playlist = app.buttons["示例歌单"].firstMatch
        if !playlist.isHittable { app.swipeUp() }
        XCTAssertTrue(playlist.waitForExistence(timeout: 5)); playlist.tap()
        let target = app.buttons["播放 插件示例歌曲"].firstMatch
        let revealed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in target.exists && target.isHittable }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [revealed], timeout: 8), .completed)
        capture("平台歌单-自动定位暂停曲目", app: app)
    }

    @MainActor func testFailedSongsAutomaticallyAdvanceToPlayableAudio() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-playback-failures", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("failure fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 晚风里的片刻")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        XCTAssertTrue(app.buttons["暂停"].firstMatch.waitForExistence(timeout: 5))
        let mini = app.buttons["打开播放页，雨落在窗沿"]
        if mini.exists { mini.tap() }
        XCTAssertTrue(app.staticTexts["雨落在窗沿"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["重试"].exists)
        capture("播放失败-连续跳过并自动续播", app: app)
    }

    @MainActor func testExistingPlatformLikeShowsFilledHeartOnEntry() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "--demo-liked", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("likes fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 晚风里的片刻")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let like = app.buttons["player-platform-like"]
        XCTAssertTrue(like.waitForExistence(timeout: 5))
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "取消点赞"), object: like)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 5), .completed)
        capture("已在平台喜欢-播放器直接亮红心", app: app)
        like.tap(); XCTAssertEqual(like.label, "点赞")
    }

    @MainActor func testPlatformPlaylistCanBeAddedFromThePlayerAndSongList() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--platform-playlist-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        selectTab("发现", app: app)
        let source = app.buttons["source-tab-fixture"]
        XCTAssertTrue(source.waitForExistence(timeout: 5)); source.tap()
        let song = app.buttons["播放 插件示例歌曲"].firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let more = app.buttons["player-track-more"]
        XCTAssertTrue(more.waitForExistence(timeout: 5)); more.tap()
        app.buttons["player-add-platform-playlist"].tap()
        let target = app.buttons["platform-playlist-pl"]
        XCTAssertTrue(target.waitForExistence(timeout: 5)); capture("播放器-选择平台歌单", app: app); target.tap()
        let pickerClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.navigationBars["添加到平台歌单"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [pickerClosed], timeout: 5), .completed)
        app.buttons["关闭"].firstMatch.tap()
        let actionsClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.descendants(matching: .any)["player-track-actions"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [actionsClosed], timeout: 5), .completed)
        app.buttons["收起"].press(forDuration: 0.15)
        capture("平台歌单-收起返回列表", app: app)
        let playerClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.buttons["player-track-more"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [playerClosed], timeout: 5), .completed)
        let actions = app.buttons["曲目操作 插件示例歌曲"].firstMatch
        if !actions.exists { app.buttons["查看更多示例曲目"].firstMatch.tap() }
        XCTAssertTrue(actions.waitForExistence(timeout: 5)); actions.tap()
        app.buttons["添加到平台歌单"].tap()
        XCTAssertTrue(target.waitForExistence(timeout: 5)); capture("歌曲列表-选择平台歌单", app: app); target.tap()
    }

    @MainActor func testFavoriteFolderTerminologyAndNativeRemoval() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--folder-playlist-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        selectTab("发现", app: app); app.buttons["source-tab-fixture"].tap()
        let song = app.buttons["播放 插件示例歌曲"].firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let more = app.buttons["player-track-more"]
        XCTAssertTrue(more.waitForExistence(timeout: 5)); more.tap()
        app.buttons["player-add-platform-playlist"].tap()
        XCTAssertTrue(app.navigationBars["添加到收藏夹"].waitForExistence(timeout: 5))
        let target = app.buttons["platform-playlist-pl"]
        XCTAssertTrue(target.waitForExistence(timeout: 5)); capture("收藏夹-播放器添加入口", app: app); target.tap()
        let pickerClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.navigationBars["添加到收藏夹"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [pickerClosed], timeout: 5), .completed)
        app.buttons["关闭"].firstMatch.tap()
        let actionsClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.descendants(matching: .any)["player-track-actions"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [actionsClosed], timeout: 5), .completed)
        app.buttons["收起"].press(forDuration: 0.15)
        let playerClosed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !app.buttons["player-track-more"].exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [playerClosed], timeout: 5), .completed)
        let folder = app.buttons["示例歌单"].firstMatch
        if !folder.exists { app.swipeUp() }
        XCTAssertTrue(folder.waitForExistence(timeout: 5)); folder.tap()
        let action = app.buttons["曲目操作 music · 插件结果"].firstMatch
        XCTAssertTrue(action.waitForExistence(timeout: 5))
        let resume = app.buttons["playlist-resume"]
        let firstTrack = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "catalog-track-")).firstMatch
        XCTAssertTrue(resume.exists); XCTAssertTrue(firstTrack.exists)
        let gap = firstTrack.frame.minY - resume.frame.maxY
        XCTAssertGreaterThanOrEqual(gap, 0, "续播入口不应覆盖第一首曲目")
        XCTAssertLessThanOrEqual(gap, 24, "无标题列表不应在续播入口后保留空白分区，实际间距：\(gap)")
        capture("收藏夹-顶部紧凑曲目列表", app: app)
        action.tap()
        let remove = app.buttons["从此收藏夹移除"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5)); capture("收藏夹-原生移除", app: app); remove.tap()
        XCTAssertTrue(app.descendants(matching: .any)["catalog-empty"].waitForExistence(timeout: 5))
    }

    @MainActor func testPluginCenterTabsAndInstallationReview() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-check-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        let community = app.buttons["社区"]
        XCTAssertTrue(community.waitForExistence(timeout: 5))
        community.tap()
        let search = app.textFields["plugin-community-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap(); search.typeText("mygo")
        app.buttons["plugin-community-submit"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "plugin-community-install-")).firstMatch.waitForExistence(timeout: 35))
        capture("插件中心-社区搜索", app: app)
        app.buttons["已安装"].tap()
        let github = app.buttons["plugin-install-github"]
        XCTAssertTrue(github.waitForExistence(timeout: 5))
        github.tap()
        let alert = app.alerts.firstMatch
        let field = alert.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("fixture/success")
        alert.buttons["检查"].tap()
        let install = app.buttons["安装"]
        XCTAssertTrue(install.waitForExistence(timeout: 15))
        capture("插件中心-安装仍需确认", app: app)
        install.tap()
        XCTAssertTrue(github.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["已安装"].exists)
        capture("插件中心-安装后定位", app: app)
    }

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
        app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["黑胶"].tap()
        let artwork = app.buttons["player-artwork-vinyl"]
        XCTAssertTrue(artwork.waitForExistence(timeout: 5))
        let inlineLyrics = app.descendants(matching: .any)["player-inline-lyrics"].firstMatch
        XCTAssertTrue(inlineLyrics.waitForExistence(timeout: 5))
        let inlineLine = app.buttons["选择歌词 把今天的喧闹放远"]
        XCTAssertTrue(inlineLine.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(inlineLyrics.frame.height, 85)
        XCTAssertGreaterThan(inlineLyrics.frame.minY, app.buttons["player-creator"].frame.midY)
        inlineLine.tap()
        XCTAssertTrue(app.buttons["从这一句播放 把今天的喧闹放远"].waitForExistence(timeout: 5))
        // 封面页选词也必须等待右侧确认，不能改变歌曲或打开完整歌词页。
        XCTAssertTrue(artwork.exists)
        capture("music-inline-lyrics", app: app)
        artwork.tap()
        let cover = app.buttons["player-lyrics-cover"]
        XCTAssertTrue(cover.waitForExistence(timeout: 5))
        let lyrics = app.buttons["选择歌词 把今天的喧闹放远"]
        XCTAssertTrue(lyrics.waitForExistence(timeout: 5))
        let header = app.descendants(matching: .any)["player-lyrics-header"].firstMatch
        XCTAssertTrue(header.staticTexts["晚风里的片刻"].exists)
        let headerY = cover.frame.minY
        lyrics.swipeUp()
        XCTAssertEqual(cover.frame.minY, headerY, accuracy: 2, "歌词滚动时歌曲信息固定在顶部")
        app.buttons["player-creator"].tap()
        XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5)); app.buttons["完成"].tap()
        XCTAssertTrue(cover.waitForExistence(timeout: 5))
        capture("音乐播放器-固定歌词头与作者入口", app: app)
        cover.tap(); XCTAssertTrue(artwork.waitForExistence(timeout: 5))
        XCTAssertFalse(cover.exists)
        capture("音乐播放器-返回用户黑胶样式", app: app)
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

    @MainActor func testRestoreOtherDeviceSettings() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-device-settings", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("我的", app: app)
        let service = app.buttons["open-shanhai-service"]
        if !service.isHittable { app.swipeUp() }
        service.tap()
        let otherDevice = app.buttons["restore-other-device"]
        XCTAssertTrue(otherDevice.waitForExistence(timeout: 5)); otherDevice.tap()
        let device = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "书房 Mac")).firstMatch
        XCTAssertTrue(device.waitForExistence(timeout: 8)); device.tap()
        let details = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "App 设置")).firstMatch
        XCTAssertTrue(details.waitForExistence(timeout: 5)); details.tap()
        XCTAssertTrue(app.staticTexts["无损"].exists || app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "无损")).firstMatch.exists)
        capture("设备备份明细", app: app)
        details.tap()
        let restore = app.buttons["恢复这些设置"]
        let form = app.collectionViews["node-service-form"]
        // iPad 表单以 sheet 呈现；按钮的 AX 可点击标志可能包含被 sheet 裁掉的区域。
        // 在表单内滚动并要求整行位于可见范围，避免点击背景或 sheet 边缘。
        for _ in 0..<4 {
            if restore.isHittable && form.frame.insetBy(dx: 0, dy: 12).contains(restore.frame) { break }
            form.swipeUp()
        }
        restore.tap()
        let result = app.staticTexts["已恢复 2 项 · 跳过 1 项"]
        if !result.exists { form.swipeUp() }
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        capture("设备设置恢复结果", app: app)
        app.buttons["完成"].tap()
        selectTab("设置", app: app)
        let quality = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "音质", "无损")).firstMatch
        if !quality.exists { app.collectionViews["app-settings-form"].swipeUp() }
        XCTAssertTrue(quality.waitForExistence(timeout: 5))
    }

    @MainActor func testDeviceNamingAndSelectedPluginRestore() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-device-settings", "--demo-plugin-restore", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("我的", app: app)
        let service = app.buttons["open-shanhai-service"]
        if !service.isHittable { app.swipeUp() }
        service.tap()
        let form = app.collectionViews["node-service-form"]
        func reveal(_ element: XCUIElement) {
            for _ in 0..<6 {
                if element.isHittable && form.frame.insetBy(dx: 0, dy: 12).contains(element.frame) { return }
                form.swipeUp()
            }
        }
        let name = app.textFields["node-device-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 8)); reveal(name)
        name.tap(); name.typeText(" 回归设备\n")
        XCTAssertTrue(app.staticTexts["设备名称已保存"].waitForExistence(timeout: 8))
        XCTAssertTrue((name.value as? String)?.contains("回归设备") == true)
        let others = app.buttons["restore-other-device"]; reveal(others); others.tap()
        let source = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "书房 Mac")).firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 5)); reveal(source); source.tap()
        let replace = app.buttons["restore-complete-device"]
        XCTAssertTrue(replace.waitForExistence(timeout: 8)); reveal(replace); replace.tap()
        let fullConfirm = app.alerts.buttons["确认全部覆盖"]
        XCTAssertTrue(fullConfirm.waitForExistence(timeout: 5))
        app.alerts.buttons["取消"].tap()
        XCTAssertTrue(fullConfirm.waitForNonExistence(timeout: 5))
        let choose = app.buttons["choose-plugin-restore"]
        XCTAssertTrue(choose.waitForExistence(timeout: 8)); reveal(choose); choose.tap()
        let selected = app.buttons["restore-plugin-preview-a"]
        XCTAssertTrue(selected.waitForExistence(timeout: 15)); reveal(selected); selected.tap()
        let cancel = app.buttons["取消"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5)); cancel.tap()
        XCTAssertFalse(app.staticTexts["已覆盖所选插件的信息"].exists)
        reveal(selected); selected.tap()
        let confirm = app.buttons["覆盖此插件的信息"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
        let result = app.staticTexts["已覆盖所选插件的信息"]
        reveal(result); XCTAssertTrue(result.waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["restore-plugin-preview-b"].exists)
        capture("设备命名与单插件恢复", app: app)
    }

    @MainActor func testTabletCenteredNavigationAndModeSwitch() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let search = app.buttons["tablet-tab-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 15))
        XCTAssertGreaterThanOrEqual(search.frame.height, 44)
        search.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.1)).tap()
        XCTAssertTrue(app.textFields["搜索视频、音乐、UP 主"].waitForExistence(timeout: 5))
        XCTAssertTrue(search.isSelected)
        let settings = app.buttons["tablet-tab-settings"]
        settings.tap()
        XCTAssertTrue(app.buttons["settings-app-updates"].waitForExistence(timeout: 5))
        XCTAssertTrue(settings.isSelected)
        let mode = app.buttons["tablet-playback-mode"]
        let previous = mode.value as? String
        mode.tap()
        let changed = expectation(for: NSPredicate { _, _ in mode.value as? String != previous }, evaluatedWith: mode)
        wait(for: [changed], timeout: 5)
        XCTAssertTrue(settings.isSelected)
        XCTAssertTrue(app.buttons["settings-app-updates"].exists)
        XCTAssertGreaterThan(settings.frame.minX, app.buttons["tablet-tab-account"].frame.minX)
        XCTAssertGreaterThan(mode.frame.minX, settings.frame.minX)
        XCTAssertFalse(app.buttons["显示侧边栏"].exists)
        capture("iPad-居中导航-设置与模式", app: app)
        mode.tap()
        let restored = expectation(for: NSPredicate { _, _ in mode.value as? String == previous }, evaluatedWith: mode)
        wait(for: [restored], timeout: 5)
        XCUIDevice.shared.orientation = .landscapeLeft
        let discover = app.buttons["tablet-tab-discover"]
        // 旋转通知早于 AX 布局更新；等导航确实居中到横屏后再点击，避免使用上一帧坐标。
        let rotated = expectation(for: NSPredicate { _, _ in
            let window = app.windows.firstMatch.frame
            let center = (discover.frame.minX + mode.frame.maxX) / 2
            return window.width > window.height && abs(center - window.midX) < 4 && discover.isHittable
        }, evaluatedWith: discover)
        wait(for: [rotated], timeout: 10)
        discover.tap()
        XCTAssertTrue(discover.isSelected)
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 5))
        capture("iPad-横屏发现-无侧栏", app: app)
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
        let later = app.sheets["保存密码？"].buttons["以后"]
        if later.waitForExistence(timeout: 2) { later.tap() }
        app.buttons["隐藏密码"].tap()
        if later.waitForExistence(timeout: 2) { later.tap() }
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

    @MainActor func testLandscapeQueuePaginatesAtTheBottomWithoutChangingCurrentTrack() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-pagination", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("分页\n")
        let firstTrack = app.buttons["选择 分页示例 1"]
        XCTAssertTrue(firstTrack.waitForExistence(timeout: 5)); firstTrack.tap()
        XCTAssertTrue(app.buttons["下一首"].waitForExistence(timeout: 5))
        if UIDevice.current.userInterfaceIdiom == .phone { app.buttons["横屏播放"].tap() }
        XCUIDevice.shared.orientation = .landscapeLeft
        let queue = app.buttons["wide-player-queue"]
        XCTAssertTrue(queue.waitForExistence(timeout: 8)); queue.tap()
        XCTAssertTrue(app.staticTexts["共 18 首"].waitForExistence(timeout: 5))
        let list = app.descendants(matching: .any)["wide-player-queue-list"].firstMatch
        let lastTrack = app.buttons["队列选择 分页示例 21"]
        for _ in 0..<8 {
            if lastTrack.exists && lastTrack.isHittable { break }
            list.swipeUp()
        }
        XCTAssertTrue(lastTrack.isHittable)
        XCTAssertTrue(app.staticTexts["共 21 首"].exists)
        XCTAssertTrue(app.staticTexts["分页示例 1"].exists)
        capture("横屏播放器-待播清单触底续页", app: app)
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
        XCTAssertTrue(app.staticTexts["晚风里的片刻"].firstMatch.exists)
        stage.swipeLeft()
        XCTAssertTrue(app.staticTexts["城市慢下来"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["收起"].exists)
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        stage.swipeRight()
        XCTAssertTrue(app.staticTexts["晚风里的片刻"].firstMatch.waitForExistence(timeout: 5))
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
        XCTAssertTrue(app.otherElements["player-lyrics-header"].staticTexts["城市慢下来"].waitForExistence(timeout: 5))
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

    @MainActor func testAudioContinuesAfterBackgroundingAndPlayerControlsRecover() {
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
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
        app.buttons["播放"].tap()
        XCTAssertTrue(app.buttons["暂停"].waitForExistence(timeout: 8))
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
        app.activate()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["暂停"].isHittable)
        capture("后台音频-返回后继续播放", app: app)
        app.buttons["暂停"].tap()
        XCTAssertTrue(app.buttons["播放"].waitForExistence(timeout: 5))
    }

    @MainActor func testRecentSearchRemoval() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
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
        app.launchEnvironment["MB_VIDEO_UI_FIXTURE"] = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("android/app/src/androidTest/assets/video-portrait.mp4").path
        XCUIDevice.shared.orientation = .portrait; app.launch()
        XCTAssertTrue(app.buttons["竖屏示例一"].waitForExistence(timeout: 5))
        let like = app.buttons["点赞"]
        XCTAssertTrue(like.waitForExistence(timeout: 5), "数据源迟到也应加载互动能力")
        expectation(for: NSPredicate(format: "value == %@", "128"), evaluatedWith: like)
        waitForExpectations(timeout: 5)
        like.tap()
        XCTAssertTrue(app.buttons["取消点赞"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["取消点赞"].value as? String, "129")
        XCTAssertTrue(app.buttons["评论"].exists)
        let picture = app.otherElements["native-video-surface"]
        XCTAssertTrue(picture.waitForExistence(timeout: 5))
        XCTAssertEqual(picture.frame.height, app.frame.height, accuracy: 2, "画面铺到上下边缘，控制浮在视频上")
        // 按屏幕比例拖动，超过大屏的 18% 阈值，并避开进度条与互动栏。
        let lower = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.65))
        let upper = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.25))
        lower.press(forDuration: 0.05, thenDragTo: upper)
        XCTAssertTrue(app.buttons["竖屏示例二"].waitForExistence(timeout: 5))
        upper.press(forDuration: 0.05, thenDragTo: lower)
        XCTAssertTrue(app.buttons["竖屏示例一"].waitForExistence(timeout: 5))
        capture("竖屏-主画面与右侧互动", app: app)
        app.buttons["评论"].tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 8))
        capture("竖屏-评论底部面板", app: app)
        app.buttons["关闭"].tap()
        app.buttons["portrait-more"].tap()
        app.buttons["收藏到资料库"].tap(); XCTAssertTrue(app.buttons["已收藏"].exists)
        capture("竖屏-无填充更多面板", app: app)
        app.buttons["画质"].tap()
        XCTAssertTrue(app.buttons["video-quality-720p"].waitForExistence(timeout: 5)); app.buttons["video-quality-720p"].tap()
        app.buttons["关闭"].tap()
        app.buttons["portrait-more"].tap()
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.staticTexts["字幕"].waitForExistence(timeout: 5))
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
        XCTAssertEqual(secondRowCard.frame.minY, firstCard.frame.minY, accuracy: 2, "目录封面先填满第一行")
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

    @MainActor func testDiscoveryShelfFillsRowsAndFollowingKeepsAvatarsAndLoadsAtBottom() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--shelf-layout-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch(); selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 8))
        selectTab("发现", app: app); app.buttons["source-tab-fixture"].tap()
        let first = app.buttons["播放 封面资源1"], second = app.buttons["播放 封面资源2"]
        XCTAssertTrue(second.waitForExistence(timeout: 8))
        XCTAssertEqual(first.frame.minY, second.frame.minY, accuracy: 2)
        XCTAssertLessThan(first.frame.maxX, second.frame.minX)
        capture("发现卡片-两项先填第一行", app: app)
        for count in [1, 2, 3, 8] {
            let tab = app.buttons["plugin-filter-tab-shelf:\(count)"]
            XCTAssertTrue(tab.waitForExistence(timeout: 5)); tab.tap()
            let cards = (1...count).map { app.buttons["播放 封面资源\($0)"] }
            XCTAssertTrue(cards[0].waitForExistence(timeout: 5))
            if count > 1 {
                XCTAssertTrue(cards[1].waitForExistence(timeout: 5))
                XCTAssertEqual(cards[0].frame.minY, cards[1].frame.minY, accuracy: 2)
            }
            if count > 2 {
                let columns = max(1, Int((app.frame.width - 40 + 16) / 172))
                if count > columns {
                    XCTAssertTrue(cards[columns].exists)
                    capture("发现卡片-第二行边界-\(count)项", app: app)
                    XCTAssertGreaterThan(cards[columns].frame.minY - cards[0].frame.minY, 246, "第二行须位于完整卡片高度之后")
                }
            }
            capture("发现卡片-\(count)项", app: app)
        }
        app.buttons["plugin-filter-tab-shelf:2"].tap()
        app.buttons["播放 封面资源1"].tap()
        XCTAssertTrue(app.buttons["player-creator"].waitForExistence(timeout: 8))
        app.buttons["player-creator"].tap()
        XCTAssertTrue(app.navigationBars["插件作者主页"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["播放 作者的另一首歌"].exists)
        capture("播放器-独立插件歌手主页", app: app)
        app.buttons["完成"].tap(); app.buttons["收起"].tap()
        let people = app.buttons["查看更多我的关注"]
        for _ in 0..<5 { if !people.isHittable { app.swipeUp() } }
        XCTAssertTrue(people.isHittable); people.tap()
        let author = app.buttons["catalog-link-profile:1"]
        XCTAssertTrue(author.waitForExistence(timeout: 8))
        XCTAssertFalse(app.staticTexts["查看更多"].exists)
        XCTAssertFalse(app.buttons["catalog-link-profile:19"].exists)
        capture("关注列表-头像名称与完整介绍", app: app)
        let last = app.buttons["catalog-link-profile:21"]
        let mini = app.otherElements["mini-player"].firstMatch
        func visibleBottom() -> CGFloat { (mini.exists ? mini.frame.minY : app.frame.maxY) - 8 }
        for _ in 0..<14 {
            if !last.exists || !last.isHittable || last.frame.midY > visibleBottom() { app.swipeUp() }
        }
        XCTAssertTrue(last.waitForExistence(timeout: 8) && last.isHittable)
        XCTAssertLessThan(last.frame.midY, visibleBottom())
        capture("关注列表-触底追加第二页", app: app)
        last.tap(); XCTAssertTrue(app.navigationBars["作者主页"].waitForExistence(timeout: 5))
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
        XCTAssertTrue(app.buttons["dismiss-settings"].waitForExistence(timeout: 5))
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
        XCTAssertTrue(app.buttons["dismiss-settings"].waitForExistence(timeout: 5))
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

    @MainActor func testLocalPlaylistResumesLastTrack() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("资料库", app: app)
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "晚间散步 · 示例")).firstMatch
        XCTAssertTrue(playlist.waitForExistence(timeout: 10))
        playlist.tap()
        let resume = app.buttons["playlist-resume"]
        let row = app.buttons["播放 晚风里的片刻"]
        XCTAssertTrue(resume.waitForExistence(timeout: 5))
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(resume.isEnabled)
        resume.tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
        capture("歌单上次播放-演示数据", app: app)
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
                XCTAssertTrue(app.staticTexts["优化推荐分类切换，保留完整首页内容。"].exists)
                XCTAssertFalse(app.staticTexts["media.example.test"].exists)
                app.descendants(matching: .any)["plugin-review-network-permissions"].firstMatch.tap()
                XCTAssertTrue(app.staticTexts["media.example.test"].waitForExistence(timeout: 5))
                capture("插件-新版说明与折叠权限", app: app)
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

    @MainActor func testPluginManualInstallRejectsNonRepositoryInput() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        app.buttons["plugin-install-github"].tap()
        let field = app.alerts.firstMatch.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("file:///tmp/plugin.zip")
        app.alerts.firstMatch.buttons["检查"].tap()
        XCTAssertTrue(app.staticTexts["插件检查失败"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["安装"].exists)
        app.buttons["dismiss-plugin-check-error"].tap()
        XCTAssertTrue(app.buttons["plugin-install-github"].waitForExistence(timeout: 5))
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
        let newName = "My Platform With a Long Title for Horizontal Scrolling"
        XCTAssertTrue(name.waitForExistence(timeout: 3)); name.tap(); name.typeText(newName)
        app.alerts.buttons["保存"].press(forDuration: 0.15)
        selectTab("发现", app: app)
        let target = app.buttons["source-tab-fixture"]
        XCTAssertTrue(target.waitForExistence(timeout: 5)); XCTAssertEqual(target.label, newName)
        let initialTabX = app.buttons["source-tab-demo"].frame.minX
        XCTAssertFalse(app.buttons["搜索音源"].exists)
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        if isPad {
            // 平板上两项都能直接点击，走顶部标签切换入口。
            XCTAssertTrue(target.isHittable)
            target.tap()
        } else {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.18))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.18))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].waitForExistence(timeout: 5))
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in target.isSelected }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 4), .completed)
        if !isPad {
            let firstTab = app.buttons["source-tab-demo"]
            let advanced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                firstTab.frame.minX < initialTabX - 8 && target.isHittable
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [advanced], timeout: 4), .completed)
        }
        capture("发现-切换后选中标签与下划线", app: app)
        chooseMode("视频模式", app: app)
        capture("发现-顶部平台与点击切换模式", app: app)
        chooseMode("听歌模式", app: app)
        let more = app.buttons["查看更多示例曲目"]
        XCTAssertEqual(app.buttons.matching(identifier: "查看更多示例曲目").count, 1)
        more.press(forDuration: 0.15)
        XCTAssertTrue(app.navigationBars["相同导航完整内容"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["playlist-resume"].waitForExistence(timeout: 5))
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
        XCUIDevice.shared.orientation = .portrait
        app.launch()
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
        let shelf = app.scrollViews["plugin-shelf-0"]
        XCTAssertTrue(shelf.waitForExistence(timeout: 5))
        func visibleOnShelf(_ element: XCUIElement) -> Bool {
            guard element.exists, shelf.exists, !element.frame.isEmpty else { return false }
            return shelf.frame.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)) && element.isHittable
        }
        for (title, detail) in [("示例专辑", "专辑"), ("示例作者", "作者"), ("示例播客", "播客"), ("示例电台", "电台")] {
            XCTAssertTrue(shelf.waitForExistence(timeout: 5))
            let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            for _ in 0..<3 where !visibleOnShelf(link) { shelf.swipeLeft() }
            XCTAssertTrue(visibleOnShelf(link), "横滑后仍无法点击\(title)")
            link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-" + detail + "详情-演示数据", app: app)
            if detail == "专辑" { app.buttons["收藏专辑"].tap(); XCTAssertTrue(app.buttons["取消收藏专辑"].waitForExistence(timeout: 5)) }
            if detail == "作者" { app.buttons["关注作者"].tap(); XCTAssertTrue(app.buttons["取消关注作者"].waitForExistence(timeout: 5)) }
            if detail == "播客" { app.buttons["订阅播客"].tap(); XCTAssertTrue(app.buttons["取消订阅"].waitForExistence(timeout: 5)) }
            app.navigationBars[title].buttons["BackButton"].tap()
        }
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例歌单")).firstMatch
        XCTAssertTrue(shelf.waitForExistence(timeout: 5))
        for _ in 0..<3 where !visibleOnShelf(playlist) { shelf.swipeRight() }
        XCTAssertTrue(visibleOnShelf(playlist))
        playlist.tap()
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
        app.navigationBars["示例歌单"].buttons["BackButton"].tap()
        selectTab("我的", app: app)
        for title in ["平台歌单", "喜欢的歌曲", "最近播放", "收藏专辑", "订阅播客", "电台"] {
            let link = app.buttons[title]
            if !link.isHittable { app.swipeUp() }; link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-我的-" + title + "-演示数据", app: app)
            app.navigationBars[title].buttons["BackButton"].tap()
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
            if UIDevice.current.userInterfaceIdiom == .pad {
                let settings = app.buttons["tablet-tab-settings"]
                XCTAssertTrue(settings.waitForExistence(timeout: 10)); settings.tap()
                return
            }
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
            let player = app.descendants(matching: .any)["music-player-root"].firstMatch
            if !player.waitForExistence(timeout: 3) { app.buttons["打开播放页，晚风里的片刻"].tap() }
            let queue = orientation == .portrait ? app.buttons["队列"] : app.buttons["wide-player-queue"]
            XCTAssertTrue(queue.waitForExistence(timeout: 5))
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
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为随机播放"].waitForExistence(timeout: 3))
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为顺序播放"].waitForExistence(timeout: 3))
        app.buttons["顺序播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为列表循环"].waitForExistence(timeout: 3))
        app.buttons["列表循环"].tap()
        XCTAssertTrue(app.staticTexts["已切换为单曲循环"].waitForExistence(timeout: 3))
        capture("播放器-图标操作与模式提示", app: app)
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
    @MainActor func testLandscapeVideoSideDragsShowSystemAdjustmentsInlineAndFullscreen() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launchEnvironment["MB_VIDEO_UI_FIXTURE"] = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Tests/MBFoundationTests/Fixtures/video-ui.mp4").path
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom != .pad {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 8)); app.buttons["查看更多为你推荐"].tap()
        }
        chooseMode("视频模式", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let surface = app.otherElements["native-video-surface"]
        XCTAssertTrue(surface.waitForExistence(timeout: 12))
        for full in [false, true] {
            let start = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.64))
            start.press(forDuration: 0.05, thenDragTo: surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.3)))
            let feedback = app.otherElements["video-adjustment-feedback"]
            XCTAssertTrue(feedback.exists)
            XCTAssertEqual(feedback.label, "系统亮度")
            XCTAssertTrue((feedback.value as? String)?.hasSuffix("%") == true)
            capture(full ? "全屏视频-系统亮度滑动" : "内嵌视频-系统亮度滑动", app: app)
            // 向下调整仍在同一画面，不能触发暂停或切换资源。
            surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.32)).press(forDuration: 0.05,
                thenDragTo: surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.62)))
            XCTAssertTrue(feedback.exists); XCTAssertEqual(feedback.label, "系统亮度")
            surface.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.6)).press(forDuration: 0.05,
                thenDragTo: surface.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.34)))
            XCTAssertTrue(feedback.exists); XCTAssertEqual(feedback.label, "系统音量")
            capture(full ? "全屏视频-系统音量滑动" : "内嵌视频-系统音量滑动", app: app)
            if !full {
                if !app.buttons["全屏播放"].exists { surface.tap() }
                XCTAssertTrue(app.buttons["全屏播放"].waitForExistence(timeout: 5)); app.buttons["全屏播放"].tap()
                XCTAssertTrue(app.otherElements["fullscreen-video"].waitForExistence(timeout: 8))
            }
        }
        if !app.buttons["退出全屏"].exists { surface.tap() }
        app.buttons["退出全屏"].tap()
        XCTAssertTrue(app.buttons["简介"].waitForExistence(timeout: 8))
    }

    @MainActor func testDiscoverySectionsCancelSaveReorderAndReopen() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--discovery-sections-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 8))
        selectTab("发现", app: app); app.buttons["source-tab-fixture"].tap()
        XCTAssertTrue(app.buttons["播放 one资源0"].waitForExistence(timeout: 8))
        func openDisplaySettings() {
            selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
            let display = app.buttons["plugin-display-fixture"]
            XCTAssertTrue(display.waitForExistence(timeout: 8)); capture("插件列表-右侧设置显示", app: app); display.tap()
        }
        func backToDiscovery() {
            if app.buttons["dismiss-plugins"].exists { app.buttons["dismiss-plugins"].tap() }
            selectTab("发现", app: app)
        }
        openDisplaySettings()
        let first = app.switches["discovery-section-one"]
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        first.tap()
        XCTAssertEqual(first.value as? String, "0")
        app.buttons["取消"].tap()
        backToDiscovery()
        XCTAssertTrue(app.buttons["播放 one资源0"].exists)
        openDisplaySettings()
        first.tap()
        XCTAssertEqual(first.value as? String, "0")
        capture("栏目设置-开关与拖动手柄", app: app)
        app.buttons["保存"].tap()
        backToDiscovery()
        XCTAssertTrue(app.buttons["播放 two资源0"].waitForExistence(timeout: 8))
        XCTAssertTrue(NSPredicate(format: "exists == false").evaluate(with: app.buttons["播放 one资源0"]) ||
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["播放 one资源0"])], timeout: 8) == .completed)
        openDisplaySettings()
        XCTAssertEqual(first.value as? String, "0")
        let thirdCell = app.cells.containing(.switch, identifier: "discovery-section-three").firstMatch
        let firstCell = app.cells.containing(.switch, identifier: "discovery-section-one").firstMatch
        XCTAssertTrue(thirdCell.exists); XCTAssertTrue(firstCell.exists)
        thirdCell.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5))
            .press(forDuration: 0.5, thenDragTo: firstCell.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.1)))
        XCTAssertLessThan(app.switches["discovery-section-three"].frame.minY, first.frame.minY)
        app.buttons["保存"].tap()
        backToDiscovery()
        XCTAssertTrue(app.buttons["播放 three资源0"].waitForExistence(timeout: 8))
        capture("发现栏目-保存后的两行卡片", app: app)
        openDisplaySettings()
        XCTAssertLessThan(app.switches["discovery-section-three"].frame.minY, first.frame.minY)
        XCTAssertEqual(first.value as? String, "0")
        capture("栏目设置-重开保留排序", app: app)
    }

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
        // 平台分页容器的滚动区域延伸至顶栏背后，触点必须落在可见内容内。
        let top = max(scroll.frame.minY + 60, app.navigationBars.firstMatch.frame.maxY + 24)
        let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0))
            .withOffset(CGVector(dx: 0, dy: top - scroll.frame.minY))
        start.press(forDuration: 0.05,
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
            XCTAssertGreaterThan(top, app.frame.minY + 1, "普通视频保留系统状态栏")
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

    @MainActor func testInlineVideoKeepsStatusBarAndActionsOnOneRow() throws {
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
        XCTAssertTrue(statusBar.waitForExistence(timeout: 5))
        let previous = app.buttons["上一首"], next = app.buttons["下一首"], shuffle = app.buttons["随机播放"]
        XCTAssertTrue(previous.waitForExistence(timeout: 5)); XCTAssertTrue(next.exists); XCTAssertTrue(shuffle.exists)
        XCTAssertEqual(previous.frame.midY, next.frame.midY, accuracy: 2)
        XCTAssertEqual(previous.frame.midY, shuffle.frame.midY, accuracy: 2)
        let like = app.buttons["video-action-like"]
        XCTAssertTrue(like.waitForExistence(timeout: 5)); XCTAssertEqual(previous.frame.midY, like.frame.midY, accuracy: 2)
        shuffle.tap(); XCTAssertEqual(shuffle.value as? String, "开启")
        capture("视频-状态栏与同排操作", app: app)
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

    @MainActor func testPlayerIconActionsShowNoticesAndOpenLyricsAndQueue() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("icons fixture\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        XCTAssertTrue(app.buttons["字幕"].waitForExistence(timeout: 5))
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为随机播放"].waitForExistence(timeout: 3))
        app.buttons["随机播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为顺序播放"].waitForExistence(timeout: 3))
        app.buttons["顺序播放"].tap()
        XCTAssertTrue(app.staticTexts["已切换为列表循环"].waitForExistence(timeout: 3))
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.staticTexts["已切换到歌词"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["player-lyrics-cover"].waitForExistence(timeout: 5))
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.staticTexts["已切换到封面"].waitForExistence(timeout: 3))
        capture("播放-仅图标与轻提示", app: app)
        app.buttons["队列"].tap()
        XCTAssertTrue(app.navigationBars.matching(NSPredicate(format: "identifier BEGINSWITH %@", "播放队列")).firstMatch.waitForExistence(timeout: 5))
        app.buttons["完成"].tap()
        XCTAssertTrue(app.buttons["字幕"].waitForExistence(timeout: 5))
    }
}

extension MBiOSUITests {
    @MainActor func testPluginCatalogPaginationKeepsOneListAndContinuousTrackNumbers() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--catalog-pagination-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 8))
        selectTab("发现", app: app); app.buttons["source-tab-fixture"].tap()
        let more = app.buttons["查看更多示例曲目"]
        XCTAssertTrue(more.waitForExistence(timeout: 8)); more.tap()
        XCTAssertTrue(app.staticTexts["分页作品"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["catalog-track-0-1"].exists)
        let numberTwentyOne = app.buttons["catalog-track-0-21"]
        for _ in 0..<8 {
            if numberTwentyOne.exists && numberTwentyOne.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(numberTwentyOne.isHittable)
        XCTAssertEqual(numberTwentyOne.label, "播放 《无名的人》致所有顶天立地却平凡普通")
        XCTAssertLessThanOrEqual(app.staticTexts.matching(identifier: "分页作品").count, 1)
        capture("插件分页-第21条连续编号", app: app)
    }

    @MainActor func testPluginFilterTabsChangeContentInPlaceAndRefreshSelectedFilter() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--filter-fixture", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); selectTab("设置", app: app); app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 8)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 8))
        selectTab("发现", app: app)
        app.buttons["source-tab-fixture"].tap()
        let music = app.buttons["plugin-filter-tab-music"]
        XCTAssertTrue(music.waitForExistence(timeout: 8)); music.tap()
        XCTAssertTrue(app.buttons["播放 music内容第1次"].waitForExistence(timeout: 8))
        XCTAssertTrue(music.isSelected)
        capture("推荐分类-横滚Tab原地切换", app: app)
        let scroll = app.scrollViews["plugin-discovery-scroll"]
        scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.08)).press(forDuration: 0.05,
            thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.85)))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 music内容第")).firstMatch.waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["播放 music内容第1次"].waitForNonExistence(timeout: 8))
        XCTAssertTrue(music.isSelected)
        app.buttons["plugin-filter-tab-games"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 games内容第")).firstMatch.waitForExistence(timeout: 8))
        app.buttons["plugin-filter-tab-all"].tap()
        XCTAssertTrue(app.buttons["播放 全部内容"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["plugin-filter-tab-all"].isSelected)
        scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.08)).press(forDuration: 0.05,
            thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.85)))
        XCTAssertTrue(app.buttons["播放 全部内容"].waitForExistence(timeout: 8))
        capture("推荐分类-返回全部并刷新", app: app)
        selectTab("我的", app: app)
        for (label, content) in [("订阅", "subscriptions内容第"), ("我的频道", "channel内容第"), ("点赞", "插件示例歌曲"), ("音乐专区", "music内容第")] {
            let link = app.buttons[label]
            if !link.isHittable { app.swipeUp() }
            XCTAssertTrue(link.waitForExistence(timeout: 8)); link.tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 " + content)).firstMatch.waitForExistence(timeout: 8))
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        XCTAssertFalse(app.buttons["喜欢的歌曲"].exists)
        capture("插件我的-个人入口", app: app)
    }
}


extension MBiOSUITests {
    @MainActor func testExplicitAccountResetSitsBesideLogoutAndReturnsToQRCode() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait; app.launch()
        selectTab("我的", app: app)
        let normal = app.buttons["退出登录"], reset = app.buttons["退出并清理缓存"]
        for _ in 0..<4 { if reset.exists && reset.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(reset.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(reset.frame.midX, normal.frame.midX)
        XCTAssertEqual(reset.frame.midY, normal.frame.midY, accuracy: 3)
        capture("账号-退出与清理缓存并排", app: app)
        reset.tap()
        let login = app.buttons["登录本地示例"]
        XCTAssertTrue(login.waitForExistence(timeout: 5)); login.tap()
        XCTAssertTrue(app.images["登录二维码"].waitForExistence(timeout: 5))
        capture("账号-清理后重新扫码", app: app)
        app.buttons["取消"].tap()
    }
}
