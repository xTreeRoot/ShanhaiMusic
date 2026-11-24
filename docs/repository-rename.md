# 山海仓库改名与另一台电脑同步说明

## 仓库与品牌

- 软件名称：山海。
- 标语：万端归一。
- GitHub 仓库名：`ShanhaiMusic`。
- 新仓库地址：https://github.com/xTreeRoot/ShanhaiMusic
- Git 远程地址：`https://github.com/xTreeRoot/ShanhaiMusic.git`。
- 原仓库 `xTreeRoot/MB` 已直接更名，仓库历史、分支、标签和 Releases 均保留；旧地址由 GitHub 重定向。

## 先在另一台电脑更新远程地址

进入已有的项目目录后执行。目录仍叫 `MB` 也可以，不需要重新克隆或移动文件夹。

```sh
git remote set-url origin https://github.com/xTreeRoot/ShanhaiMusic.git
git remote -v
git fetch origin --tags
git status --short --branch
```

以上操作不会覆盖工作区源码。先保留另一台电脑上的未提交修改和本地提交，不要执行 `git reset --hard`、`git clean` 或强制推送，也不要新建 worktree 或功能分支。

## 再同步代码

桌面同步 `main`，iPhone/iPad 同步 `ios`，安卓同步 `android`，服务端同步 `server`。在对应工作树确认工作区干净、本地提交已妥善保留后快进同步。例如桌面：

```sh
git status --short --branch
git pull --ff-only origin main
```

如果不能快进，请先对比并处理本地提交，不要强制覆盖。`main` 不再保留移动端和服务端工程副本，勿删除 `ios`、`android`、`server` 分支。

## 改名后的兼容约定

- 三端展示名称统一为「山海」，README、网站介绍和 GitHub 描述同步使用「山海 · 万端归一」。
- GitHub API、下载入口和仓库链接使用 `xTreeRoot/ShanhaiMusic`。版本更新校验需要同时接受新仓库和原 MB 仓库的官方发布路径。
- 插件 Stars 导入应同时排除 `xTreeRoot/ShanhaiMusic` 和 `xTreeRoot/MB`，避免把宿主仓库当成插件。
- 保留现有 Android 包名、iOS Bundle ID、桌面应用标识、签名、登录回调和插件安装协议。
- 桌面继续使用 `MB` / `MB Dev` 数据与缓存目录；iOS 的 `MBMobile` 和 Android 已有数据目录继续沿用。
- 已发布的 `mb-*` 标签和 `MB-*` 安装包名称继续有效，不批量重命名历史发布资产。
- 不卸载旧版、不清理应用数据、不重新生成 Android 签名密钥，不把任何密钥或账号凭据提交到仓库。

## 同步后的检查

确认 `git remote -v` 指向新仓库，三端展示名称为山海，版本检查能识别新旧官方发布链接，原账号、插件、资料库和设置仍可正常读取。
