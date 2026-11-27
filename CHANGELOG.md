# 山海更新记录

## 0.1.12 - 2026-11-18

- 听歌 / 视频模式只改变后续选中内容的播放方式，切到视频模式不再立即打开或播放当前内容。
- 首页卡片、列表、歌单和队列的主动选中统一按模式播放；切回听歌关闭视频画面，保留当前音频、状态和进度。
- 关闭视频窗口保留模式选择，会话恢复、暂停继续、音源重试和自动切歌不会额外弹出视频窗口。
- 补充真实点击、反复切换、迟到请求和恢复播放的离屏回归。

## 0.1.11 - 2026-11-18

- macOS 视频模式打开独立大播放窗口，主窗口继续浏览，右侧提供简介和评论；支持置顶、全屏和回到主窗口。
- 修复远程视频轨道元数据在首帧绘制时同步加载造成的界面卡死，后台获取帧率，不人为延迟播放。
- 初次加载和播放缓冲显示明确状态，网络等待期间保持界面可操作，关闭窗口会取消视频下载且保留音频播放。
- 补充慢网绘制、输入、关闭及窗口生命周期回归；普通测试默认离屏，原生窗口测试仅显式开启。

## 0.1.10 - 2026-11-18

- 主窗口右上角增加听歌模式 / 视频模式切换，记住选择，切换时保留当前歌曲和播放进度。
- 视频模式在主内容区播放平台提供的视频，复用底部播放、暂停、音量和进度控制，画面与音频同步。
- 视频模式下可返回首页或收藏夹继续浏览，新选曲目自动打开视频；不支持视频的音源显示明确状态。
- 主视频与展开播放器共享原生解码器，切换到听歌模式后释放视频请求，忽略已过期的加载结果。

## 0.1.9 - 2026-11-18

- 哔哩哔哩首页增加为你推荐、B站音乐榜、音乐区热门和新鲜投稿，支持查看更多与分区加载容错。
- 播放器增加通用横版视频卡片，直接点击播放所在分区的队列，显示时长与 UP 主。
- 首页支持主动刷新，刷新失败保留当前内容，避免空白页面。
- 设置新增系统代理域名白名单，默认仅包含 YouTube Music 相关服务；宿主中其他域名保持直连，修复网页登录后账号校验请求失败。

## 0.1.8 - 2026-11-18

- 重启后恢复队列、进度和歌词，等待用户播放时再获取音源，避免暂停期间网络流失效。
- 解码或下载中途失败时保留当前歌曲与进度并反馈错误，再次播放可重新加载音源和歌词。
- 状态栏视频支持同一歌曲重新加载后的刷新，修复启动加载失败后必须换歌才能重试的问题。
- 补齐恢复后播放、歌词重新加载、断流重试和迟到请求隔离的回归测试。

## 0.1.7 - 2026-11-18

- 将内置哔哩哔哩插件的源码、清单、翻译和平台测试移到 `plugins/builtin/bilibili`，与播放器页面分离。
- 内置插件通过通用插件宿主发现和加载，保持既有账号数据目录，并允许用户安装的同 ID 插件优先。
- 明确视频、登录取消和时间轴字幕等通用能力契约，其他内置和社区插件可复用。
- 启动时检查并补注册山海和社区安装协议，安装链接主动显示播放器和插件确认窗口。

## 0.1.6 - 2026-11-18

- 正式包接收山海和 MyGo Music 社区的插件安装链接，开发包使用独立的两种开发协议。
- 社区插件最低版本按已采用的 MyGo Music 0.1.25 宿主契约检查，与山海产品版本分开；更高要求仍会拦截。
- 接回上游 Git 历史并迁移到正式 GitHub fork，后续合并更新时优先保留山海功能。
- 保留 0.1.5 的固定宽度状态栏歌词、宽度设置和左侧静音视频预览。

## 0.1.5 - 2026-11-18

- macOS 状态栏歌词按固定实际宽度绘制，切换短句、长句和中英混排时不再改变占位。
- 设置中可调整歌词宽度，默认 240pt，范围 80–600pt；长句按像素平滑滚动，暂停和跳转跟随音频时间。
- 歌词最左侧增加静音视频预览，随歌曲切换、暂停、继续和进度跳转；可在设置中单独关闭。
- 状态栏与主界面视频使用独立解码器，关闭预览即释放播放和下载资源。

## 0.1.4 - 2026-11-18

