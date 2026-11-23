# 山海更新记录

## 0.1.26 - 2026-11-23（桌面）

- macOS 安装包统一使用 `山海.app`。若旧版安装为 `MB.app`，请退出旧版后用 `山海.app` 替换，并更新 Dock 中固定的旧入口。应用标识和本地数据目录不变。

## 桌面 0.1.25 - 2026-11-23

- macOS 状态栏的上一首、播放/暂停、下一首改为独立原生按钮；左键直接控制播放，不再因点击位置识别失败而弹出山海菜单。
- 桌面插件管理对齐 iOS：列表保留版本、启用状态和显示设置；登录、启用开关与权限放入详情，编辑模式支持拖动排序和本地重命名。

## 桌面 0.1.24 / Android 0.1.47 / iOS Build 53 - 2026-11-23

- 修复 macOS 状态栏上一首、播放/暂停、下一首误弹菜单，按状态栏按钮内的真实点击位置执行对应操作。
- 修复桌面收藏夹与歌单触底不加载下一页；三端播放队列在接近页尾时预取，播完自动续到下一页。
- 三端搜索标题不再显示 HTML 高亮标签；哔哩哔哩收藏夹分页需要同时升级独立插件至 1.1.5。

## 桌面 0.1.23 / Android 0.1.46 / iOS Build 52 - 2026-11-23

- 三端插件「一键导入/覆盖」可从普通网页提取插件链接，支持 GitHub 仓库、发行页和山海安装链接，不再要求 GitHub 星标列表；仍须确认后才安装或覆盖。
- 桌面插件设置新增编辑模式，可重命名和调整排序，变动同步到来源标签。
- macOS 状态栏新增上一首、播放/暂停、下一首三个按钮。

## 桌面 0.1.22 - 2026-11-23

- 修复发现页卡片滚到播放栏下方时底栏完全透明、卡片与播放控件重叠的问题，恢复磨砂背景。

## 桌面 0.1.21 - 2026-11-23

- 桌面新增自定义皮肤编辑器：预览图片，拖动定位，调整缩放、主题色、透明度和模糊度，并可应用到音乐播放器。
- 确认保存后才应用新皮肤；取消或导入失败保留原设置，已有背景可直接重新编辑。

## 桌面 0.1.20 / Android 0.1.45 / iOS Build 51 - 2026-11-23

- 三端插件宿主优先使用同一份通用发行 ZIP，保留原有插件 ID、账号数据与设置。
- 插件可在同一份清单中声明各端最低宿主版本；桌面宿主兼容基线仍为 `0.1.25`。

## 0.1.19 - 2026-11-23

- 桌面发现页栏目标题与卡片左对齐。每栏固定五列、最多两行十张卡片，取消横向滚动，保留「查看全部」。
- 桌面插件宿主支持包内 JavaScript 和 TypeScript 模块的相对路径静态导入及再导出，不改变插件最低宿主版本检查。

## 0.1.18 - 2026-11-23

- 桌面版接入独立的签名更新源。可在设置中手动检查，也可开启每日检查和后台下载，更新在下次启动时生效。

## 移动端 Android 0.1.36 / iOS Build 38 - 2026-11-22

- 移除磁力搜索下拉入口、直接链接识别器、专用插件查询调用和 BT 标签；保留平台搜索、全平台搜索及搜索记录清理。
- 资源详情使用通用来源和链接文案，不再固定显示索引站名称；已有资料库记录与插件偏好不做删除迁移。
- iOS 包含 Build 37 的透明平台与分类标签修复，去掉独立玻璃底色和形状，保留跟手下划线与切换交互。
- 桌面完成源码排查，无对应的磁力搜索专用入口或处理链路。

## 移动端 Android 0.1.32 / iOS Build 33 - 2026-11-22

- 竖屏视频采用叠层布局：作者与标题位于左下，点赞、评论实际计数与分享位于右侧；点击画面暂停，上下滑切换队列。
- 竖屏更多、字幕、画质与评论集中到底部面板；评论打开时缩小上方视频，作者可通过来源能力进入主页。
- iOS 原生 OAuth 改为跳转外部系统浏览器，授权后通过已注册 URL 回到山海；保留 PKCE、state、取消与重复回调保护。
- 修复安卓 MBTextButton 实际使用填充按钮的问题；常用文字操作统一透明文字按钮，保留足够触控范围。
- 插件可返回目录分类 Tab，两端使用单行横向滚动并原地切换；刷新和分页保持当前分类，忽略已替换请求。
- YouTube 独立插件 0.2.3 修复首页未携带登录账号、空推荐误用热门或搜索替代、现代卡片遗漏及 Shorts 续页；移除本机观看记录和平台播放列表入口，保留已有收藏引用。

## 移动端 Android 0.1.31 / iOS Build 32 - 2026-11-22

- 下拉刷新重新请求首页与独立导航栏目，等待结果并更新页面；失败保留已有内容，按栏目提示错误。
- 修复安卓忙碌期间刷新被丢弃、旧请求覆盖新页面，以及工具栏刷新仍读取旧缓存的问题。
- 视频播放器提供插件真实画质选项，视频画质独立于音乐音质，切换保留播放进度和播放意图。
- 画质偏好保存到本地，后续视频及重启继续沿用；单条资源缺少请求画质时显示实际回退值，不覆盖偏好。YouTube 0.2.2 去掉 720p 上限，默认选择最高可用 H.264 流。

