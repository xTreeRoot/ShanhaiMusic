# 插件导航与管理验收（Build 34）

本轮安卓 0.1.33（versionCode 34）、iOS 0.1.0（Build 34）。宿主仅实现通用导航、管理与更新合同；YouTube 首页内容来自独立插件 0.2.4。

## 行为与截图

| 截图 | 核对内容 |
| --- | --- |
| [安卓插件列表](android-plugin-grouped-overview.png) | 分组列表、详情入口、一键导入与全部更新同排左右分开、无顶部“插件”标题 |
| [安卓插件设置](android-plugin-grouped-settings.png) | 名称、来源、设置项、启用、更新、保存与删除分组 |
| [安卓更新确认](android-plugin-review-notes-collapsed-permissions.png) | 新版说明可见，网络权限默认折叠 |
| [iPhone 更新说明](phone-plugin-review.png)、[iPad 更新说明](pad-plugin-review.png) | 显示实际下载提交的说明；截图包含展开后的权限，默认折叠由自动化另行验证 |
| [iPhone 返回全部](phone-return-all.png)、[iPad 返回全部](pad-return-all.png) | 分类往返后恢复完整 home 请求和全部内容，刷新继续使用当前请求 |
| [iPhone 我的入口](phone-personal.png)、[iPad 我的入口](pad-personal.png) | personal 导航进入声明的页面/集合，避免重复自动入口 |
| [iPhone 权限](phone-permissions.png)、[iPad 权限](pad-permissions.png) | 插件设置网络权限折叠与展开 |

## 验证

- Swift 行为回归 203 项通过。
- Android JVM 回归 160 项通过，lint、Debug 与 Release 构建通过。
- iPhone、iPad 各 3 项相关 UI 行为连续 5 轮通过：分类切换和刷新、更新前检查/取消/失败恢复、网络权限默认折叠与展开。
- 安卓 3 项 UI 行为连续 5 轮通过：分类切换、个人入口请求、更新说明/权限/分组设置。
- 安卓改名、实际拖动排序、发现页横滑平台与模式联动，以及“更多”和账户工具入口，2 项回归连续 5 轮通过。测试在完成编辑后等待列表、设置、发现页面分别出现，避免将两个“完成”操作投递到同一旧页面。
- 独立插件 0.2.4 共 41 项 Node 回归及语法检查通过；真实现有账号验证分类往返与刷新后仍有音乐视频、Shorts、推荐视频，分类及 Shorts 翻页成功。

`source-sha256.json` 记录构建时两端生产源码及工程配置哈希；交付前已与当前工作区逐项核对。

## 真机覆盖与数据

- 安卓正式 APK 使用原签名覆盖安装成功，版本 0.1.33/code 34；firstInstallTime 保持 2026-11-21 22:00:07。未卸载、未清数据、未安装调试或临时辅助包。
- iPhone Build 34 无线覆盖安装成功，安装前后 117 个私有文件内容一致（JSON 按语义比较）。
- iPhone 插件 0.2.4 的 9 个公开文件逐文件合并并读回一致。插件运行后仅推荐分页缓存和过期目录缓存更新，并新增空设置文件；OAuth 会话及其余私有设置保持一致，另有 72 个非缓存私有文件保持一致。没有恢复旧缓存覆盖当前账号。
- 私有备份、真实请求和设备日志仅存本机临时交付目录，不进入仓库。
- 公开 iOS IPA 不带签名；真机安装使用本机团队开发签名构建，公开 IPA 需用户自行签名。
