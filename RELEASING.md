# 山海打包与发布

## 桌面打包

1. 在 main 完成实现，运行 `npm run test:plugins`、`CGO_ENABLED=0 go test ./...` 与 `go tool mygo vet .`。
2. 更新 `mygo.config.ts` 的版本和 `CHANGELOG.md`，提交并推送 main。确认工作区干净，本地 HEAD 等于 origin/main。
3. macOS 本地执行 `bash scripts/build-desktop.sh -sign - -skip-notarize -skip-dmg`；用 `python3 scripts/artifacts.py latest desktop` 查询成功目录。应用位于该目录的 `output/darwin-arm64/山海.app`。交付前归档或放置 `.keep`，防止下一次成功构建滚动掉候选包。
4. 正常退出正在运行的应用，将新版 `山海.app` 覆盖安装到应用目录（如 `/Applications`），并核对 Dock 指向新版。启动后核对应用名、运行二进制、版本和签名，以及应用包不含内置平台适配器或信息源脚本。信息源插件单独发布、安装和验收，不打包用户账号数据。
5. 核对 `mb` 和 `mygo-music` 的系统协议关联，并用真实社区插件安装链接验证能唤起山海、下载插件并展示确认窗口。仅替换磁盘文件不会更新已运行的旧进程。

本地包默认使用临时签名，不走 Apple 公证，也不生成签名更新包。准备正式产物时，从 `main` 运行 GitHub Actions 的 `Package Shanhai` 并开启 `stage_release`。工作流用仓库更新密钥生成安装 ZIP、签名更新包和清单，创建桌面更新源草稿 `mb-desktop-v<版本>`。核对目标提交、版本、签名和下载地址。Apple 开发者签名与公证由维护者单独配置。

## 四端正式发布

1. 从各自固定工作树准备已验证的 macOS、iOS、Android 和 Server 产物。仅升级部分平台时，可带上上一轮已验证且兼容的安装包，在说明中写清各端实际版本和升级顺序。
2. 在 `main` 创建 `mb-release-YYYY.MM.DD` 草稿，日期按北京时间，同日后续版本追加 `.2`、`.3`。标题为「山海 YYYY.MM.DD」，有序号时带上序号。标签指向本次对应的桌面源码提交，其他平台源码由清单里的标签和提交定位。
3. 上传四端安装包及各端生成的签名更新包、校验文件和源码摘要，保留原始文件名与字节。iOS 只公开未签名 IPA。核对上传前后的 SHA-256 及 Release 内四个下载链接。
4. 上传 `release-manifest.json`。顶层包含 `schema: 1`、合集标签 `tag`、按 `desktop`、`ios`、`android`、`server` 分组的 `platforms`。每端填写 `version`、源码标签 `sourceTag`、完整提交 `sourceCommit`、附件文件名 `asset`、字节数 `size` 和无前缀小写十六进制 `sha256`。iOS 的 `version` 包括构建号。[已发布清单](https://github.com/xTreeRoot/ShanhaiMusic/releases/download/mb-release-2026.11.25/release-manifest.json)
5. 验收后公开四端正式版并设为 GitHub Latest。已安装客户端仍依赖平台标签时，按它们的现有规则保留所需更新源及附件，平台更新源不参与 Latest。桌面清单中签名更新包的原下载地址必须可用。
6. 等待 `Deploy Shanhai Website` 成功，核对线上中英文下载页，以及四个下载链接均指向同一合集。历史包保留原标签和直链，必要时维护按日期与平台索引的归档。