## 移动端 Android 0.1.30 / iOS Build 31 - 2026-11-21

- 重构移动端背景皮肤：图片缩放、拖动位置、八种主题色、透明度与模糊度在真实发现页实时预览，确认后统一保存；取消保留原设置。
- 新增“应用于音乐播放器背景”开关，可在预览和设置中调整；关闭后播放器使用原默认背景。
- 导航栏、搜索、资料库、我的、插件和设置共享窗口背景，去掉遮挡图片的整块黑白底色；分组行保留轻微透明层级，适配深色和浅色。
- 主题色同步平台下划线、选中曲目、歌词、播放器进度与操作；修复搜索内容背景越界覆盖搜索框，以及安卓快速调整多个皮肤参数时相互覆盖。
- 保留上一版 iOS 视频返回和展开浮窗的卡死修复，用户已确认恢复正常。

## 移动端 Android 0.1.29 / iOS Build 30 - 2026-11-21

- 启动与返回前台时静默检查新版，仅有更新时弹出当前版本、最新版本、更新内容和更新入口；“不再打扰”永久关闭主动检查，设置里仍可手动检查和重新开启。
- 背景图片支持文件、系统照片和网络地址，统一先裁剪，再在真实发现页预览并调整透明度，确认后才应用；取消或失败保留原背景和透明度。
- 安卓平台 Tab 下划线跟随文字宽度，底部导航去掉点击灰色反馈；iOS 横屏播放器待播清单和评论行透出播放器背景。
- 音乐播放器左右滑动切歌；点选歌词后需再点右侧播放确认，避免误跳。
- 插件登录入口支持 QQ / 微信等插件声明的方式，修复安卓生成二维码的同步脚本预算；网络权限默认折叠。
- QQ 音乐 0.3.9 修复长 QRC 歌词解码时的脚本中断、封面和重复精选入口；波点 0.3.5 统一显示为“波点音乐”，保留账号与本地名称。
- 音乐播放器作者可进入来源主页，元信息右侧显示平台提供的点赞与评论总数；更多面板集中收藏、下载和全局音质选择。
- 视频详情支持横滑切页，App 内与系统画中画分别设置；修复安卓点选分页后回跳。
- 三端插件名称、排序和启用偏好使用双份本地缓存；节点开启时主动拉取并按字段合并，避免空本地状态覆盖远端。
- 山海服务入口和节点表单统一分组布局，补充地址校验、清空和密码显隐。
- 修复 iOS 视频顶部安全区黑边；画中画私有控制器不参与界面观察，避免返回和浮窗交接期间反复创建、销毁控制器而触发系统无响应终止。
- 应用与网站同步采用用户选定的白发龙角角色图标，保留原应用标识和数据目录。

## 0.1.14 - 2026-11-21

- GitHub 仓库更名为 `xTreeRoot/ShanhaiMusic`；更新检查使用新地址，并兼容原 MB 发布链接。

- 桌面、iOS / iPadOS 与 Android 统一更名为「山海」，标语为「万端归一」。
- 同步界面文案、项目介绍、网站元数据与打包说明。
- 保留应用标识、登录回调、历史发布链接及已有插件和资料库；桌面继续使用 `MB` / `MB Dev` 数据与缓存目录。

- App 不再内置远程信息源，哔哩哔哩改用独立插件，并保留旧会话迁移能力。
- 三端源码统一在 main 维护；移动端 0.1.28 / Build 29 增加通用竖屏操作、后台视频预载、磁力链直接识别及搜索记录清理。
- 移动端点击资源主动打开对应播放器，iOS 启动读取完成前不会覆盖原设置。
- 同步用户选定的草帽熊猫图标，使用统一母版生成桌面、iOS、Android 与网站资源。

## 0.1.13 - 2026-11-19

- 桌面插件按音源分标签，支持独立背景皮肤；列表标注需要会员、没有可用音源等已知不可播原因。
- 插件支持检查 GitHub 更新、一键更新、删除，以及从 GitHub Stars 列表批量导入或覆盖。批量导入会排除 MB 和插件模板，GitHub 下载支持系统代理。
- 支持的音频容器可分段跳读；保留顺序回退，遇到可恢复的媒体失败时尝试从原位置继续。
- 自托管节点可加密同步已验证账号的公开资料，不通过平台资料接口上传 Cookie。需要节点支持 `/v1/data`；线上真实账号同步尚未验收。

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
- 启动时检查并补注册 MB 和社区安装协议，安装链接主动显示播放器和插件确认窗口。

## 0.1.6 - 2026-11-18

- 正式包接收 MB 和 MyGo Music 社区的插件安装链接，开发包使用独立的两种开发协议。
- 社区插件最低版本按已采用的 MyGo Music 0.1.25 宿主契约检查，与 MB 产品版本分开；更高要求仍会拦截。
- 接回上游 Git 历史并迁移到正式 GitHub fork，后续合并更新时优先保留 MB 功能。
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
- 对齐内置插件最低应用版本、MB 链接协议与打包身份，增加平台与 MyGo 回归测试。

以下为复制自 MyGo Music 的上游变更记录，版本号不代表 MB 版本。

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
