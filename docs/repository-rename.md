# 山海仓库与多设备同步

山海 · 万端归一。项目仓库为 [xTreeRoot/ShanhaiMusic](https://github.com/xTreeRoot/ShanhaiMusic)。

## 同步源码

在对应工作树核对远程地址、分支与未提交修改：

```sh
git remote set-url origin https://github.com/xTreeRoot/ShanhaiMusic.git
git remote -v
git fetch origin --tags
git status --short --branch
```

桌面使用 `main`，iPhone/iPad 使用 `ios`，Android 使用 `android`，服务端使用 `server`。工作区干净且本地提交已保留后快进同步，例如桌面：

```sh
git pull --ff-only origin main
```

不能快进时先对比并处理本地提交，不强制覆盖。固定工作树与分支约定见根目录 `AGENTS.md`。

## 覆盖更新

保持应用标识、签名、登录回调、插件安装协议与数据目录一致。不要卸载应用、清空数据或重新生成签名密钥；不要提交密钥或账号凭据。

更新后检查客户端名称、版本检查、原账号、插件、资料库及设置。
