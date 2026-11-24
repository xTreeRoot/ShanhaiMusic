# 山海打包

1. 在 main 完成实现，运行 `npm run test:plugins`、`CGO_ENABLED=0 go test ./...` 与 `go tool mygo vet .`。
2. 更新 `mygo.config.ts` 的版本和 `CHANGELOG.md`，提交并推送 main。确认工作区干净，本地 HEAD 等于 origin/main。
3. macOS 本地执行 `bash scripts/build-desktop.sh -sign - -skip-notarize -skip-dmg`；用 `python3 scripts/artifacts.py latest desktop` 查询成功目录。应用位于该目录的 `output/darwin-arm64/山海.app`。交付前归档或放置 `.keep`，防止下一次成功构建滚动掉候选包。
4. 正常退出旧版后，将新版按原名 `山海.app` 安装到可写的应用目录（如 `/Applications`），不要更名为 `MB.app`。如果 Dock 固定的是旧的 `MB.app`，移除旧入口并固定新版；应用标识和数据目录保持不变。启动后核对应用名、运行进程使用的二进制、包版本、签名，以及应用包不包含平台原生适配器或嵌入的信息源脚本。信息源插件单独发布、安装和验收，不将用户账号数据打包进应用。
5. 核对 `mb` 和 `mygo-music` 的系统协议关联，并用真实社区插件安装链接验证能唤起山海、下载插件并展示确认窗口。仅替换磁盘文件不会更新已运行的旧进程。

本地包默认使用临时签名，不走 Apple 公证，也不生成签名更新包。正式发布时从 `main` 运行 GitHub Actions 的 `Package Shanhai` 并开启 `stage_release`：工作流用仓库更新密钥生成安装 ZIP、签名更新包和清单，创建桌面 Release 草稿。核对草稿目标提交、版本和产物后再正式发布，不设为全仓库的最新发布。Apple 开发者签名与公证需由维护者单独配置。
