# 页面核对与验证

Build 26：设置检查版本、校验下载与签名安装交接，iPhone / iPad 各连续五轮通过，见 [验收记录与截图](AppUpdates/README.md)。

Build 25：远程插件直接比较更新、触底分页和播放器动作对齐，见 [验收记录](RemoteUpdates/README.md)。

## 紧凑页面与系统授权（2026-11-19）

四页 hero 标题移除，音源独占一行；YouTube iOS 系统 OAuth 和 CarPlay 主标题歌词已实现。Swift 全量 162 项、最终相关回归 29 项、插件 Node 14 项及 iPhone 4 项 / iPad 2 项 UI 方法通过。26 张截图、源码摘要、流播结论和真机未验收范围见 [本轮验证](CompactOAuth/README.md)。

## Build 15（2026-11-19）

- 浏览页切换视频模式只更新偏好和卡片布局，不会打开当前暂停或播放中的资源。主动点选后，在同一播放会话打开视频详情；播放器固定，简介、评论、字幕和队列独立切换。插件入口移到设置的“显示”下方。
- Swift 行为回归通过；iPhone 覆盖切模式、视频评论/队列/全屏、样式和设置首屏插件入口，iPad 覆盖视频详情横竖布局。模拟器游客实测哔哩哔哩真实音频、两次视频渲染和切回暂停状态；未进行真实账号发评、点赞或扫码。
- iPhone/iPad Release 构建及签名校验通过。无线安装后从真机读回 `0.1.0 (15)`；不将安装成功算作真机播放验证。
- 本地截图及索引：`ios/.build/Build15/screenshots/README.md`。结果包：`PhoneFinal.xcresult`、`TabletFinal.xcresult`、`TabletBilibili.xcresult`（同在 `ios/.build/Build15/`）。

内置发现沿用现有四区接口，简介使用音源返回的标题和作者，详情下部使用当前队列，不假冒平台相关推荐。评论读取、发评和点赞按音源现有能力显示。宿主复用插件 `trackLayout: "videos"` 卡片、导航与 `video` 播放合同；未新增插件视频专用搜索类型、视频简介正文、弹幕、投币或关注等接口。

## 历史验证

产品版本 0.1.0，build 7 的桌面功能对照 main `27d4337`。当时的页面重构与交互验证见 [build 7 说明](Build7/README.md)、[图库](Build7/index.html) 和 [编号索引](Build7/SCREENSHOTS.md)。[build 6](Build6/README.md) 保留历史验证。底栏与歌词设置的 [build 5 图库](Build5/index.html)、[原有完整图库](index.html) 保留历史验证。

截图来自 XCTest 在 iPhone 18 Pro / iPad Pro 13-inch (M5) 模拟器上的实际操作。账号、歌曲、评论、插件均为隔离演示数据，系统分享页使用演示静音 WAV。它们用于检查页面、导航、弹窗与状态，不代表真实账号互动、真机媒体播放或第三方平台验收。每张图的测试名称、设备、采集时间与 SHA-256 记录在 [manifest.json](manifest.json)。

## Build 7（2026-11-18 至 19 日）

参考 Apple Music、网易云音乐官方手机截图重构发现、搜索、资料库、我的和播放器；修复底栏及列表尾部遮挡，样式选择即时展示，点击封面切实时视频。修复系统后台取封面崩溃及暂停视频的缓冲状态刷新。

127 项 Swift 回归、SwiftUI 宿主编译与通用 Release 构建通过；iPhone 13 项、iPad 4 项 UI 方法分批通过，另覆盖暗色辅助大字体。当前 148 张演示图及逐方法结果见 [Build7](Build7/README.md)。游客联网与真实视频证据、源码摘要和未验收边界均在该目录说明。

## Build 6（2026-11-18）

123 项 Swift 行为/合同回归通过（Foundation 96、B 站 27），新增 HTTP/HTTPS/协议相对封面的三输入回归；公共 SwiftUI 宿主编译和通用 iPhone/iPad arm64 Release 构建通过。真实游客首页四区共 24 张封面下载、原生解码成功，手机首页实际显示；搜索“夜曲”得到 17 条并解码真实音频 4096 PCM 帧。

手机 3 项、平板 3 项 UI 回归通过；固定作者页面音源后，手机交互额外重跑 1 项通过。覆盖四个 Tab 的迷你播放器、音乐队列切歌不受未来视频偏好影响、固定控制/歌词/评论入口、作者其他投稿、封面/黑胶/磁带/三种歌词样式和 iPad 横竖屏。46 张演示截图记录在 [build 6 manifest](Build6/manifest.json)，真实游客首页封面另附并明确区分。

