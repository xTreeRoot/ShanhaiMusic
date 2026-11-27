# 山海

使用 Go 与 [MyGo](https://github.com/egoist/mygo) 构建的原生桌面音乐播放器，基于 [egoist/mygo-music](https://github.com/egoist/mygo-music) 提交 `298c88a03407844ac1c4eb76e3590edfe4577121` 开发，遵循 AGPL-3.0 许可证。

iPhone / iPad 客户端位于 `main` 分支的 [ios/](https://github.com/xTreeRoot/ShanhaiMusic/tree/main/ios)，使用 SwiftUI；构建、个人签名安装和页面截图见该目录说明。

## 当前能力

- 哔哩哔哩 App 扫码登录、扫码状态更新、二维码过期刷新和取消登录。
- 游客首页推荐、视频搜索、视频音频播放；登录后可读取个人内容、发送评论和视频点赞。
- 个人中心、收藏夹、观看历史、关注列表和 UP 主投稿分页。
- 带每句开始与结束时间的字幕，转换为播放器毫秒时间轴并随播放滚动。
- 播放队列、进度跳转、随机和循环播放、本地音频、缓存与音频下载。
- 中文和英文界面、macOS 托盘与刘海播放器。

登录 Cookie 保存在软件数据目录的 `plugin-data/bilibili/storage.json` 中。退出登录只停用 Cookie，重启后也保持退出状态；再次登录时先验证已保存的 Cookie，有效则直接复用，失效才重新扫码。字幕能否获取取决于视频提供的字幕和账号权限；缺少字幕时会返回明确状态。

## 开发与验证

需要 Go 1.27.3 或更新版本、Node.js 22.6 或更新版本。桌面应用无需安装网站依赖；MyGo 会使用本机 Node.js 或 Bun 读取配置。

```bash
go tool mygo dev
CGO_ENABLED=0 go test ./...
go tool mygo vet .
npm run test:bilibili
```

macOS 本地打包：

```bash
go tool mygo build -sign - -skip-notarize
```

产物位于 `build/darwin-arm64/MB.app`。默认使用本地临时签名；正式公证发布需要维护者自行配置 Apple 签名和公证凭据。

## 平台策略

通用播放器只依赖 `internal/music.Source`。自带插件集中在 `plugins/builtin/<platform>`，每个平台有自己的清单、网络权限、会话和 API 实现，通过通用的 `internal/plugin.LoadBundled` 加载。当前仅内置哔哩哔哩；源码、测试和平台资料均独立于播放器页面。

新增平台时新增独立目录并声明其已实现能力，运行时文件通过 `plugins/builtin/assets.go` 嵌入。通用页面、播放器和登录弹窗不根据平台名称调用私有 API。为平台需求增加的视频、登录取消等逻辑必须扩展为可复用的播放器能力，用户安装的 MyGo 音源插件也可通过同一契约接入。能力契约见 [插件开发约定](plugins/README.md)，插件示例保留在 `examples/demo-plugin`。

正式包同时支持 `mb://install-plugin?repo=owner/repo` 和上游社区的 `mygo-music://install-plugin?repo=owner/repo` 安装链接；开发包使用各自的 `-dev` 协议。插件兼容版本按已采用的 MyGo Music `0.1.25` 宿主契约检查，与山海的产品版本独立。上游历史、更新流程和本地功能优先规则见 [UPSTREAM.md](UPSTREAM.md)。

哔哩哔哩能力通过平台策略适配已有 MyGo 能力入口。扫码登录使用官方 `qrcode/generate`、`qrcode/poll`，优先读取 `Set-Cookie`，兼容官方 `passport.biligame.com/crossDomain` 回调。字幕接口返回的秒数转换为毫秒后交给原生歌词组件。

## 来源与许可证

保留 MyGo Music 的 Go 模块路径、上游许可证和历史变更记录，便于核对来源与后续维护。`website/` 是保留的上游网站源码，不参与山海桌面打包；本次未发布网站。源项目的歌词舞台动画参考 [folia-major](https://github.com/chthollyphile/folia-major)。

源码仓库：[xTreeRoot/ShanhaiMusic](https://github.com/xTreeRoot/ShanhaiMusic)。许可证见 [LICENSE](LICENSE)。
