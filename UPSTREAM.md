# 跟随 MyGo Music 上游

山海的 GitHub 仓库 https://github.com/xTreeRoot/ShanhaiMusic 是 https://github.com/egoist/mygo-music 的 fork。当前已采用的上游基线为 `0.1.25`，提交 `298c88a03407844ac1c4eb76e3590edfe4577121`。

上游基线与山海历史保留共同祖先，后续修复按提交逐项审阅并进行三方合并，保留现有功能。

## 更新流程

始终在 `main` 操作，不创建分支或 worktree。确认没有其他聊天正在修改、提交或打包同一仓库，且工作区和暂存区干净后再合并。

```bash
git fetch --no-tags upstream main
git log --oneline HEAD..upstream/main
git diff HEAD...upstream/main
git merge --no-ff --no-commit upstream/main
```

`upstream` 应指向 `https://github.com/egoist/mygo-music.git`。新克隆的仓库需先执行 `git remote add upstream https://github.com/egoist/mygo-music.git`。

山海的现有功能与行为优先保留，包括内置平台、登录会话、视频和状态栏增强，以及山海自己的应用标识、数据目录、发布配置和协议。冲突逐项审阅，将上游修复接入保留的山海实现；无冲突的合并也要核对行为。不要用 `reset --hard upstream/main`、强制覆盖、整次 `ours` 合并或全局 `-X ours` 丢弃上游修复。首次接回已采用基线的历史不代表以后也可以忽略上游修改。

完整接入较新的宿主契约后，再更新 `internal/app/plugin_install.go` 中的 `pluginCompatibilityVersion` 和本文基线；只选择部分提交时，不自动抬高兼容版本。山海的产品版本独立维护，不直接参与社区插件的 `minAppVersion` 检查。

提交前运行 `npm run test:bilibili`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .`，构建桌面包并核对源代码快照。新 UI 回归按项目规则连续运行五次。提交使用中文信息，推送与发布依用户授权执行。

## 社区插件

山海沿用 MyGo Music 的插件清单、宿主 API、安装和更新流程。正式包同时处理：

```text
mb://install-plugin?repo=owner/repo
mygo-music://install-plugin?repo=owner/repo
```

开发包对应 `mb-dev://` 和 `mygo-music-dev://`，与正式包隔离。上游社区网页的现有安装按钮可唤起已注册协议的山海；安装仍需在山海中确认。

可在设置的插件页通过 GitHub 仓库、ZIP 或目录安装。当前兼容 MyGo Music `0.1.25` 的宿主契约；声明更高 `minAppVersion` 的插件仍会被拦截，第三方插件自己的服务和登录流程需分别验证。
