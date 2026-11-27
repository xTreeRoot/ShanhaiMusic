# 山海打包

1. 在 main 完成实现，运行 `npm run test:bilibili`、`CGO_ENABLED=0 go test ./...` 与 `go tool mygo vet .`。
2. 更新 `mygo.config.ts` 的版本和 `CHANGELOG.md`，提交并推送 main。确认工作区干净，本地 HEAD 等于 origin/main。
3. macOS 本地执行 `go tool mygo build -sign - -skip-notarize -skip-dmg`，产物在 `build/darwin-arm64/MB.app`。
4. 正常退出旧山海进程后再替换应用目录并启动新版。核对运行进程使用的二进制、包版本、签名，以及数据目录中安装的内置插件与 `plugins/builtin` 源码一致。
5. 核对 `mb` 和 `mygo-music` 的系统协议关联，并用真实社区插件安装链接验证能唤起山海、下载插件并展示确认窗口。仅替换磁盘文件不会更新已运行的旧进程。

默认使用本地临时签名，没有配置开发者证书、公证或自动更新。正式分发前由维护者提供自己的签名配置，不复用上游作者身份。GitHub 手动打包工作流只从 main 读取源码并上传工作流产物，不自动发布。
