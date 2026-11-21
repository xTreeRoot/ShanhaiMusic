# 移动端背景皮肤验收

Android 0.1.30（versionCode 31）/ iOS、iPadOS 0.1.0 Build 31。

图片在真实发现页预览，支持缩放、拖动、主题色、透明度、模糊度及“应用于音乐播放器背景”。确认后保存图片与全部参数；取消、无效网络图片不覆盖已有设置。背景按同一窗口坐标连续显示，分组行保留透明层级，深浅色共用参数。播放器关闭皮肤时使用原默认背景。

## 截图索引

以下均为无账号的合成图片与本地音频测试内容。

| 场景 | Android | iPhone | iPad |
| --- | --- | --- | --- |
| 发现页调整皮肤 | [预览](android-discovery-preview.png) | [发现页实时预览](phone-discovery-preview.png) | [发现页实时预览](pad-discovery-preview.png) |
| 搜索 | [搜索](android-search.png) | [搜索页连续背景](phone-search.png) | [搜索页连续背景](pad-search.png) |
| 资料库 | [资料库](android-library.png) | [资料库连续背景](phone-library.png) | [资料库连续背景](pad-library.png) |
| 我的 | [我的](android-account.png) | [我的连续背景](phone-account.png) | [我的连续背景](pad-account.png) |
| 设置 | [设置](android-settings.png) | [设置连续背景](phone-settings.png) | [设置连续背景](pad-settings.png) |
| 播放器启用皮肤 | [启用](android-player-enabled.png) | [音乐播放器应用皮肤](phone-player-enabled.png) | [音乐播放器应用皮肤](pad-player-enabled.png) |
| 播放器关闭皮肤 | [关闭](android-player-disabled.png) | [关闭播放器应用皮肤](phone-player-disabled.png) | [关闭播放器应用皮肤](pad-player-disabled.png) |
| 深色资料库 | [资料库](android-library-dark.png) | [深色资料库连续背景](phone-library-dark.png) | [深色资料库连续背景](pad-library-dark.png) |
| 深色设置 | [设置](android-settings-dark.png) | [深色设置连续背景](phone-settings-dark.png) | [深色设置连续背景](pad-settings-dark.png) |

## 验证

- Android 背景参数行为回归、lint、Debug 与 Release 构建通过；两个界面行为回归连续五轮通过（共 10 项），没有失败重试。
- Swift 全量 196 项通过；iPhone 与 iPad 各两个界面行为连续五轮通过（各 10 项），验证裁剪、实时参数调整、确认落盘、取消保留和无效地址；未使用失败重试。截图均取最终第五轮并经人工查看。
- 皮肤改动后，iPhone 与 iPad 各一次真实 AVPlayer 视频“返回 → 浮窗 → 展开”相关回归通过，保留已验收的卡死修复。
- 正式 APK 原签名、包名、versionCode 与 16 KiB 对齐已校验。公开 iOS 资产为未签名 IPA，设备覆盖使用同一源码生成的签名 app。
- 平台真实封面与账号请求不属于皮肤回归；系统画中画离开 App 后的恢复未在本轮单独验收。
