# 内置哔哩哔哩插件

这是可独立加载的标准 MyGo 音源插件。清单、网络权限、平台请求、WBI 签名、登录会话、媒体选流、字幕时间转换和翻译都在本目录；没有独立服务器或数据库。

`index.js` 实现平台业务，播放器通过标准导出函数调用。`video`、`cancelSignIn` 使用山海的通用可选宿主能力，边界说明见 [插件开发约定](../../README.md)。平台接口以已验证的 bilibil-bot 和 SocialCrawlerHub 实现为依据，修改请求前先对照对应实现，不复制账号凭据。

会话由 `host.storage` 写入软件数据目录的 `plugin-data/bilibili/storage.json`。退出登录保留但停用会话，再登录时先验证再复用。源码目录移动不改变插件 ID 和存储目录。

在仓库根目录运行 `npm run test:bilibili` 验证平台请求、登录取消、分页和字幕秒到毫秒转换；运行 `CGO_ENABLED=0 go test ./plugins/builtin/bilibili` 验证真实 Go/JavaScript 宿主边界，包括 Cookie、封面、音视频和登录取消。测试使用模拟响应；真实扫码、账号权限和平台播放需另行验证。

首页内容组织及音乐榜、新鲜投稿接口参考 [GentleM4dman/mygo-music-bilibili](https://github.com/GentleM4dman/mygo-music-bilibili)。音乐区使用新版分区 ID 1003，周榜从 `all_period` 中按数值 ID 选择最新期次；新鲜投稿通过 `finger/spi` 获取访客标识，与已停用的登录会话分开保存。首页各区并行请求，单区失败不影响其他内容，更多页对视频 ID 去重。
