# 山海 · 万端归一

**山海（Shanhai）** 希望把分散的平台连接成一个入口，让听音乐、看视频、读资讯与交流拥有一致的体验。当前以音乐、视频和本地资料库为主，通过插件扩展平台；社交与聊天平台是后续方向。

提供桌面、iOS / iPadOS 和 Android 原生客户端。桌面使用 Go + [MyGo](https://github.com/egoist/mygo)，iOS 使用 SwiftUI，Android 使用 Kotlin + Jetpack Compose。

[山海官网](https://shanhai.treeroot.top/) · [下载发布版本](https://github.com/xTreeRoot/ShanhaiMusic/releases) · [山海插件库](https://shanhai.treeroot.top/zh/plugins/) · [插件开发](plugins/README.md) · [桌面打包](RELEASING.md)

## 三端源码

三端统一在 `main` 维护，不再创建独立的平台开发分支。

| 平台 | 源码与构建说明 | 技术 |
| --- | --- | --- |
| 桌面 | 仓库根目录、[打包说明](RELEASING.md) | Go + MyGo |
| iPhone / iPad | [ios/](ios/README.md) | SwiftUI，iOS / iPadOS 17+ |
| Android | [android/](android/README.md) | Kotlin + Jetpack Compose，Android 8.0+ |

各端功能与真机验证进度以对应目录的说明和验收记录为准。



## 从 MB 升级

本次统一更名为「山海」，标语为「万端归一」。应用标识、签名兼容关系、插件 ID、登录回调和已有数据目录继续沿用，覆盖安装后保留账号、插件、资料库与设置。

- 桌面继续使用 `MB` / `MB Dev` 数据和缓存目录，应用包名称为 `山海.app`。
- Android 包名继续为 `com.xtreeroot.mb`；iOS Bundle ID 保持现有工程配置。
- `mb://install-plugin?repo=owner/repo` 与 `mygo-music://install-plugin?repo=owner/repo` 等已有安装协议继续有效。
- GitHub 仓库已更名为 [xTreeRoot/ShanhaiMusic](https://github.com/xTreeRoot/ShanhaiMusic)。原 `xTreeRoot/MB` 地址由 GitHub 重定向，现有发布标签和安装包文件名继续沿用。

请保留旧版数据，使用相同应用标识及兼容签名覆盖更新。

## 插件与平台策略

通用桌面播放器依赖 `internal/music.Source`。平台私有接口、签名、分页、媒体地址及字幕转换集中在独立插件中，宿主负责通用播放、存储、网络权限和交互。三端不编译或嵌入任何远程信息源；信息源在独立仓库维护，通过外部插件清单声明能力和权限，并由用户安装。哔哩哔哩插件为 [xTreeRoot/mygo-music-bilibili](https://github.com/xTreeRoot/mygo-music-bilibili)。

各端兼容的 MyGo Music 插件宿主版本独立于山海产品版本维护。契约见 [插件开发约定](plugins/README.md)，更新上游的规则见 [UPSTREAM.md](UPSTREAM.md)。

登录状态使用已有插件数据目录保存。退出登录停用会话；重新登录时先验证可复用的数据。账号、Cookie 与签名密钥不进入 Git 仓库。

## 桌面开发与验证

需要 Go 1.27.3 或更新版本、Node.js 22.6 或更新版本。桌面应用无需安装网站依赖。

```sh
go tool mygo dev
npm run test:plugins
CGO_ENABLED=0 go test ./...
go tool mygo vet .
```

macOS 本地打包：

```sh
go tool mygo build -sign - -skip-notarize
```

产物位于 `build/darwin-arm64/山海.app`。默认使用本地临时签名；正式公证需配置维护者自己的 Apple 签名与公证凭据。

移动端构建、安装、行为测试与截图说明分别见 [iOS README](ios/README.md) 和 [Android README](android/README.md)。

## 自托管节点

支持的客户端可在设置中接入自托管节点，应用没有默认服务器。节点管理员能够解密同步会话，因此只接入信任的节点。已有协议文档位于历史 [server 分支](https://github.com/xTreeRoot/ShanhaiMusic/blob/server/server/EDGE.md)；账号资料与登录会话使用不同同步路径，真实跨端同步的验证状态见各端文档。

## 来源与许可证

山海基于 [egoist/mygo-music](https://github.com/egoist/mygo-music) 开发，保留上游 Go 模块路径、许可证和历史变更记录，遵循 [AGPL-3.0](LICENSE)。源项目的歌词舞台动画参考 [folia-major](https://github.com/chthollyphile/folia-major)。

`website/` 保存网站源码，网站品牌信息已同步为山海；网站部署与客户端打包分别进行。
