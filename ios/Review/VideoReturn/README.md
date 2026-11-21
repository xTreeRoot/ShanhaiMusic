# iOS 视频顶部与返回卡死验收

2026-11-21：视频页隐藏状态栏后仍保留顶部容器安全区，iPhone 真实视频表面的起点为 62pt。视频页现延伸至容器顶部；验收同时检查画面位置和实际截图。

真机 Build 30 的三份诊断记录均为 FRONTBOARD `0x8BADF00D` watchdog。两份为 scene-update 超过 10 秒，另一份为退出超时。主线程均位于 `NativeVideoSurface.updateUIView` → `VideoPictureInPicture.attach` → AVKit 画中画控制器的 KVO 注册或注销；其中一份包含 `VideoPictureInPicture.controller.setter` → `ObservationRegistrar.withMutation`。这将无响应定位到 SwiftUI 更新期间反复建立、销毁画中画控制器的路径。原始诊断包含设备信息，保留在仓库外。

修复将 controller、layer 等 UIKit 承载状态标记为 `@ObservationIgnored`，仅保留系统画中画的活动状态供界面观察。视频页、竖屏视频与浮窗只在自身可见时挂载画面；同一播放器不重复绑定到 AVPlayerLayer，换承载对象前移除旧委托及关联。

## 截图

| 场景 | iPhone | iPad |
| --- | --- | --- |
| 修复前顶部安全区黑边 | [修复前](iphone-before-top-safe-area.png) | — |
| 真实视频延伸到顶部 | [视频页](iphone-video-top.png) | [视频页](ipad-video-top.png) |
| 播放中返回并显示应用内小窗 | [浮窗](iphone-floating-window.png) | [浮窗](ipad-floating-window.png) |

修复后的截图均取自最终连续五轮回归的第五轮，已人工查看。

## 验证

- Swift 视频、预加载、播放会话与取消行为回归 22 项通过；同一工作区全量 Swift 194 项通过。
- iPhone、iPad 模拟器各连续五轮通过 `testReadyVideoReachesTopAndReturningToFloatingWindowStaysResponsive`。每轮加载真实本地 MP4，等待 AVPlayerLayer 显示画面并开始播放，连续三次返回浮窗及展开，检查画面顶部位置，再关闭浮窗、进入搜索页验证界面仍响应。设置为首次失败即停止，未使用失败重试。
- 本地视频源仅在 DEBUG、`--demo` 和显式 `MB_VIDEO_UI_FIXTURE` 环境变量同时满足时启用，Release 不包含该测试源。MP4 为自行生成的 60 秒测试图案，无平台请求或账号数据。
- Debug iOS 测试构建通过；主会话的正式设备 Release 构建包含本次修复并成功完成。`git diff --check` 通过。

最终模拟器结果保留在仓库外的 `/tmp/mb-video-return-repeat-phone.xcresult` 和 `/tmp/mb-video-return-repeat-pad.xcresult`，行为测试日志为 `/tmp/mb-video-return-behavior.log`。

真机旧版 watchdog 已有直接堆栈证据；本轮模拟器未复现旧版卡死。修复包在真机的播放后返回、反复展开，以及退到后台启动系统画中画后恢复界面，仍需实际验收，不能由应用内浮窗回归替代。正式包覆盖安装与用户数据核对由主会话统一执行。

## 真机覆盖与用户复验

正式 Build 30 已无线覆盖 iPhone，覆盖前后 132 个私有文件摘要一致。用户随后确认播放视频后“返回 → 展开小窗”已经正常；系统画中画离开应用及恢复仍未单独验收。私有备份与崩溃日志保留在仓库外。
