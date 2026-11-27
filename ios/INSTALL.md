# 把山海安装到自己的 iPhone

需要 Mac、完整 Xcode、iOS 17 或更新的 iPhone、能传数据的数据线和自己的 Apple 账号。免费账号可用 Personal Team，描述文件通常 7 天到期。

## 用 Xcode 直接安装

1. 下载仓库 `ios` 分支，打开 `ios/MBiOS.xcodeproj`。顶部 scheme 选 `MBiOS`。
2. Xcode → Settings → Apple Accounts（旧版叫 Accounts）登录自己的 Apple 账号。
3. 数据线连接手机，解锁并在手机点“信任此电脑”。
4. Xcode 左侧点蓝色工程图标，中间 TARGETS 选 `MBiOS`，再点 `Signing & Capabilities`。勾上 `Automatically manage signing`，`Team` 下拉选择“你的名字 (Personal Team)”。这就是让 Xcode 用你自己的账号给 App 签名。
5. Xcode 顶部运行设备选你的 iPhone，点左上角三角形运行。
6. 如提示开发者模式，在手机「设置 → 隐私与安全性 → 开发者模式」开启，按提示重启并确认。
7. 如提示“不受信任的开发者”，在手机「设置 → 通用 → VPN 与设备管理」点自己的 Apple 账号，再点「信任」，按系统要求确认。然后回桌面打开山海；部分 iOS 版本显示「允许与重新启动」。

手机需要联网验证开发者。如果 Team 里没有账号，返回第 2 步；如果 Bundle ID 已被别的团队占用，改为自己唯一的 `com.你的名字.mb.ios`。账号密码和系统钥匙串密码不用发给任何人。

## 命令行签名安装

已在 Xcode 登录、设备配对完成后，在 `ios/` 执行：

```bash
bash scripts/install-device.sh TEAM_ID DEVICE_UDID
```

`TEAM_ID` 是自己的团队 ID，`DEVICE_UDID` 是真机标识，可通过 `xcrun xcdevice list` 查看。脚本构建 Release、核对签名/团队/设备授权/到期时间、导出 `.build/artifacts/*-development.ipa`，再用官方 `devicectl` 安装。仓库不预填个人团队或设备。

## 未签名包与到期

`bash scripts/package-ipa.sh` 生成当前工程版本的 `*-unsigned.ipa`。它供 AltStore 等工具用自己的账号重签，不是直接可安装的成品。AirDrop、聊天发送或在“文件”里点 IPA 都不等于安装。

免费签名到期后重新连接手机，在 Xcode 运行或重跑安装脚本。尽量不要先卸载，卸载会删除 App 数据。签名仅对描述文件授权的设备有效。

2026-11-18 的 build 3 已完成真机签名和安装；本轮 build 7 的功能与验收状态以 [PARITY.md](PARITY.md) 为准，旧包安装成功不代表新功能真机验收通过。

苹果说明：[Personal Team](https://developer.apple.com/support/compare-memberships/)、[开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。

## CarPlay 使用

在手机打开山海并开始播放，再连接 CarPlay、进入车机自带的「正在播放」。这条路径不需要独立 CarPlay App 的专属权限。默认主标题按音频时钟逐句显示歌词，副标题显示歌名；无歌词时主标题回退到歌名。可在「设置 → CarPlay 与锁屏」关闭，也会同步影响手机锁屏和控制中心。车机决定文字刷新、截断和滚动，暂无真实车辆验收。


## 设备工具更新插件

优先使用 App 内插件安装器原子替换代码。通过 `devicectl device copy to` 更新插件时，只合并 `plugins/installed/<id>` 中的公开代码，保留独立 `plugins/data` 目录；复制后读回公开入口与清单核对，并检查其他插件仍在。

**禁止传入 `--remove-existing-content true`。** 当前设备工具实际会清理整个 App 数据域，不能安全地用来替换单个插件目录。2026-11-19 发生过一次该参数导致原有插件登录数据丢失的安装事故；插件代码已恢复，账号需重新登录。不要用读取或提交 Cookie 的方式补救。
