# MB iPhone / iPad

build 67 为插件管理增加「已安装 / 社区」切换、山海维护 Stars 列表内搜索和分页安装、包内图标及作者头像；安装完成返回已安装并自动定位高亮。去掉一键导入/覆盖入口，名称、排序、栏目设置、手动安装、节点目录和账号数据继续保留。验收与截图见 [插件中心](Review/PluginCenter/README.md)。

SwiftUI 原生客户端，最低 iOS / iPadOS 17，Swift 6。源码在 `ios` 分支维护，工程入口为 `ios/MBiOS.xcodeproj`。产品版本与构建号以工程设置为准，功能对照基准见 [PARITY.md](PARITY.md)。

build 62 的 iPad 使用居中横向导航，依次为发现、搜索、资料库、我的、设置、模式切换，不再显示发现侧栏。设置直接在标签页打开，模式切换保留当前页面与播放会话；iPhone 沿用系统标签栏。

build 20 增加播放到 70% 时预取下一首地址和首个 256 KiB 数据块，切歌复用，改音质、切源和改队列时取消；预取失败不打断当前播放。播放器显示中文模式反馈和底部功能小字，横屏支持歌词、律动、待播清单、选歌、评论。音乐与视频统一使用小圆点细进度条；视频单击切换控制条、双击播放/暂停，播放中无操作 4 秒收起，拖动时保留。YouTube 0.1.6 使用官方频道接口验证 OAuth 账号，登录已获用户确认。build 21 修复 iPad 播放器窄弹层导致的横屏失效；iPhone/iPad 相关 UI 回归通过。build 22 补齐插件 fetch 的手动跳转和逐跳网络权限检查，并为扫码登录申请短时后台执行。汽水 0.3.5 改用官方汽水专用扫码入口后仍返回 2156，0.3.6 增加本机匿名设备注册后仍返回 7。build 23 与汽水 0.4.0 使用隔离 WebKit 网页加载官方安全 SDK，并由同一浏览器会话取码和确认；2026-11-20 已装手机，用户确认授权登录成功并显示个人歌单。验收状态与截图见 [播放器验证](Review/PlayerExperience/README.md)。

## 在另一台 Mac 编译

安装完整 Xcode，首次打开并完成组件安装。终端执行：

```bash
git clone --branch ios https://github.com/xTreeRoot/ShanhaiMusic.git
cd ShanhaiMusic/ios
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
swift package resolve
open MBiOS.xcodeproj
```

Xcode 选择 `MBiOS` scheme；模拟器可直接运行。装到自己的 iPhone 时，选择 MBiOS target → Signing & Capabilities → Automatically manage signing，在 Team 中选自己的账号团队，顶部选真实手机再运行。仓库的 Team 留空，不携带个人证书或描述文件。详细步骤见 [INSTALL.md](INSTALL.md)。

## 已有功能

build 15 将浏览页的“视频模式”设为新选内容的播放偏好，不会打开当前资源。视频详情将播放器固定在顶部（宽屏平板在左侧），在同页切换简介、评论、字幕和队列。“设置 → 显示”下方可直接进入插件管理。

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

浏览页模式只影响主动点选的资源，不打开当前曲目，也不受播放、暂停或队列推进影响。要看正在听的曲目，可在音频播放页点“观看当前视频”或封面，进入同一播放会话的视频详情。视频静音跟随同一音频时钟，保留曲目、进度和暂停状态；切回听歌取消视频加载并隔离迟到响应。没有视频能力的音源显示明确状态，本地纯音频保持听歌界面。重启恢复和返回浏览不会自行打开视频。

