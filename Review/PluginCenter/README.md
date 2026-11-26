# 桌面插件中心 · 0.1.38

2026-11-26：接入上游 MyGo Music 0.1.26，社区固定使用山海维护 Stars 列表，移除一键导入/覆盖，编辑位于全部更新之后，保留山海紧凑列表、详情、名称排序、栏目设置、批量更新、节点目录。

`npm run test:plugins`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .` 通过；社区安装、权限确认、取消、搜索替换、失败重试、分页及安装后定位的 UI 回归连续运行 5 次通过。已构建 macOS arm64 应用。截图来自 `ui.NewTester` 的隔离来源，不含账号，不代表第三方平台登录和播放已验收。

- [社区目录](community.png)
- [已安装与定位高亮](installed.png)
- [独立详情与权限](details.png)

原始测试日志和帧保存在本工作树 `.build/plugin-center/`。
