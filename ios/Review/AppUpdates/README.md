# Build 26 应用版本更新验收

设置新增“检查版本”。从山海官方 GitHub Releases 中筛选 iOS 正式资产，按产品版本和 Build 数字比较；下载支持取消，逐块校验大小和 SHA-256，成功后通过分享交给签名工具。取消后忽略迟到的检查结果，并删除迟到的下载文件。

公开 IPA 为未签名分发包。iOS 不允许应用自行安装它；页面说明需使用现有 Apple 签名与包名覆盖安装，保留原应用和数据。

验证结果：

- 198 项 Swift 回归通过，其中联网回归默认跳过；另行设置 `MB_LIVE_APP_UPDATE=1` 后，真实获取官方 Build 25 资产，下载 3,437,079 字节并通过摘要校验。
- iPhone 与 iPad 通用 Release 真机构建通过，`UIDeviceFamily` 为 `[1, 2]`。
- `testAppUpdateSettingsCanOpenAndReturnWithoutLosingSettings` 在 iPhone、iPad 模拟器各连续运行 5 轮，全部通过。验证从设置进入、显示版本和签名说明、返回设置与关闭设置。该测试不触发真实远程安装。
- 最初 iPhone 短点击有两次没有改变系统界面；依据截图和层级，将测试手势改为完整按下/抬起，返回按钮限定当前导航栏。生产 UI 未为测试增加重试。
- Build 26 已无线覆盖安装到 iPhone 15 并成功启动，没有卸载、清数据或清空 App 数据域。
- 安装后从设备读回插件清单：网易云 0.1.17、汽水 0.4.0、YouTube Music 0.1.7、喜马拉雅 0.1.7。用户已确认 YouTube 发现页可显示内容。

截图索引：

- [iPhone 检查版本](iphone-check-version.png)：当前版本 0.1.0 (26)、检查入口与签名说明。
- [iPad 检查版本](ipad-check-version.png)：平板弹层布局、版本与相同操作，文字完整可见。

公开资产为 `MB-iOS-v0.1.0-build26-unsigned.ipa`，大小 3,462,319 字节，SHA-256 为 `7762db133694fd5f52292b0afbd94956c9066d1dbd195539e5c3d94c0babd804`。已移除所有嵌入签名、描述文件和签名目录，并核对包名 `com.xtreeroot.mb.ios`、版本 0.1.0 / Build 26。真机覆盖使用本地原开发签名工程，公开资产不含个人签名。
