# 三端发现栏目配置与按需加载

Android 0.1.40（41）、iOS 0.1.0（42）、macOS 0.1.15。

## 行为与契约

- 发现页“栏目”可独立开关、拖动排序，保存生效；取消不写入。关闭栏目在请求之前过滤，不先请求完整首页再隐藏，不预取关闭栏目的封面。
- 插件通过可选 `discovery` 声明稳定 ID、标题、独立 page 或 builtin、默认开关。宿主复用现有插件路由及通用集合；不加入任何平台专用接口。最多 128 栏目，严格校验重复 ID、路由与集合。`categories` 只作为可选元数据校验，当前没有分类筛选功能。
- 旧插件兼容聚合首页和发现导航，私人导航不重复加入。桌面本地音乐页保持原有流程，不套用插件栏目。栏目使用标题左、更多右、两行横向卡片，更多保留插件返回的真实分页地址。
- 初次按插件默认展示。明确保存后，新增加的栏目默认关闭；“恢复默认”可重新使用当前插件默认值。失效或暂未安装的栏目偏好保留。
- 独立 `app/discovery` 节点文档复用现有认证、加密、逻辑时钟与 ETag 合并；与插件名称/排序的 `app/plugins` 分开。启动、绑定节点、回到前台和本地编辑均主动拉取并合并；桌面另有分钟级同步。需要开启已有节点功能。
- 两份原子本地缓存，单份坏掉从另一份修复；双份损坏返回错误，不能写入默认值覆盖。编辑草稿只提交实际变化，保留编辑期间到达的远端修改。
- 原有普通视频左侧系统音量、右侧系统亮度手势保留，使用 AudioManager/Settings.System 和 MPVolumeView/UIScreen；竖屏短视频禁用，参见前次 [系统手势审阅](../VideoSystemAdjustments/README.md)。

## 插件适配

| 插件 | 版本 |
| --- | --- |
| 哔哩哔哩 | 1.1.3 |
| 网易云音乐 | 0.1.19 |
| QQ 音乐 | 0.3.10 |
| YouTube | 0.2.8 |
| 汽水音乐 | 0.4.2 |
| 波点音乐 | 0.3.6 |
| 喜马拉雅 | 0.1.8 |

YouTube 仅扫描所选独立栏目，关闭时不再补查 Shorts 或音乐。上游聚合响应本身可能包含其他数据，不声称能阻止上游返回其内部捆绑数据。喜马拉雅保留远端 0.1.7 登录取消、游客降级与播放修复。

## 验证

- `npm run test:plugins`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .` 通过。桌面新编辑器通过真实点击、拖动、保存/取消、请求过滤和本地页回归，连续五轮通过；macOS arm64 包构建成功。
- Swift 全量 221 项通过；iPhone/iPad Release 构建通过。iPhone 栏目编辑连续五轮，iPad 栏目编辑与独立栏目下拉刷新，以及 iPhone 分类切换/当前分类刷新回归通过。
- Android JVM 168 项通过；Release lint 0 errors、39 warnings，Release/Debug/AndroidTest 构建通过。插件 VM 设备测试在专用模拟器中执行，验证关闭栏目零请求、保存顺序和真实 morePage。
- 初轮设备回归发现 Android 通用 browse 会将单列表 morePage 移到 nextPage，栏目拼装丢失此地址；已改为保留并通过运行时断言。队列回归改用队列退出后的无障碍点击动作，不等待播放进度不断刷新的全局无障碍空闲。
- 七个外部插件检查/Node 回归通过，哔哩哔哩与波点另有真实 Go/MoeJS 宿主验证。实际账号的全部栏目接口、系统控制中心调节效果及真实跨设备节点恢复尚未逐项真机验收。

## 截图与源码

iPhone/iPad 截图见 [索引](SCREENSHOTS.md) / [交互查看器](index.html)。额外原生组件截图：

- [Android 两行栏目](android-discovery-sections.png)、[Android 重开保留排序和开关](android-discovery-editor-reopened.png)
- [macOS 两行栏目](desktop-discovery-sections.png)、[macOS 栏目编辑](desktop-discovery-editor.png)

均使用隔离 fixture，不包含账号内容；Android 两行截图直接使用生产 PluginDiscoverySection 组件。生产源码摘要见 `source-sha256.json`，发布前与提交和构建输入再次核对；桌面包附相同公开源码快照。

## 真机交付核对

Android 已无线覆盖 0.1.40（41），新旧 APK 原证书一致、首次安装时间未变，用户确认已执行“全部更新”。Android Release 不可 run-as，未读取其私有文件摘要或独立核对全部插件版本。

iPhone 已无线覆盖 Build 42，安装前后 128 个私有文件的规范化摘要一致；七个已有插件随后逐文件合并公开代码，63 个文件读回一致，130 个私有文件摘要未变。两端均启动成功。无卸载、清数据或整域覆盖。
