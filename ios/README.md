# 山海 iPhone / iPad

SwiftUI 原生客户端，最低 iOS / iPadOS 17，Swift 6。此目录属于 [xTreeRoot/ShanhaiMusic](https://github.com/xTreeRoot/ShanhaiMusic) 的 `main` 分支；桌面功能对照基准为 main `27d4337`，当前产品版本 `0.1.0`、build `14`。源码与桌面共仓，工程入口为 `ios/MBiOS.xcodeproj`。

## 在另一台 Mac 编译

安装完整 Xcode，首次打开并完成组件安装。终端执行：

```bash
git clone --branch main --single-branch https://github.com/xTreeRoot/ShanhaiMusic.git
cd MB/ios
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
swift package resolve
open MBiOS.xcodeproj
```

Xcode 选择 `MBiOS` scheme；模拟器可直接运行。装到自己的 iPhone 时，选择 MBiOS target → Signing & Capabilities → Automatically manage signing，在 Team 中选自己的账号团队，顶部选真实手机再运行。仓库的 Team 留空，不携带个人证书或描述文件。详细步骤见 [INSTALL.md](INSTALL.md)。

## 已有功能

build 14 保留最新页面和听歌/视频切换，修复网易云插件纯文字登录提示被误判为非法、系统后台读取封面时的 MainActor 断言崩溃，以及 iOS 26.1+ 底部播放器与标签栏重叠。扫码确认时切换 App 产生的网络断连由 [xTreeRoot 网易云兼容插件 0.1.15](https://github.com/xTreeRoot/mygo-music-netease) 在同一二维码内恢复轮询和账号读取。新增搜索按钮；进入搜索不自动聚焦，点击空白、拖动内容或点完成可收起键盘。插件详情显示 GitHub 来源和安装位置。[喜马拉雅兼容插件 0.1.5](https://github.com/xTreeRoot/mygo-ximalaya) 在插件内将不可用无损降到已有音质。验证记录见 [登录与播放稳定性](Review/LoginStability/README.md)。

- B 站四区首页、查看更多、刷新、游客搜索、联想、分页与失败重试；扫码登录、二维码分享、过期刷新、取消、已存 Cookie 验证复用、退出后停用。
- 个人中心、收藏夹及内容、点赞视频、观看历史游标、关注列表、UP 投稿；评论分页、发送与视频点赞。所有发布操作由用户点击发起。
- AVFoundation 音频、HTTP Range 分段开始播放、不支持 Range 时完整下载回退、后台全量缓存；视频选择接近 720p 的 AVC 轨道，画面静音跟随同一音频时钟，支持横屏全屏。
- 后台音频、CarPlay 系统“正在播放”/锁屏信息与封面、耳机/控制中心操作、系统打断与拔耳机暂停；队列、随机、单曲/列表循环、增删排序、重启恢复为暂停。插件电台自动补充曲目，电台身份随队列保存。
- 歌词逐句滚动、点击跳转、翻译、逐字时间；Flow / Steps / Tilt，封面 / 黑胶 / 磁带；标准/动态/关闭响度处理。歌词火花、进度烟雾、低音封面雾气、间奏热评和封面展开均有开关。
- 本地歌曲/专辑/作者/喜欢/最近播放、搜索、歌单创建/重命名/删除/增删排序；文件与文件夹导入、AVFoundation 标签/封面读取、内容摘要去重、LRC 附加、音频导出分享。下载/导入独立保留，播放缓存按 LRU 和容量清理。
- 音质、应用音量、中英文、外观、缓存容量、搜索记录、系统代理域名白名单；首页/搜索/播放按能力使用音源，手动切换音源立即清空上一音源的播放会话与队列，播放和暂停曲目均移除，旧加载不能恢复曲目。
- 外部 JavaScript 插件：ZIP/目录/GitHub/安装链接、权限预览、安装/更新/卸载、设置、启停、GitHub release 每日检查。QuickJS ES 模块解释器支持 timer/fetch/Abort、Headers/URL、隔离存储与 MD5/AES；不启用 JIT，不运行外部进程。
- 插件首页/自定义页/导航、歌曲与专辑/作者/歌单/播客/电台、搜索分类、个人内容、收藏/关注/订阅、平台歌单增删与搜索选歌、可选评论互动、播放回报；网页登录在独立临时 WKWebView 中完成，`host.openURL` 使用 App 内网页。

build 7 参考 Apple Music、网易云音乐官方手机版截图重构页面：发现横向精选与分区列表，搜索独立入口，资料库集中最近添加和歌单，我的集中账号、个人内容、设置与插件。播放器以封面/歌词为主舞台，保留固定播放控制、评论、输出与队列；迷你播放器在各页安全区域内展示，平板限制宽度居中。危险删除操作增加确认，异步保存显示状态；保留原有插件、导入导出、歌词与实验效果能力。

听歌/视频可直接反复切换当前曲目的画面，无需重选歌曲。视频静音跟随同一音频时钟，切换保留曲目、进度和暂停状态；视频加载显示进度，切回听歌立即取消加载并隔离迟到响应。重复选择不会打断正在加载的视频；模式保存按顺序完成，最新选择最终落盘。没有视频能力的音源显示明确状态，仍可切回听歌；本地纯音频保持听歌界面。重启恢复和返回浏览不会自行打开视频。手机点击封面、黑胶或磁带可直接切到当前曲目的实时视频；选择封面样式立即显示对应封面，选择歌词舞台立即展开对应歌词，包括重复选择当前样式。

[YouTube Music 兼容插件 0.1.2](https://github.com/xTreeRoot/mygo-music-youtube-music) 使用原生 AAC 直链，已验证真实首页、搜索和完整音频可播放；设备网络仍需能访问 YouTube，Google 登录未验收。[汽水音乐兼容插件 0.2.1](https://github.com/xTreeRoot/mygo-music-soda) 提供公开推荐歌单、分类和可用完整 AAC，不声明搜索、私有歌单或会员能力。两个插件均不启动桌面辅助进程。

## CarPlay 系统正在播放与逐句歌词

继续使用 AltStore 个人签名。先在手机打开山海开始播放，连接 CarPlay 后打开车机自带的“正在播放”，可使用系统播放/暂停、上下曲与进度控制；实际显示和可用按钮由车机决定。无需为这条路径增加 CarPlay 专属 entitlement，App 没有独立的车机图标或歌单页面。

默认开启「设置 → CarPlay 与锁屏 → 在歌手栏显示当前歌词」：歌名保留，歌手字段按音频播放时间替换为当前句；前奏、间奏、无歌词或空白歌词行恢复原歌手。暂停时保留对应句，拖动、切曲、歌词迟到及开关变更立即刷新；普通进度按秒更新，句子变化不受整秒节流限制。关闭开关立即恢复正常歌手信息，选择会保存。

手机锁屏和控制中心也会使用同一份文字。系统决定刷新速度、截断与文字滚动，此功能不提供自定义滚动歌词界面。真实 CarPlay 尚未实测，不能将元数据回归或设置截图当成车机显示验收。

## 模块与数据

| 目录 | 职责 |
| --- | --- |
| `MBiOS/` | App 入口、图标、系统配置、中英文资源 |
| `Sources/MBFoundation/` | 来源协议、业务状态、网络、存储、原生播放、插件宿主 |
| `Sources/MBBilibili/` | B 站合同、WBI、账号、发现、互动及媒体解析 |
| `Sources/MBInterface/` | 手机/平板 SwiftUI 页面 |
| `Sources/MBSignal/` | 音频响度及低音采样 |
| `Sources/MBJavaScript/` | QuickJS C 桥与上游解释器 |
| `Tests/`、`MBiOSUITests/` | 行为/合同回归、原生媒体验证与界面操作截图 |
| `Review/` | 页面截图图库、覆盖索引与验证说明 |

数据位于 App 沙盒 `Application Support/MBMobile`。Cookie 与设置留在沙盒文件，退出仅停用；不读取桌面账号，不保存临时媒体 URL，不把证书、账号或沙盒资料提交 Git。文件写入采用原子提交，iOS 文件保护允许首次解锁后后台播放。

## 验证与打包

在本目录运行：

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
swift test
swift build --target MBInterface
xcodebuild -project MBiOS.xcodeproj -scheme MBiOS -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/SimulatorData CODE_SIGNING_ALLOWED=NO build-for-testing
bash scripts/package-ipa.sh
```

`package-ipa.sh` 输出 `.build/artifacts/MB-iOS-0.1.0-14-unsigned.ipa` 及源码 SHA-256 清单。未签名 IPA 需要自己的签名，不能点击直接安装；Xcode 直接运行是首次安装最省事的方式。

UI 回归使用后台启动的 Simulator 设备和 XCTest，不需要打开 Simulator.app 或控制 Xcode 窗口。可先用 `xcrun simctl list devices available` 找设备，再按 [Review/README.md](Review/README.md) 的命令运行和导出截图。Release 忽略 `--demo`；Debug 演示资料、插件、静音音频与实际沙盒数据隔离。

2026-11-18 build 7：127 项 Swift Testing 回归通过（Foundation 100、B 站 27，参数化测试另含多个输入）。本轮游客首页四区各 6 条、更多页分别 30/20/100/40 条，搜索“夜曲”17 条，真实音频解码 4096 PCM 帧，视频 HTTP 206 分段加载与原生画面输出通过。封面探针因平台分区偶发错误，本轮获得的三个分区 18 张图片全部下载解码成功；页面保留失败分区并支持下拉重试。最终手机/平板界面与 Release 结果见 [PARITY.md](PARITY.md) 和 [截图说明](Review/README.md)。

## 宿主差异与未验收项

桌面托盘、刘海、窗口和本机进程能力对应 iOS 导航、锁屏及控制中心；iOS 插件不提供外部进程、远程播放器和桌面端口监听回调 `waitForRedirect`。依赖完整浏览器 Web API 的插件可能需要适配；已验证原版 B 站 JS 可在解释器执行签名搜索合同，但不能据此宣称所有桌面插件通用。网页登录使用 WKWebView 自己的网络栈，域名代理白名单只控制 URLSession 的 API/图片/媒体请求。

build 3 曾完成免费 Personal Team 真机签名与安装。网易云插件 0.1.15 的真实扫码已由用户确认成功；当前版本的线上点赞/发评论、真机锁屏/后台长播、蓝牙切换、CarPlay 显示与控制和特效功耗尚未实测，不能用演示截图替代。平台风控错误保留已有内容并允许重试，不绕过风控。系统支持的本地格式以 AVFoundation 实际可解码能力为准。

## 来源与许可证

主体沿用根目录 AGPL-3.0，保留 [LICENSE](LICENSE)。平台合同对照桌面 `plugins/builtin/bilibili`、`internal/music` 与已验证旧实现；测试媒体来自桌面已有测试资产。歌词舞台参考桌面的 folia-major 思路。QuickJS 2026-06-04 为 MIT（来源和摘要见 `Sources/MBJavaScript/UPSTREAM.md`）；ZIPFoundation 固定 0.9.20，为 MIT。第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