- 补齐 macOS 菜单栏歌词，长句在限定长度内循环滚动，悬停显示整句。
- 滚动跟随音频时间，支持暂停、进度跳转和切句；关闭主窗口后继续更新。
- 发包包含已合入的登录会话复用、退出停用和游客首页。

## 0.1.3 - 2026-11-18

- 使用 WBI 签名播放接口获取音视频，修复视频网页缺少播放数据时无法播放。
- 恢复歌曲和收藏夹封面，补齐收藏夹元数据与图片请求上下文。
- 侧栏歌单支持拖动排序，按平台、账号和分组记住顺序。
- 大封面支持封面 / 视频切换，切歌和重启沿用选择；视频跟随音频暂停、继续和跳转。

## 0.1.2 - 2026-11-18

- 对齐 Zhuoqi 的扫码会话读取方式，完整 Set-Cookie 会话直接用于账号校验，不再被回调跳转地址阻断。
- 增加桌面内嵌运行时回归，覆盖会话完整时的 HTTP 跳转、不同 passport 域名和缺省回调地址。

## 0.1.1 - 2026-11-18

- 初始化 Go + MyGo 原生桌面播放器与独立平台策略，目前内置哔哩哔哩。
- 增加扫码登录、扫码状态和过期刷新，修正官方 biligame 成功回调的域名判断。
- 优先从登录响应 Set-Cookie 读取原始会话，兼容回调参数；取消时停止后台轮询。
- 接入视频音频、带起止时间字幕、评论、点赞、个人中心和分页收藏夹、观看历史、关注与投稿。
- 对齐内置插件最低应用版本、山海链接协议与打包身份，增加平台与 MyGo 回归测试。

以下为复制自 MyGo Music 的上游变更记录，版本号不代表山海版本。

# Changelog

