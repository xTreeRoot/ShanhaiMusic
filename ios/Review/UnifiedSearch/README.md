# iOS Build 28：平台滑动与统一搜索

发现页保留平台 Tab 样式并移到顶部，支持左右滑动；模式按钮点击切换音乐/视频，中央轻提示，删除播放后多余的右上角入口。搜索页删除模式切换，下拉固定在搜索框下方，当前项带背景和勾选。

统一搜索按各来源独立游标并发查询，结果显示来源与类型，可收藏到独立资源资料库。种子搜索使用 Knaben 官方 v2 GET 合同，提供元数据、磁力链接与外部工具入口，不包含 BT 下载引擎。插件本地名称和顺序独立保存，更新后保留并同步到各平台入口。首页按标题或目的地合并已有导航，并保留“更多”入口。

验证结果：

- 204 项 Swift 行为回归通过，另行启用真实联网测试后，Knaben 官方接口返回 Ubuntu 的资源元数据；其他账号和平台请求没有逐项真机验证。
- 通用 Release 构建通过，版本 0.1.0 / Build 28，UIDeviceFamily 为 [1, 2]。
- iPhone 17 Pro 与 iPad Pro 13 英寸模拟器上，两项新界面行为回归各连续 5 轮通过，共 20 次、零失败。覆盖改名传播、平台横滑、模式状态切换、导航合并后“更多”仍可进入、搜索下拉不遮挡输入框、跨平台结果与资源收藏。
- iPad 最初使用整屏手势，落入了卡片横滚区域并越过侧栏；检查实际截图后，仅将 iPad 测试手势改为分页内容顶部，重新连续通过 5 轮。iPhone 的操作路径保持已验收的坐标。短暂 toast 不使用依赖真实时钟的存在断言，模式切换验证实际 accessibilityValue。
- 截图使用合成插件和曲目，已人工核对布局。`source-sha256.json` 记录最终构建与验收源码；iPad 操作范围修正仅修改 UI 测试，正式安装包源码未变。

按用户要求没有再向 iPhone 真机安装 Build 28。网易云插件 0.1.18 已发布并更新到安卓；本轮没有操作 iPhone 的插件数据。网易云四个个人音乐分区与真实封面由该插件版本提供。

截图索引：

- [iPhone 顶部平台与点击切换模式](iphone-discovery-platforms.png)
- [iPhone 搜索下拉](iphone-search-platforms.png)
- [iPhone 平台和结果类型](iphone-search-results.png)
- [iPad 顶部平台与点击切换模式](ipad-discovery-platforms.png)
- [iPad 搜索下拉](ipad-search-platforms.png)
- [iPad 平台和结果类型](ipad-search-results.png)

公开资产为 MB-iOS-v0.1.0-build28-unsigned.ipa，3,620,537 字节，SHA-256 为 ef292b5cb580e833ba837c28107c5cfa0d1488c09082d084da6c5902672c6ea3。已移除个人签名、描述文件和签名目录，需使用原有签名方式覆盖安装。
