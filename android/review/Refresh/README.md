# Android 0.1.37：下拉刷新

主动下拉刷新向插件传递 `refresh: true`，同时跳过宿主新鲜缓存并写回相同缓存键。首页、分类、栏目更多页、集合与实体均沿用现有通用目录合同，没有在 App 内嵌入 YouTube 请求。刷新前捕获路由，分类切换时忽略迟到结果。

## 验证

- JVM 160 项通过；Release lint、Release/Debug/AndroidTest 构建通过。
- 插件内部快照刷新、首页与独立导航刷新、双行卡片刷新、分类原地切换四项设备回归，各连续五轮通过。
- [Shorts 两行全部更新](android-pull-refresh-two-rows.png)：Short 1-* 替换为 Short 2-*；两行使用不同资源，标题和更多分列左右。
- [首页及独立栏目更新](android-pull-refresh-replaced-home-and-shelf.png)。
- [分类切换](android-catalog-filter-tabs.png)。
- 截图来自无账号测试插件，无封面链接时显示通用占位。
- YouTube 0.2.6 的 50 项回归及真实账号只读验证通过；音乐推荐、Shorts 栏目和直接 Shorts 刷新均取得新内容，后续分页不重复首屏。

原签名证书与 16 KiB 对齐通过。无线覆盖安装 versionCode 38，首次安装时间不变。用户已确认在手机更新插件；小米不允许 ADB 点击，未通过调试工具读取正式版私有插件目录。

正式源码摘要见 [iOS 共享验收索引](https://github.com/xTreeRoot/ShanhaiMusic/blob/ios/ios/Review/ExplicitRefresh/source-sha256.json)。
