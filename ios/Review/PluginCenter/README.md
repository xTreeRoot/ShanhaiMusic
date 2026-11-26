# 插件中心 · build 67

2026-11-26：Swift 行为回归 261 项通过。iPhone 18 Pro 与 iPad Pro 13-inch (M5) 各三项 UI 回归通过，覆盖社区搜索、已安装切换、安装确认、失败恢复与安装后定位。社区目录只读取山海维护 Stars 列表，搜索限于该列表，安装确认使用隔离演示插件，不代表第三方插件登录或播放已验证。

截图索引见 [screenshots.json](screenshots.json)。安装后高亮为演示插件，没有真实账号数据。完整结果位于本工作树 `ios/.build/review/PluginCenterCurated.xcresult`；源码和实际打包版本以本提交为准。

| 设备 | 社区搜索 | 安装确认 | 安装后定位 |
| --- | --- | --- | --- |
| iPhone | [截图](iphone-community.png) | [截图](iphone-review.png) | [截图](iphone-installed.png) |
| iPad | [截图](ipad-community.png) | [截图](ipad-review.png) | [截图](ipad-installed.png) |

一键导入/覆盖入口已移除，保留 ZIP、目录和单个 GitHub 仓库安装。
