# YouTube 推荐、竖屏播放与 iOS 外部登录验收

2026-11-22，Android 0.1.32 / iOS Build 33，YouTube 独立插件 0.2.3。

## 改动

目录 Tab 是通用可选合同，两端在一行中横滚并原地替换内容，刷新及分页继续使用所选分类，旧请求不得覆盖新选择。YouTube 推荐解析、账号授权请求、TV/现代网页卡片和 Shorts 分页均位于独立插件，App 没有新增平台请求。

竖屏播放保留视频比例，作者及标题左下、真实点赞/评论数和分享右侧。更多、字幕、画质和评论采用底部面板，文字操作无填充；评论打开时缩小同一视频舞台。iOS 上下滑切换队列；Android 复用播放器队列动作。画质使用已有全局持久偏好。Android MBTextButton 改为真实 Material TextButton，修复旧实现的填充底色；竖屏使用 Media3 支持的 TextureView 与 Compose 共同合成，避免已解码却被遮黑的 SurfaceView 画面。

iOS 原生 OAuth 通过外部默认浏览器打开，授权后由已注册 URL scheme 回到山海。宿主保持 PKCE/state、路径校验、单次回调、取消和迟到响应保护；畸形回调不终止有效等待，浏览器打不开或明确拒绝会结束本次等待。已有登录信息保留。

## 截图

| 场景 | iPhone | iPad | Android |
| --- | --- | --- | --- |
| 竖屏主画面 | [查看](iphone-portrait.png) | [查看](ipad-portrait.png) | [查看](android-portrait.png) |
| 评论面板及上方视频 | [查看](iphone-comments.png) | [查看](ipad-comments.png) | [查看](android-comments.png) |
| 无填充更多操作 | [查看](iphone-actions.png) | [查看](ipad-actions.png) | [查看](android-actions.png) |
| 字幕面板 | [查看](iphone-captions.png) | [查看](ipad-captions.png) | — |
| 分类 Tab 原地切换 | [查看](iphone-filter-tabs.png) | [查看](ipad-filter-tabs.png) | [查看](android-filter-tabs.png) |

截图均来自模拟器合成插件和本地自制 MP4，无私人账号。iPhone/iPad 采用连续第五轮；Android 最终五轮等待 ExoPlayer 首帧后操作并截图。蓝色为测试视频本身，非真实平台节目，已人工检查图层及按钮布局。

## 验证

- Swift 全量 202 项通过；新增外部浏览器交接、取消/迟到回调、打开失败/拒绝授权行为回归通过。先前高负载并发时两项脚本预算测试超出墙钟限制，单独串行运行全部通过（模块无限循环中断约 0.5 秒），未改预算或放宽断言。
- Android JVM 159 项、lint、Debug/Release 和设备测试包构建通过。
- iPhone、iPad 的分类 Tab、竖屏滑动/评论/收藏/字幕/画质操作分别连续五轮通过；另外视频返回/展开浮窗和画质后续沿用回归通过。
- Android 分类 Tab、竖屏操作及目录分页各连续五轮通过；竖屏回归等待真实解码首帧。组件大字、横版评论共享和画质偏好回归通过。最初 SurfaceView 截图只有黑底，首帧回调并不能证明最终合成正确；改用 TextureView 后复核真实蓝色视频画面。
- 独立插件 38 项 Node 测试和语法检查通过；真实现有账号只读验证首页、Shorts 两页、分类两页及首页续页。推荐客户端确认 logged_in=1，数量与顺序受地区、终端和时间影响，不承诺与官方手机 App 逐条一致。
- 真机 iPhone Build 33 已无线覆盖并成功启动，142 个私有文件全部保留（141 个字节一致，媒体索引 JSON 语义一致）；插件 0.2.3 逐文件合并并读回 9 个公开文件，126 个私有文件内容保留。
- Android 正式版使用原签名覆盖到 versionCode 33，firstInstallTime 保持 2026-11-21 22:00:07；未卸载、未清数据。插件已发起更新，手机确认及版本读回仍待完成。

真实 Google 重新授权由用户完成，不能用模拟器交接测试替代。没有退出或清除现有账号来强行触发授权。iOS 公开 IPA 无签名，真机覆盖使用原团队开发签名。生产源码摘要见 source-sha256.json，与正式构建工作区核对。
