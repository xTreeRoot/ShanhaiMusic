# 插件中心 · Android 0.1.58

2026-11-26：插件管理增加已安装/社区切换、山海维护 Stars 列表内搜索、分页、重试、插件图标和安装后定位高亮。保留名称排序、栏目设置、ZIP/文件夹、单个 GitHub 仓库安装、节点目录和私有数据。

验证：196 项 JVM 单测、Lint 与 Debug APK 构建通过；专用无窗口模拟器的 `PluginCenterDeviceTest` 验证社区搜索入口及返回已安装管理；附加 `communityLive=true` 的两项实网测试已验证维护列表返回可安装仓库及真实服务端列表。模拟器使用现有 Mac HTTP 代理作为测试网络，结束后恢复原代理配置。社区加载界面见 [community.png](community.png)，原管理功能见 [installed.png](installed.png)。分页失败恢复、去重、取消旧查询和图标路径由 `PluginCommunityTest` 验证；界面截图不代表已完成第三方插件登录或播放。

首次启动模拟器遇到 System UI 无响应，恢复系统界面后继续。初次 UI 测试对更多菜单的名称不匹配，已依据真实可访问名称修正。原始记录位于本工作树 `.build/plugin-center/`，没有清空或卸载真实设备上的应用。

一键导入/覆盖入口已移除，社区仅显示维护列表中的插件，排除主程序和插件模板。
