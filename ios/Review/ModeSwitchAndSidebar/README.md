# 听歌切视频与平板侧栏回归（build 61）

本轮在远程 iOS `7490fcb` 上合入修复，保留最新节点管理与设备设置恢复功能。只修改通用宿主和回归夹具，信息源仍通过独立插件接入。

## 修复

- 听歌页右上角打开视频时，等待全屏听歌页关闭完成后再打开视频；视频切听歌期间保留底层页面，普通收起时清除保留路径。
- 视频重新显示时恢复控制条，并取消离开页面前的自动隐藏任务，避免返回和听歌按钮被旧任务提前隐藏。
- 平板侧栏标题、列表及顶部安全区共用背景；保留收起和系统展开入口。
- 栏目取自当前插件清单，点击后打开对应页面，并以实际导航路径驱动选中高亮；整行至少 44 点的点击高度。
- 栏目提供加载、无内容、失败和重试状态；没有音源时提供插件管理入口。

## 验证

- `bash ios/scripts/verify-core.sh`：251 项通过。
- `bash ios/scripts/check-ui-host.sh`：SwiftUI 公共模块编译通过。
- iPad Pro 13 英寸 M5 / iOS 26.5 模拟器：侧栏回归连续 5 轮通过，包括横竖屏行尾点击、选中状态、空数据重载、失败后重试、切回栏目、收起与展开。
- iPhone 17 Pro / iOS 26.5 模拟器：模式切换连续 5 轮通过；每轮 3 次听歌切视频，检查状态栏边界、返回及模式按钮命中，再检查普通收起、返回小窗、关闭与重开。
- 两端 `testRestoreOtherDeviceSettings` 均通过，确认合并后设备备份明细与恢复流程仍可用。
- `bash ios/scripts/package-ipa.sh`：iPhone/iPad 通用 Release 构建及未签名 IPA 结构校验通过。
- `verified-sources.sha256` 与安装包旁的 `MB-iOS-0.1.0-61-sources.sha256` 一致；`verified-tests.sha256` 记录本轮测试代码与素材。提交前逐文件核对 Git 对象与已验证源码。

模拟器使用隔离的示例插件和本地视频。用户真机原路径、真实平台数据、扫码登录及网络播放仍需真机复测。

## 截图索引

精选最终代码第 5 轮截图，导出文件与设备对应关系见 `screenshots.json`。

| 截图 | 检查内容 |
| --- | --- |
| [iPad-侧栏空态-横屏.png](iPad-侧栏空态-横屏.png) | 标题与列表同色，空栏目高亮与重载操作 |
| [iPad-侧栏空态-竖屏.png](iPad-侧栏空态-竖屏.png) | 竖屏选中与重载 |
| [iPad-侧栏失败与重试.png](iPad-侧栏失败与重试.png) | 失败时选中状态与重试入口 |
| [iPad-侧栏切回内容.png](iPad-侧栏切回内容.png) | 切换回实际内容 |
| [iPad-收起侧栏.png](iPad-收起侧栏.png) | 系统展开按钮 |
| [iPad-重新展开侧栏.png](iPad-重新展开侧栏.png) | 展开后保留首页选中状态 |
| [iPhone-听歌切视频-第1轮.png](iPhone-听歌切视频-第1轮.png) | 保留状态栏及顶部按钮 |
| [iPhone-听歌切视频-第2轮.png](iPhone-听歌切视频-第2轮.png) | 收起并重开后再次切换 |
| [iPhone-听歌切视频-第3轮.png](iPhone-听歌切视频-第3轮.png) | 多次往返后的控制条与安全区 |

双端测试摘要为 `tablet-summary.json`、`phone-summary.json`，设备设置恢复摘要为 `tablet-node-summary.json`、`phone-node-summary.json`。完整回归在仓库根目录 `.build/mb-artifacts/success/ios-simulator-test/1795525984163752000-m40078ah/`，保留待真机验收。

## 安装包与产物

`../../.build/artifacts/MB-iOS-0.1.0-61-unsigned.ipa` 为未签名包，需要通过现有侧载流程重新签名。

保留 `ios/.build` 固定缓存、最新 IPA、双端完整回归及本目录截图；清理本轮已解决的失败结果与重复调试附件，接管前历史 IPA 和其他平台缓存不变。前次清理实际增加 534.4 MiB 可用空间，具体路径记录在本地 `cleanup-result.json`；最终容量和当前可用空间在交付时报告。