[YouTube Music 兼容插件 0.1.6](https://github.com/xTreeRoot/mygo-music-youtube-music) 使用原生 AAC 直链，已验证真实首页、搜索和完整音频可播放；设备网络仍需能访问 YouTube，授权登录已获用户确认。[汽水音乐兼容插件 0.4.0](https://github.com/xTreeRoot/mygo-music-soda) 提供公开推荐歌单、分类和可用完整 AAC；账号及个人歌单已有合同回归，用户已在真机确认扫码登录成功、显示个人歌单，不声明搜索或会员专属取流。两个插件均不启动桌面辅助进程。

## CarPlay 系统正在播放与逐句歌词

继续使用 AltStore 个人签名。先在手机打开山海开始播放，连接 CarPlay 后打开车机自带的“正在播放”，可使用系统播放/暂停、上下曲与进度控制；实际显示和可用按钮由车机决定。无需为这条路径增加 CarPlay 专属 entitlement，App 没有独立的车机图标或歌单页面。

iOS 将当前输出识别为车载音频时，山海只播放音频，停止当前视频和视频预加载，不覆盖原来的播放模式偏好。只被系统识别为普通蓝牙音频的设备不会触发该规则。退到后台时，停掉界面进度刷新和不可见的视频预热，不影响系统播放控制与歌词。

诊断记录位于 App 沙盒的 `Library/Application Support/MBMobile/playback-diagnostics.json`，保留最近 128 条启动、前后台、车载路由与播放状态事件，包含时间与构建号，不含歌曲信息、地址或凭据。记录会在事件发生时尽力写入，不等 App 退出；被系统终止时最后一条未写完的事件可能丢失，是否被强杀仍以系统 `.ips` 报告为准。

默认开启「设置 → CarPlay 与锁屏 → 在主标题显示当前歌词」：歌曲名放入副标题（系统 artist 字段），主标题按音频播放时间替换为当前句；前奏、间奏、无歌词或空白歌词行回退到歌曲名。暂停时保留对应句，拖动、切曲、歌词迟到及开关变更立即刷新；普通进度按秒更新，句子变化不受整秒节流限制。关闭开关立即恢复正常的歌曲名和歌手信息，选择会保存。

手机锁屏和控制中心也会使用同一份文字。系统决定刷新速度、截断与文字滚动，此功能不提供自定义滚动歌词界面。真实 CarPlay 尚未实测，不能将元数据回归或设置截图当成车机显示验收。

## 插件登录（宿主兼容版本 0.1.28）

`host.capabilities.webSignInHTML` 为真时，`host.webSignIn({url, cookiesOf, until, userAgent, html})` 可在独立临时 WKWebView 中加载插件提供的登录文档。`html` 必须为非空字符串、UTF-8 不超过 256 KiB，页面与 Cookie 目标须使用 HTTPS、同域名及端口，并通过插件网络权限检查；省略 `html` 保留原有加载远程网页登录方式。页面使用自己的浏览器网络栈和非持久 Cookie 库，达到 `until` 中的 Cookie 条件后把匹配目标路径的 Cookie 交给插件，是否保存由插件在线验证决定。

HTML 页面可通过 `window.webkit.messageHandlers.mbSignIn.postMessage({error: message})` 报告失败；宿主只接受初始 HTTPS 同源顶层页面、长度受限的错误。失败、取消和旧页面迟到的 Cookie 不会完成后续登录，WebKit 进程终止会正常结束本次登录。汽水 0.4.0 在此页面加载平台安全 SDK、初始化浏览器会话、显示专用二维码并等待确认，切到后台暂停轮询，回前台恢复；平台错误不自动重试。用户已在真机确认官方 App 扫码后登录成功并显示个人歌单；平台额外安全验证 2046 尚未实现交互处理。

`host.oauthSignIn({authorizationEndpoint, tokenEndpoint, clientID, redirectURI, scopes})` 返回授权服务器的 token JSON。两个服务端点必须使用 HTTPS 并在插件 `permissions.network` 内；`redirectURI` 必须为 App 已注册的自定义回调 scheme。宿主通过 `ASWebAuthenticationSession` 打开系统授权页面，每次生成独立 state 和 PKCE S256，校验回调后用 POST 交换一次性授权码，禁止 token 请求自动重定向。取消会关闭当前授权，迟到回调不能完成后续请求。

YouTube Music 0.1.6 使用 MB 自己的公开 iOS OAuth 客户端和 YouTube 权限；当前 Google Cloud 为外部测试模式，已加入的测试用户可以登录。测试模式下 Google 刷新令牌通常 7 天失效，需要再次授权。插件把会话保存在现有私有数据目录，退出仅停用，重新登录先验证后复用；候选授权验证通过才替换旧会话。源码仅包含公开客户端 ID 和回调 scheme。系统授权页面遵循系统网络设置，MB 的 URLSession 代理白名单不控制系统浏览器。

## 流式播放检查（2026-11-19）

音频、视频均通过 AVFoundation 和 HTTP Range 播放。插件 `stream()` 负责解析直链和请求头，宿主按需请求 256 KiB 块，内存保留 16 块，可直接请求 seek 所在区间。并非先下载整首，也不是 HLS/DASH 自适应直播。服务端不支持 Range 等特定错误会退回整文件下载，下载完成后开始播放。

本次使用隔离游客会话检查星标插件最新 main：网易云 0.1.17、喜马拉雅 0.1.7、汽水 0.3.2 和内置哔哩哔哩的真实媒体开头/中段均返回 HTTP 206，AVFoundation 在未读完整文件前获取到可播元信息。YouTube 0.1.4 媒体解析超时，模板 0.1.0 的示例 WAV 请求遇到 TLS 错误，仅能确认代码合同，未完成本次真实流播验证。开启播放缓存后，当前实现会在开始流播 8 秒后另行全量下载缓存，存在重复流量；本轮未修改这项策略。

## 模块与数据

每个插件的数据目录保留最近 24 次登录、验号、退出和登录缓存清理的 `account-diagnostics.json`，以及最近一次扫码或授权的 `sign-in-diagnostics.json`。诊断记录操作结果、存储读写计数、请求耗时、HTTP 状态和有界平台错误码；只记录请求是否声明 Cookie / Authorization 头及服务端设置 Cookie 的数量，不记录值、存储键、账号资料、URL 路径原文或查询、请求及响应正文，也不上传节点。平台未返回具体原因时，错误码只能证明当次会话没有通过验证。

| 目录 | 职责 |
| --- | --- |
| `MBiOS/` | App 入口、图标、系统配置、中英文资源 |
| `Sources/MBFoundation/` | 来源协议、业务状态、网络、存储、原生播放、插件宿主 |
| 独立信息源插件 | 平台合同、签名、会话和媒体解析均在插件仓库，App 不内置平台适配器 |
| `Sources/MBInterface/` | 手机/平板 SwiftUI 页面 |
| `Sources/MBSignal/` | 音频响度及低音采样 |
| `Sources/MBJavaScript/` | QuickJS C 桥与上游解释器 |
| `Tests/`、`MBiOSUITests/` | 行为/合同回归、原生媒体验证与界面操作截图 |
| `Review/` | 页面截图图库、覆盖索引与验证说明 |

数据位于 App 沙盒 `Application Support/MBMobile`。Cookie 与设置留在沙盒文件，退出仅停用；不读取桌面账号，不保存临时媒体 URL，不把证书、账号或沙盒资料提交 Git。文件写入采用原子提交，iOS 文件保护允许首次解锁后后台播放。

## 验证与打包

本轮四页紧凑布局、音源栏、系统 OAuth、CarPlay 元数据与流播检查的测试结果、26 张截图和真机未验收范围见 [验证记录](Review/CompactOAuth/README.md)。

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

`package-ipa.sh` 按 Xcode 工程中的 `MARKETING_VERSION` 和 `CURRENT_PROJECT_VERSION` 输出 `.build/artifacts/MB-iOS-<版本>-<构建号>-unsigned.ipa` 及源码 SHA-256 清单。公开 IPA 随四端正式版一起发布，须自行签名后安装。现有客户端从 `mb-ios-v<版本>-build<构建号>` 平台更新源识别 `MB-iOS-v<版本>-build<构建号>-unsigned.ipa`，发布时为该更新源提供符合命名规则且与正式 IPA 的 SHA-256 一致的附件。[GitHub Latest](https://github.com/xTreeRoot/ShanhaiMusic/releases/latest)

### 0.1.0 (17) - 2026-11-19

- 支持自托管节点绑定和已验证账号资料加密同步，不通过资料接口上传 Cookie。线上真实账号同步尚未验收。
- 补充音视频分段缓存、有限重试及原位置恢复；尚未实现同容器音视频共用分段，断网后的音画偏差尚未实测。
- 插件页支持音源标签、GitHub Stars 批量导入、检查更新、一键更新和删除；新增独立皮肤及已知不可播原因提示。

UI 回归使用后台启动的 Simulator 设备和 XCTest，不需要打开 Simulator.app 或控制 Xcode 窗口。可先用 `xcrun simctl list devices available` 找设备，再按 [Review/README.md](Review/README.md) 的命令运行和导出截图。Release 忽略 `--demo`；Debug 演示资料、插件、静音音频与实际沙盒数据隔离。

2026-11-18 build 7：127 项 Swift Testing 回归通过（Foundation 100、B 站 27，参数化测试另含多个输入）。本轮游客首页四区各 6 条、更多页分别 30/20/100/40 条，搜索“夜曲”17 条，真实音频解码 4096 PCM 帧，视频 HTTP 206 分段加载与原生画面输出通过。封面探针因平台分区偶发错误，本轮获得的三个分区 18 张图片全部下载解码成功；页面保留失败分区并支持下拉重试。最终手机/平板界面与 Release 结果见 [PARITY.md](PARITY.md) 和 [截图说明](Review/README.md)。

## 宿主差异与未验收项

桌面托盘、刘海、窗口和本机进程能力对应 iOS 导航、锁屏及控制中心；iOS 插件不提供外部进程、远程播放器和桌面端口监听回调 `waitForRedirect`。依赖完整浏览器 Web API 的插件可能需要适配；已验证原版 B 站 JS 可在解释器执行签名搜索合同，但不能据此宣称所有桌面插件通用。网页登录使用 WKWebView 自己的网络栈，域名代理白名单只控制 URLSession 的 API/图片/媒体请求。

build 3 曾完成免费 Personal Team 真机签名与安装。网易云插件 0.1.15 的真实扫码已由用户确认成功；当前版本的线上点赞/发评论、真机锁屏/后台长播、蓝牙切换、CarPlay 显示与控制和特效功耗尚未实测，不能用演示截图替代。平台风控错误保留已有内容并允许重试，不绕过风控。系统支持的本地格式以 AVFoundation 实际可解码能力为准。

## 来源与许可证

主体沿用根目录 AGPL-3.0，保留 [LICENSE](LICENSE)。平台合同对照桌面 `plugins/builtin/bilibili`、`internal/music` 与已验证旧实现；测试媒体来自桌面已有测试资产。歌词舞台参考桌面的 folia-major 思路。QuickJS 2026-06-04 为 MIT（来源和摘要见 `Sources/MBJavaScript/UPSTREAM.md`）；ZIPFoundation 固定 0.9.20，为 MIT。第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 节点完整备份

在“山海服务 → 设备设置”点“备份本机”，会加密保存插件包、名称、启用状态、排序、完整插件设置、私有数据以及资料库、下载、搜索历史和播放队列。“从其他机器恢复”选择源设备并点“恢复到本机”，空白客户端会自动补装缺失插件；已有私有文件保留，空队列暂停恢复。同平台恢复完整 App 偏好，跨平台恢复支持的通用偏好。节点身份不迁移；旧备份仍只恢复设置。需要节点 0.1.3，平台登录可能需要重新验证。完整备份可能包含插件登录信息，全部内容通过现有恢复密钥端到端加密。
