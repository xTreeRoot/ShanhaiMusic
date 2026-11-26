# 插件中心 · Android 0.1.57

2026-11-26：插件管理增加已安装/社区切换、GitHub 搜索、分页、重试、插件图标和安装后定位高亮。保留名称排序、栏目设置、ZIP/文件夹、GitHub 与 Stars 导入、节点目录和私有数据。

验证：196 项 JVM 单测、Lint 与 Debug APK 构建通过；专用无窗口模拟器的 `PluginCenterDeviceTest` 验证社区搜索入口及返回已安装管理；附加 `communityLive=true` 已验证 GitHub 真实目录返回可安装仓库。社区加载界面见 [community.png](community.png)，原管理功能见 [installed.png](installed.png)。分页失败恢复、去重、取消旧查询和图标路径由 `PluginCommunityTest` 验证；界面截图不代表已完成第三方插件登录或播放。

首次启动模拟器遇到 System UI 无响应，恢复系统界面后继续。初次 UI 测试对更多菜单的名称不匹配，已依据真实可访问名称修正。原始记录位于本工作树 `.build/plugin-center/`，没有清空或卸载真实设备上的应用。
