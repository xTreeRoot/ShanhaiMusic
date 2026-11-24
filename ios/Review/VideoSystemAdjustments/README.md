# 视频画面系统音量与亮度手势

Android 0.1.38（versionCode 39）、iOS 0.1.0（Build 40）。

横向视频画面左半边上下拖动调整系统媒体音量，右半边调整系统屏幕亮度。内嵌与全屏共用手势，提供图标、百分比及进度反馈；控件、进度条保留原操作，竖屏短视频不接入调节手势。

安卓通过 `AudioManager.STREAM_MUSIC` 和 `Settings.System` 读写实际系统设置；首次调整亮度会提供“允许修改系统设置”的授权入口，拒绝授权不写入设置。为让亮度设置实际生效，手动调节会将系统自动亮度切成手动，退出播放器后保留系统选择。未使用 Window 局部亮度覆盖，也不改变播放器增益。

iOS 通过 `MPVolumeView` 系统音量滑块与当前窗口所在 `UIScreen.brightness` 调节。系统不提供可用控制时给出明确反馈，不以内部播放器数值替代。

## 截图

| 设备 | 内嵌亮度 | 全屏亮度 | 内嵌音量 | 全屏音量 |
| --- | --- | --- | --- | --- |
| iPhone 模拟器 | [查看](iphone-inline-brightness.png) | [查看](iphone-fullscreen-brightness.png) | [查看](iphone-inline-volume.png) | [查看](iphone-fullscreen-volume.png) |
| iPad 模拟器 | [查看](ipad-inline-brightness.png) | [查看](ipad-fullscreen-brightness.png) | [查看](ipad-inline-volume.png) | [查看](ipad-fullscreen-volume.png) |
| Android 模拟器 | [查看](android-inline-system-brightness.png) | [查看](android-fullscreen-system-brightness.png) | 同组行为回归读回系统媒体音量 | 同左 |

截图来自无账号测试数据。iOS 模拟器没有真实音量控制能力，音量图显示不可用反馈；屏幕亮度读回固定为模拟器值。这些截图仅验证手势、控件和状态反馈，不能替代实际硬件调节验收。

## 验证

- Swift 全量 208 项行为回归通过，覆盖上下拖动、反向拖动不累积误差、亮度下限及非法几何值。
- iPhone、iPad 内嵌与全屏手势 UI 各连续五轮通过；原有视频单/双击控制和竖屏队列切换另两项回归通过。模拟器与通用 iPhone/iPad 真机 Release 构建通过。
- Android JVM 162 项通过；Release lint 0 错误、39 警告；Release、Debug、AndroidTest 构建通过。
- Android 两项设备回归连续五轮通过，直接读回系统媒体音量和系统亮度，覆盖内嵌/全屏上下调整、未授权不写亮度、拖动不误触暂停及竖屏不调节。初轮发现首次全屏的 Android 系统教学浮层遮挡界面，测试准备显式确认该系统教学后重新连续验证；另修正测试重复读取瞬时无障碍节点的问题，没有修改产品手势或降低断言。

## 真机覆盖

iPhone 已无线覆盖 Build 40，安装前后 129 个私有文件的规范化摘要一致，启动成功。安卓原签名无线覆盖 0.1.38，已装包和新 APK 的证书一致、首次安装时间不变，启动成功。没有卸载或清理应用数据，独立备份保留。两台真机的手势与系统控制中心同步变化仍需实际使用验收。

原签名证书、包名、版本及 16 KiB 对齐均核验通过。公开 IPA 移除了个人签名和描述文件。生产源码摘要见 [source-sha256.json](source-sha256.json)，尚未完成的栏目偏好功能不在本次发布中。