本地结果包：`.build/Build6Phone.xcresult`、`.build/Build6PhoneFinal.xcresult`、`.build/Build6Tablet.xcresult`。Release build 6 的 IPA 结构、ZIP、可执行权限和设备族检查通过，114 个生产源码/工程/脚本摘要与打包工作区一致。真实账号发布操作、真机后台/锁屏/CarPlay 和功耗仍待实测。

## Build 5（2026-11-18）

Swift 行为/合同回归 122 项通过（Foundation 96、B 站 26），新增系统正在播放歌词、暂停/拖动/换曲/迟到响应与设置迁移回归。iPhone 与 iPad 各两项相关 UI 测试通过：四个 Tab 的迷你播放器不覆盖导航、播放页可打开和收起、歌词开关可切换。手机普通页面按现有策略保持竖屏，平板验证横竖屏。新增 20 张演示截图；歌词设置截图不代表真实车机显示效果。

结果包为本地 `.build/Build5PhoneFinal.xcresult`、`.build/Build5TabletScreen.xcresult`。Release IPA 结构、ZIP、可执行权限检查通过，114 个生产源码/工程/脚本文件摘要与打包时的工作区一致。真实 CarPlay 和手机锁屏刷新尚待实测。

## Build 4 历史结果（2026-11-18）

Swift 行为/合同回归 115 项通过（Foundation 89、B 站 26）；SwiftUI 宿主编译、同步后工程的通用 iPhone/iPad `build-for-testing` 与 arm64 Release 打包通过。手机完整 UI 9 项、平板横竖屏 1 项通过，导出 96 张截图。测试包：`phone-final.xcresult`、`tablet-final.xcresult`，保留在本地 `.build/results/`。

Release IPA 结构/ZIP/可执行权限校验通过；生产源码 114 个文件的摘要与同步目录逐项一致；共 142 个源码/测试/工程/说明文件逐字节一致。仓库不包含个人 Team、描述文件、证书或 IPA。QuickJS 上游 C 文件保留原样，编译有整数转换警告；自有代码空白检查通过，上游文件不做无关格式改写。

## 覆盖范围

- 发现：首页上下部、四个更多页、搜索结果/加载/空白/失败。
- 我的：未登录、二维码、登录后、收藏夹及内容、点赞、历史、关注、投稿；评论列表/输入/发送结果。
- 播放：封面/黑胶/磁带，Flow/Steps/Tilt，歌词默认展开、播放效果开关、队列及编辑，视频全屏和不支持视频状态。
- 本地资料库：分类、专辑详情、歌单创建/重命名/删除/增删曲目，导入文件/文件夹系统选择器、导出音频分享面板。
- 插件：安装权限、列表、设置、启停、首页/分页，专辑/作者/歌单/播客/电台，个人内容、歌单搜索选歌及管理。
- 设置：外观/音质/响度/缓存/播放器和歌词样式，代理及校验错误，中英切换。
- iPad：首页、推荐页与播放器横竖屏。

## 复现

在 `ios/` 中执行；设备标识用本机 `xcrun simctl list devices available` 的输出替换：

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
swift test
swift build --target MBInterface
xcodebuild test -project MBiOS.xcodeproj -scheme MBiOS \
  -destination 'platform=iOS Simulator,id=IPHONE_SIMULATOR_UDID' \
  -parallel-testing-enabled NO \
  -skip-testing:MBiOSUITests/MBiOSUITests/testTabletRotationKeepsBrowserAndPlayer \
  -resultBundlePath .build/Phone.xcresult
xcodebuild test -project MBiOS.xcodeproj -scheme MBiOS \
  -destination 'platform=iOS Simulator,id=IPAD_SIMULATOR_UDID' \
  -only-testing:MBiOSUITests/MBiOSUITests/testTabletRotationKeepsBrowserAndPlayer \
  -resultBundlePath .build/Tablet.xcresult
xcrun xcresulttool export attachments --path .build/Phone.xcresult --output-path .build/phone-shots
xcrun xcresulttool export attachments --path .build/Tablet.xcresult --output-path .build/tablet-shots
python3 scripts/export-review.py .build/phone-shots .build/tablet-shots --output Review
bash scripts/package-ipa.sh
```

测试结果包与构建缓存不提交。真实联网验证、宿主差异与待做的真机验收见 [PARITY.md](../PARITY.md)。个人签名与安装步骤见 [INSTALL.md](../INSTALL.md)。