All notable changes to MyGo Music are written down here. Versions follow [Semantic Versioning](https://semver.org).

## [0.1.25] - 2026-11-14

### Added

- Settings > Now Playing Style lets you show a vinyl record or a cassette in Now Playing. The record and reels turn as the song plays, the tonearm lifts when paused, and both players can be tilted, dragged and flung like the cover.

## [0.1.24] - 2026-11-11

### Changed

- Updated MyGo to v0.9.0.

### Fixed

- Windows releases now build on a native Windows runner.

## [0.1.23] - 2026-11-11

### Added

- Plugins can ask for password fields, switches and select menus in their settings. Passwords are masked and saved explicitly; switches and selects save when changed. Existing plugins keep their text fields and string values.

### Fixed

- Scrolling over the volume control changes the volume by a consistent step per mouse-wheel event.
- Now Playing's cover stays clear of the tabs while a radio plays.
- Liked and Saved Albums use the titles the source gives them in its sidebar.

## [0.1.22] - 2026-11-07

### Fixed

- The Shuffle button in a list now lets any song come first, instead of always keeping the first one first. A song you pick to start still stays first.
- Seeking in AAC audio in an MP4, including a fragmented file as streaming sites serve it, lands on the same frame as playing straight through, not a second or so off after the first short packet.
- The end of a fragmented MP4 no longer plays the silence padding of its last packet.
- Now Playing's lyrics start at the top in a tall window, instead of leaving the top of the window empty and cutting off the lines before the one sung.
- Japanese lyrics are drawn in a Japanese font, kanji and kana alike, rather than their kanji in the font of the app's language.
- On English Windows, Chinese text is drawn in one Chinese font, not with characters in different fonts.
- A plugin that needs setting up says why, in its settings and in the player, instead of "Fill these in for it to work" whatever it said. The message goes away once the program is allowed or the setting is saved.
- A plugin's `id` and `main` must now stay inside the plugin's folder, so a plugin dropped into the plugins folder can't read another plugin's saved settings or run code from outside its folder.
- The notch's Shuffle and Repeat buttons take the accent colour.

## [0.1.21] - 2026-11-07

### Added

- Audio in MP4s cut into fragments, as streaming sites serve it, plays as it comes in, and so does FLAC in MP4. A plugin for such a site can give the app the stream's address as it is, without converting it first.

### Fixed

- With Grow From the Cover on, Now Playing no longer takes hundreds of megabytes more memory each time it opens out of the cover, as it did on Windows.

## [0.1.20] - 2026-11-07

### Added

- A plugin's picture URLs can say where the size goes with `{size}`, such as `?param={size}y{size}`, and the app fetches each cover the size it shows, so rows of covers take much less memory and come in faster.
- Settings > Experiments has Grow From the Cover, for Now Playing to open out of the player bar's cover as before.

### Changed

- Now Playing slides up from the bottom of the window as it opens, and back down as it closes.

### Fixed

- A plugin's page that changes as you play, such as songs played lately, shows what's new when you open it again a while later, without restarting the app.
- Big covers that come only large are decoded within a memory budget, and the app gives memory back once they're shown and when the window hides.
- A plugin that reads its storage or settings at its top level no longer hangs as it starts.
- The button that saves a playlist's new name reads 保存 in Chinese, not 收藏.

## [0.1.19] - 2026-11-07

### Added

- Plugins can list `requestHeaders` in plugin.json: headers, such as a session's cookie, that the app sends with the plugin's pictures and audio, so covers kept behind a sign-in, as fnOS Music's are, show.

### Fixed

- Headers a plugin gives for a stream no longer go on to another host the stream redirects to.

## [0.1.18] - 2026-11-07

### Added

- ALAC, AAC and WAV files play, from local folders and from plugins. WAV and ALAC show the Lossless badge.

### Fixed

- Big covers take much less memory to show as small thumbnails, and a cover shown at several sizes at once is fetched and decoded only once.
- Long audio, such as a ten-hour audiobook, no longer takes a lot of memory for its spectrum, and streams that don't say how long they are get levels all the way through.

## [0.1.17] - 2026-11-07

### Added

- Plugins can head the page of a feature card's songs, such as a daily mix, with their own title and subtitle, and the page is headed as the card is.
- Plugins can mark a playlist of yours as fixed, such as a service's liked songs. It stays under My Playlists but can't be renamed, deleted or changed.

### Fixed

- Playlists and Liked Songs changed in the service's own apps or website now show those changes, without restarting the app.

## [0.1.16] - 2026-11-07

### Fixed

- On macOS, the app no longer takes more and more memory while something on screen moves, such as the lyrics in Now Playing.

## [0.1.15] - 2026-11-07

### Added

- A podcast's host, on its page, opens the host's own page, for plugins that have one.

## [0.1.14] - 2026-11-07

### Added

- Plugins can give you a page of your own, such as your profile, which opens from My Page in the account menu.
- Plugin pages can be headed with a round picture and a description, as an artist's page is, and their sections can say when they're empty.

## [0.1.13] - 2026-11-07

### Fixed

- On Windows, programs a plugin starts no longer open a console window.

## [0.1.12] - 2026-11-07

### Fixed

- On Linux, signing in to a plugin that shows its service's sign-in page no longer closes the whole app.

## [0.1.11] - 2026-11-07

### Changed

- Checking plugins for updates uses your GitHub token, from `GITHUB_TOKEN`, `GH_TOKEN` or `gh auth token`, so frequent checks no longer run into GitHub's limit. Plugins from private repositories can be installed and updated with it too.
- Updating a plugin installs the version its check found, rather than asking GitHub again.

### Fixed

- On Windows and Linux, the + in Settings > Plugins no longer sits under the window's close button.

## [0.1.10] - 2026-11-06

### Fixed

- Chinese titles, artists, albums and lyrics in older MP3 tags, written as GBK, no longer show as garbled Latin letters, and ID3v1 tags with accents or Chinese are no longer left blank. Your library is read again once to pick this up.

## [0.1.9] - 2026-11-06

### Added

- Check a plugin for updates whenever you like, from its ⋯ menu in Settings > Plugins, instead of waiting for the daily check. Its row says whether it's up to date.
- A plugin's repository in Settings > Plugins is a link to it on GitHub.

### Changed

- The warning about a plugin starting programs is no longer shown in red.

## [0.1.8] - 2026-11-06

### Added

- A plugin can say the oldest version of MyGo Music it works with, as `minAppVersion` in `plugin.json`. An older app won't install it, and says to update first.
- Plugins can be translated: the app shows a plugin's name, settings and sidebar in your language from its `locales/zh-CN.json`, and plugins can translate their pages with `host.t` and `host.language`. Changing the language reloads the plugin's page in it.

## [0.1.7] - 2026-11-06

### Changed

- A plugin's `process` permission can list the programs it may start, such as `["bin/helper-*", "ffmpeg"]`, and it can't start any other, even once you've allowed it. Installing or allowing such a plugin names those programs. `true` still means any program.
- Updating a plugin asks again when the new version wants to start a program the old one didn't.

## [0.1.6] - 2026-11-06

### Fixed

- Signing in from Settings opens the sign-in dialog, and a plugin's sign-in page, over the Settings window instead of behind it, over the main window.
- Settings no longer offers Sign In for a plugin you haven't switched to yet that's already signed in.

## [0.1.5] - 2026-11-06

### Added

- Install plugins from a `mygo-music://install-plugin?repo=owner/repo` link. It opens Settings > Plugins and asks before anything is installed.
- Settings > Plugins has a + menu to install from GitHub, a folder or a zip, or to show the plugins folder. Each plugin's ⋯ menu shows its folder or removes the plugin, which moves it to the Trash after asking.
- Songs with no cover, or whose cover failed to load, show a note cover in Now Playing.

### Changed

- Installing from GitHub takes any GitHub page address or `git@` address, not just the repository's root.
- A plugin's update error shows next to that plugin.
- Primary buttons and settings use the app's accent colour.

## [0.1.4] - 2026-11-06

### Added

- The next song is fetched ahead of time, so it starts right away when its turn comes.
- You can seek in a song that's still downloading. Seeking past what has arrived shows that the song is loading instead of going quiet.
- Plugins can sign in on a web page the app shows (`host.webSignIn`), for services whose website is the only way in.
- Install plugins from a folder or a zip on this computer.

### Fixed

- A sign-in that fails straight away no longer makes the dialog flash and jump.
- The daily card's section keeps a gap above the cards below it.

## [0.1.3] - 2026-11-06

### Fixed

- The daily card's grooves ripple more slowly, so the GPU stays mostly idle on Home while its songs play.

## [0.1.2] - 2026-11-06

### Added

- Now Playing grows out of the player bar's cover, and you can pull it down to close it. With reduced motion turned on, it fades in place.
- The search field has a clear button, and its recent searches popup matches the macOS search menu.

### Fixed

- The window no longer shakes while it's dragged.
- Clicks anywhere on the search field reach it, including the icon and the edges.
- Now Playing's bars fade as it closes instead of showing over the player bar.
- The wall's cover stays in place as it flies out of the player bar.
- The sidebar's dropdowns open and close at once.

### Changed

- The hairline between the sidebar and the page is gone.

## [0.1.1] - 2026-11-06

### Added

- A website with English and Chinese download pages.

## [0.1.0] - 2026-11-06

The first release: a native music player that plays your local files and gets everything else from plugins.

[0.1.25]: https://github.com/egoist/mygo-music/compare/v0.1.24...v0.1.25
[0.1.24]: https://github.com/egoist/mygo-music/compare/v0.1.23...v0.1.24
[0.1.23]: https://github.com/egoist/mygo-music/compare/v0.1.22...v0.1.23
[0.1.22]: https://github.com/egoist/mygo-music/compare/v0.1.21...v0.1.22
[0.1.21]: https://github.com/egoist/mygo-music/compare/v0.1.20...v0.1.21
[0.1.20]: https://github.com/egoist/mygo-music/compare/v0.1.19...v0.1.20
[0.1.19]: https://github.com/egoist/mygo-music/compare/v0.1.18...v0.1.19
[0.1.18]: https://github.com/egoist/mygo-music/compare/v0.1.17...v0.1.18
[0.1.17]: https://github.com/egoist/mygo-music/compare/v0.1.16...v0.1.17
[0.1.16]: https://github.com/egoist/mygo-music/compare/v0.1.15...v0.1.16
[0.1.15]: https://github.com/egoist/mygo-music/compare/v0.1.14...v0.1.15
[0.1.14]: https://github.com/egoist/mygo-music/compare/v0.1.13...v0.1.14
[0.1.13]: https://github.com/egoist/mygo-music/compare/v0.1.12...v0.1.13
[0.1.12]: https://github.com/egoist/mygo-music/compare/v0.1.11...v0.1.12
[0.1.11]: https://github.com/egoist/mygo-music/compare/v0.1.10...v0.1.11
[0.1.10]: https://github.com/egoist/mygo-music/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/egoist/mygo-music/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/egoist/mygo-music/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/egoist/mygo-music/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/egoist/mygo-music/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/egoist/mygo-music/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/egoist/mygo-music/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/egoist/mygo-music/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/egoist/mygo-music/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/egoist/mygo-music/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/egoist/mygo-music/releases/tag/v0.1.0
