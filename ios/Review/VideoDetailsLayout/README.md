# 普通视频详情布局与插件显示入口

Android 0.1.41（42）、iOS 0.1.0（43）、macOS 0.1.16。

## 改动

- 普通视频详情页保留 iOS 系统状态栏，顶部使用主题背景。竖屏短视频和独立全屏继续使用各自的全屏布局。
- 普通视频按实际宽高比明确分配画面高度，手机优先使用全宽，避免详情区把画面挤矮；不拉伸或裁切普通视频。iPad 横屏保留左右布局。
- 两端上一首、下一首、点赞、随机和循环模式放在同一行，使用文字和图标操作。窄屏或大字体可横向滚动操作行。Android 普通视频内容显式使用主题前景色，避免暗色模式出现黑色文字和图标。
- 移除发现页“栏目”入口，改到插件列表每项最右侧“设置显示”。复用插件清单，不需要启动插件或启用插件即可配置；保存、取消、排序、双副本本地持久化和节点同步继续沿用原实现。Android 先读本地缓存再创建编辑草稿。桌面同步迁移该入口。

## 验证记录

- `npm run test:plugins`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .` 通过。桌面设置页真实入口、取消、拖动与保存回归连续五轮通过；MM_FRAMES 截图核对，macOS arm64 构建成功。
- Swift 全量 221 项、Android JVM 168 项通过；Android Release lint 为 0 errors、41 warnings。iPhone/iPad 通用 Release、Android Release/Debug/AndroidTest 构建通过。
- Android 设置显示入口与同排视频操作连续五轮通过：验证停用插件可配置，取消不保存，保存及拖动排序保留，右侧按钮不触发插件详情；验证普通视频行内动作、随机与循环状态变更，并使用本地 ExoPlayer 验证首帧。
- iPhone 三项相关回归连续五轮通过（15 次）：插件列表入口/取消/保存/拖动与重开、普通视频状态栏和同行动作、真实视频返回/展开小窗。iPad 同三项通过。使用真实本地 AVPlayerLayer/ExoPlayer 首帧验证，不把空态当成功。

截图均为隔离演示数据；真实画面回归使用本地视频文件，不代表真实平台接口或账号验收。

截图见 [索引](SCREENSHOTS.md) 与 [查看器](index.html)。Android 插件项见 [设置显示入口](android-plugin-display-settings.png)，普通视频见 [同排操作](android-inline-video-actions.png)。生产源码摘要见 `source-sha256.json`；桌面窗口截图见 [栏目设置](desktop-discovery-editor.png)。

## 设备覆盖

Android 已无线覆盖 0.1.41（42），新旧 APK 原签名证书一致，首次安装时间未变，启动成功。Release 不可 run-as，未读取 Android 私有数据摘要。

iPhone 已无线覆盖 Build 43，安装前后 133 个私有文件的规范化摘要相同，启动成功。安装前独立备份，无卸载、清数据或整域覆盖。
