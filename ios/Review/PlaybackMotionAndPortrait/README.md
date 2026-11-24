# 播放手势、竖屏与移动端分页验收

本批源码版本：Android 0.1.35（versionCode 36）、iOS 0.1.0（Build 36）。`source-sha256.json` 记录构建时的生产源码摘要。

## 行为与截图

- [Android 竖屏拖动中的相邻资源](screenshots/android-video-drag-in-progress-new.png)：下一条的标题随画面从顶部露出；不足阈值回弹，不切换队列。
- [Android 插件列表](screenshots/android-plugin-grouped-overview.png)：透明分组组件和折叠权限。
- [Android 原地续页](screenshots/android-pagination-loaded-in-place.png)：可见页尾触发请求，追加并去重，不压入新的页面。
- [iPhone 竖屏互动](screenshots/ios-竖屏-主画面与右侧互动.png)：画面填充、点赞、评论与更多。
- [iPhone 评论面板](screenshots/ios-竖屏-评论底部面板.png)：展开评论时画面适配可见区域。
- [iPhone 音乐切歌](screenshots/ios-播放器-左右滑动切歌.png)：真实队列相邻资源预览。
- [iPad 竖屏](screenshots/ipad-portrait.png)：相同比例与互动入口。

截图使用固定测试资源。测试视频和封面不代表 YouTube 线上内容；真实栏目分页另外使用账号只读验证。

## 验证

- Swift 行为回归 207 项通过；iPhone、iPad 两个手势 UI 回归分别连续五轮，共 20 项通过。
- Android JVM 162 项通过；Debug、AndroidTest、Release 构建及 lint 通过。
- Android 10 项设备行为回归连续五轮通过，共 50 项；涵盖真实相邻队列预览、取消回弹、缓冲冻结、备用地址和两次 seek 后暂停、目录触底续页、平台切换与插件页交互。
- 宿主插件测试通过。YouTube 0.2.5 独立仓库 44 项测试通过；真实账号验证推荐音乐、Shorts、推荐视频各栏目独立续页。
- 本批未更改桌面生产代码。

## 真机限制

iPhone 已无线覆盖安装 Build 36，安装前后 129 个私有文件的规范化摘要一致，并已成功启动。Android 正式版只使用原签名覆盖，不卸载或清数据。插件 0.2.5 的手机确认状态另行核对。
