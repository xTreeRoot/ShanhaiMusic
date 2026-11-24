# 紧凑页面、系统授权与 CarPlay 验证

2026-11-19，基于 ios 分支 `6cdfb5d` 的改动，交付产品版本为 0.1.0（18），宿主兼容版本为 0.1.26。截图来自 build 17 开发候选，交付时仅将构建号递增到 18；生产源码未变。

四个根页面取消 hero 大标题，保留操作栏；发现页音源独占内容区域的一整行，空间不足时横向滚动。音源切换不挤占播放模式与搜索按钮。CarPlay / 锁屏共用的正在播放信息将当前歌词放在主标题、歌曲名放在副标题，继续沿用音频时钟逐句刷新；无歌词时主标题回退到歌名。

YouTube Music 插件 0.1.5 通过新增的通用 `host.oauthSignIn` 使用系统授权页面与原生 PKCE 公共客户端，替代 iOS 内嵌网页登录。Google Cloud 的 iOS 客户端、测试用户、权限声明和 YouTube Data API v3 已配置。Google 外部测试模式仅允许加入的测试用户，刷新令牌通常 7 天失效。用户手机已完成 Google 授权返回，但随后 `account/account_menu` 返回 HTTP 400，账号读取仍待修复；真车 CarPlay 尚未验收。

## 验证结果

- Swift 全量行为回归 162 项通过；最终插件 fixture 同步后，OAuth、QuickJS 桥接、外部插件和系统播放相关 29 项通过。
- YouTube 插件语法检查及 14 项 Node 行为测试通过，覆盖会话验证后保存、保留退出会话、复用、刷新合并、失败和迟到响应。插件 ZIP 与工作区、宿主测试 fixture 逐字节一致。
- 通用 iPhone/iPad arm64 Release 和模拟器 build-for-testing 通过。未签名 IPA 检查通过：唯一应用目录、可执行权限、iPhone/iPad 设备族、音频后台能力及 OAuth 回调配置。
- iPhone 4 项 UI 方法通过：四页紧凑布局、搜索键盘、歌词设置、插件安装/保存/切源/分页；iPad 2 项通过：四页紧凑布局、完整插件流程。两音源在空间足够时同时可点击，占满内容宽度并与模式按钮分行；未实测大量音源溢出及本轮横屏布局。
- 26 张演示截图来自通过的测试方法，已检查四页和音源栏视觉效果，见 [截图索引](SCREENSHOTS.md) / [图库](index.html)。截图不是实际账号或车机验收。

初次 iPhone 歌词设置测试未滚动定位开关，已补上可达性滚动；插件页曾未弹出，释放构建空间后完整流程通过，未据此修改产品逻辑。初次 iPad 插件测试被键盘和下方保存按钮阻挡，已补上隐藏键盘与表单滚动，再运行完整流程通过。历史失败保留在本地结果包，不计入通过数。

本地结果包位于 `ios/.build/Review18/`：`Phone.xcresult`（四页、键盘通过）、`PhoneFix.xcresult`（歌词设置、插件流程通过）、`Tablet.xcresult`（四页通过）、`TabletFix.xcresult`（插件完整流程通过）。生产源码、工程和脚本的 122 项 SHA-256 见 [sources.sha256](sources.sha256)，截图来源和摘要见 [manifest.json](manifest.json)。

## 流式播放审查

宿主使用 AVFoundation HTTP Range，每块 256 KiB，内存保留 16 块；seek 直接请求目标块。插件只解析媒体地址和请求头。指定 Range 不兼容错误会退回整文件下载完再播。开启缓存时流播 8 秒后另行全量缓存，存在重复流量，本轮没有改变此策略。

游客实测 B 站、网易云 0.1.17、喜马拉雅 0.1.7、汽水 0.3.0 的开头和中段均返回 HTTP 206，AVFoundation 未读完整文件就获得可播元信息。汽水前两条解析失败，第三条成功；YouTube 0.1.4 解析超时，模板 0.1.0 示例 WAV 为 TLS 错误，后两项仅完成源码审查。本次探针日志保留于 `/tmp/mb-stream-audit-20261119/probe.log`，不含登录会话。

本地未签名候选包：`ios/.build/artifacts/MB-iOS-0.1.0-17-oauth-review-unsigned.ipa`；YouTube 插件包：`/Users/xugenyin/file/mygo-music-youtube-music/dist/youtube-music-0.1.5.zip`，源码已推送至 `095e24d`。宿主和插件须一起更新，旧宿主不提供系统授权能力；插件尚未发布 Release，远程检查更新暂时拿不到此修复。

## 手机安装

build 18 已通过局域网覆盖安装并成功启动，使用原个人团队重新生成的有效签名。YouTube 插件 0.1.5 的六个公开文件已合并更新，读回与已推送代码逐字节相同；原四个插件目录和 18 个插件数据路径仍在，未读取账号存储内容。签名、安装、启动和读回日志位于忽略目录 `ios/.build/update18/` 及 `ios/.build/logs/device-build.log`，已签名包为 `ios/.build/artifacts/MB-iOS-0.1.0-18-development.ipa`。
