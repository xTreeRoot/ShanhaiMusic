# 山海网站

山海 · 万端归一。官网以 iPhone、iPad 和 Android 为主，桌面为补充。使用 TanStack Start 与 Solid，在构建时预渲染为静态文件，由 [shanhai.treeroot.top](https://shanhai.treeroot.top/) 提供服务。

```sh
# 在仓库根目录安装依赖
bun install --frozen-lockfile
# 在 website 目录执行
bun run dev
bun run build
```

下载页在构建时读取 GitHub Latest 下的 `release-manifest.json`，核对清单及四个安装包的附件元数据后展示三端客户端和自托管 Server。同一页面上的四个包来自同一条 Release。可通过环境变量 `GH_TOKEN` 或 `GITHUB_TOKEN` 提高 API 限额。官网插件页只收录自家仓库并链接 xTreeRoot 的维护列表。[GitHub Latest](https://github.com/xTreeRoot/ShanhaiMusic/releases/latest)

`public/screenshots` 中的图片来自真实客户端渲染：iPhone 为播放界面演示，Android 为统一搜索验收，桌面为 `TestDiscoveryHomeFrame` 的测试截图。只使用演示数据；发布前裁掉移动端状态栏并清除图片元数据，勿上传带私人账号或设备信息的截图。

`.github/workflows/website.yml` 在官网代码推送到 `main` 或 GitHub Release 发布后自动构建，也支持手动运行。仅上传 `website/dist/client`；服务器用专用的非 root SSH 账号接收、校验并原子切换站点目录。GitHub Secrets 保存部署专用私钥，Variables 保存已核验的服务器公钥；不使用 root 或应用签名密钥。部署验证：`python3 -B -m unittest discover -s website/deploy`。
