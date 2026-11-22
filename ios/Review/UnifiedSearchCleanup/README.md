# 移除磁力搜索验收

Android 0.1.36（versionCode 37）、iOS 0.1.0（Build 38）。

删除移动端磁力搜索选项、直接链接识别器和 `searchResources` 专用调用；移除新插件数据中的磁力链接解析以及结果的 BT 标签。通用资源详情按实际来源显示名称，使用通用链接操作文案。保留已有资料库数据、平台搜索、全平台搜索、搜索历史单条删除及全部清除。

桌面生产源码经过排查，没有磁力搜索专用入口、识别或调用；无需增加空迁移或修改平台适配器。独立插件仓库与用户已保存资源不作删除。

## 截图

- [iPhone 平台下拉](screenshots/iphone-search-platforms.png)
- [iPad 平台下拉](screenshots/ipad-search-platforms.png)
- [Android 平台下拉](screenshots/android-search-platforms.png)

已目视检查下拉仅显示全平台及现有来源，不遮住搜索输入框；截图为测试插件。Build 38 同时包含 [Build 37 发现页透明标签修复](../DiscoveryTabs/README.md)。

## 验证

- Swift 205 项行为回归通过；模拟器测试构建及真机 Release 构建通过。
- iPhone 搜索历史清理、跨平台搜索与资源保存两个 UI 用例各连续五轮，共十项通过；iPad 跨平台搜索与资源保存连续五轮通过。
- Android JVM 160 项通过，Release / Debug / AndroidTest 构建通过；lint 0 错误、38 警告。搜索历史清理与跨平台搜索保存两项设备回归连续五轮，共十项通过。
- `npm run test:plugins`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .` 均通过。桌面生产代码未更改。
- 本机 JBR 17 的 AArch64 C1 OSR 编译器在 lint 阶段崩溃；构建命令使用 `-XX:-UseOnStackReplacement` 后完成，未更改产品运行参数。

## 真机覆盖

iPhone 无线覆盖 Build 38 后，129 个私有文件规范化摘要与安装前一致，启动成功。小米无线使用原证书覆盖 0.1.36，包名与签名匹配，首次安装时间保持一致，启动命令成功。未卸载或清理应用数据；旧备份仍独立保留。

`source-sha256.json` 记录发布构建对应的生产源码摘要，后续尚未完成的栏目定制代码不包含在此版本中。
