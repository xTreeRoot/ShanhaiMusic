# 山海 · 万端归一

**山海（Shanhai）** 希望把分散的平台连接成一个入口，让听音乐、看视频、读资讯与交流拥有一致的体验。当前以音乐、视频和本地资料库为主，通过插件扩展平台；社交与聊天平台是后续方向。

提供桌面、iOS / iPadOS 和 Android 原生客户端。桌面使用 Go + [MyGo](https://github.com/egoist/mygo)，iOS 使用 SwiftUI，Android 使用 Kotlin + Jetpack Compose。

[山海官网](https://shanhai.treeroot.top/) · [下载最新版本](https://github.com/xTreeRoot/ShanhaiMusic/releases/latest) · [山海插件库](https://shanhai.treeroot.top/zh/plugins/) · [插件开发](plugins/README.md) · [打包与发布](RELEASING.md)

## 源码分支

`main` 维护桌面客户端和共享官网。移动端与自托管服务端分别在独立分支和工作树开发、验证和发布。

| 平台 | 源码与构建说明 | 技术 |
| --- | --- | --- |
| 桌面 | `main` 分支、[打包说明](RELEASING.md) | Go + MyGo |
| iPhone / iPad | `ios` 分支、[构建说明](https://github.com/xTreeRoot/ShanhaiMusic/blob/ios/ios/README.md) | SwiftUI，iOS / iPadOS 17+ |
| Android | `android` 分支、[构建说明](https://github.com/xTreeRoot/ShanhaiMusic/blob/android/android/README.md) | Kotlin + Jetpack Compose，Android 8.0+ |
| 自托管节点 | `server` 分支、[构建说明](https://github.com/xTreeRoot/ShanhaiMusic/blob/server/server/README.md) | Go + SQLite |

各端功能与真机验证进度以对应目录的说明和验收记录为准。



## 从 MB 升级

本次统一更名为「山海」，标语为「万端归一」。应用标识、签名兼容关系、插件 ID、登录回调和已有数据目录继续沿用，覆盖安装后保留账号、插件、资料库与设置。

- 桌面继续使用 `MB` / `MB Dev` 数据和缓存目录，应用包名称为 `山海.app`。
- Android 包名继续为 `com.xtreeroot.mb`；iOS Bundle ID 保持现有工程配置。
- `mb://install-plugin?repo=owner/repo` 与 `mygo-music://install-plugin?repo=owner/repo` 等已有安装协议继续有效。
- GitHub 仓库已更名为 [xTreeRoot/ShanhaiMusic](https://github.com/xTreeRoot/ShanhaiMusic)。原 `xTreeRoot/MB` 地址由 GitHub 重定向，现有发布标签和安装包文件名继续沿用。

请保留旧版数据，使用相同应用标识及兼容签名覆盖更新。

## 插件与平台策略

主窗口侧边栏的「插件」提供已安装列表和社区浏览、搜索、分页安装。保留本地名称、排序、栏目设置、Stars 导入和节点插件目录；安装仍须确认权限，成功后自动定位插件。清单可用 `icon` 指定包内图标，未指定时使用仓库作者头像或默认图标。

通用桌面播放器依赖 `internal/music.Source`。平台私有接口、签名、分页、媒体地址及字幕转换集中在独立插件中，宿主负责通用播放、存储、网络权限和交互。三端不编译或嵌入任何远程信息源；信息源在独立仓库维护，通过外部插件清单声明能力和权限，并由用户安装。哔哩哔哩插件为 [xTreeRoot/mygo-music-bilibili](https://github.com/xTreeRoot/mygo-music-bilibili)。

各端兼容的 MyGo Music 插件宿主版本独立于山海产品版本维护。契约见 [插件开发约定](plugins/README.md)，更新上游的规则见 [UPSTREAM.md](UPSTREAM.md)。

登录状态使用已有插件数据目录保存。退出登录停用会话；重新登录时先验证可复用的数据。账号、Cookie 与签名密钥不进入 Git 仓库。

macOS 开启「状态栏歌词」时，歌词和播放控制显示在同一个菜单栏项目。红心只对当前歌曲的来源插件点赞，不修改本地收藏。设置里的「状态栏视频」可开关歌词左侧的静音视频预览。右键可打开主窗口或设置；关闭歌词后恢复普通菜单栏图标。

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
npm run build -- -sign - -skip-notarize
```

运行 `python3 scripts/artifacts.py latest desktop` 定位本次成功目录，应用在其中的 `output/darwin-arm64/山海.app`。默认使用本地临时签名；正式公证需配置维护者自己的 Apple 签名与公证凭据。保留待验收或回退版本时，先在该目录放置 `.keep`，或另行归档。

`python3 scripts/artifacts.py` 默认预览磁盘占用与受管旧产物，`--apply` 才执行。三端构建入口复用缓存、清理自有临时文件；项目外缓存、已有交付归档与未解决的失败证据不删。

移动端构建、安装、行为测试与截图说明见对应分支的构建说明。

### 桌面更新

- 旧版没有内置更新源，需要先从 [GitHub Latest](https://github.com/xTreeRoot/ShanhaiMusic/releases/latest) 手动覆盖安装一次支持更新的正式包。安装到当前用户有写入权限的应用目录；应用标识和数据目录保持不变。
- 设置 → 更新支持手动检查、每日自动检查和自动下载并安装。开启自动安装后不强制重启，更新在下次打开山海时生效。
- 从 `main` 运行 `Package Shanhai` 并开启 `stage_release`，可以准备 macOS 安装 ZIP、签名更新包和桌面更新源。正式发布将这批产物和 iOS、Android、Server 安装包收进同一条 GitHub Release。桌面更新源的兼容要求见 [打包与发布](RELEASING.md)。
- 仓库只存更新公钥；签名私钥需安全备份，不能写进源码或日志。正式包目前覆盖 macOS Apple 芯片，其他桌面架构需要单独发布对应的更新产物。

## 自托管节点

支持的客户端可在设置中接入自托管节点，应用没有默认服务器。节点管理员能够解密同步会话，因此只接入信任的节点。协议见 [server 分支](https://github.com/xTreeRoot/ShanhaiMusic/blob/server/server/EDGE.md)；账号资料与登录会话使用不同同步路径，真实跨端同步的验证状态见各端文档。

三端在「山海服务」里分别查看云端偏好、账号资料和登录会话。只同步账号资料不会让另一台设备登录。要同步登录，先在各端连接同一节点、安装与节点签名目录一致且支持登录同步的插件，再在任一端登录；已有登录可点「同步登录到云端」。另一端没有有效本地登录时会尝试向平台验证并恢复，也可以手动触发。平台拒绝或要求安全验证时，仍需在本机登录；本机主动退出不会被云端自动撤销。

## 来源与许可证

山海基于 [egoist/mygo-music](https://github.com/egoist/mygo-music) 开发，保留上游 Go 模块路径、许可证和历史变更记录，遵循 [AGPL-3.0](LICENSE)。源项目的歌词舞台动画参考 [folia-major](https://github.com/chthollyphile/folia-major)。

`website/` 保存网站源码，网站品牌信息已同步为山海；网站部署与客户端打包分别进行。
