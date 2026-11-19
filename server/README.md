# MB Server

独立 Go 模块，部署于 `music.treeroot.top`。SQLite 保存设备授权和不可解密的插件会话，包目录和播放器不依赖数据库。边缘端接口与待办见 [EDGE.md](EDGE.md)。

## 构建和发布插件

在本目录使用 Go 1.26 或更高版本：

```sh
go test -race ./...
CGO_ENABLED=0 go vet ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o mb-server .
```

在签名机使用本机二进制。首发用 `mb-server keygen -key <私钥路径> -public <公钥路径>` 生成；后续继续用同一私钥，不要重新 `keygen`。私钥仅留签名机和独立安全备份，不能上传 VPS 或提交 Git。当前公钥提交在 `catalog.pub`；轮换要先发布信任新公钥的播放器。

从插件仓库的明确 Release 标签下载经过审核的 ZIP。首发目录包含 `xTreeRoot/mygo-music-netease` 的 `v0.1.15`、`xTreeRoot/mygo-ximalaya` 的 `v0.1.5`、`xTreeRoot/mygo-music-soda` 的 `v0.2.1` 和 `xTreeRoot/mygo-music-youtube-music` 的 `v0.1.2`。下面演示生成下一版目录；`-dir` 不能已存在。旧包要回滚时，用更大的目录修订号签回旧包，不直接发旧目录。

```sh
mb-server sign -key "$SIGNING_KEY" -dir release-2 -revision 2 releases/*.zip
mb-server verify -key catalog.pub -dir release-2
```

## 部署和回滚

- VPS 用独立 `mb-server` 系统用户运行 `deploy/mb-server.service`，可执行文件和发布目录都由 root 管理。状态目录 `/var/lib/mb-server` 不随发包替换，SQLite 在 `/var/lib/mb-server/mb.sqlite`。服务只监听 `127.0.0.1:19047`。
- 部署新包：上传已签名目录至 `/opt/mb-server/releases/<revision>`，用受信公钥运行 `verify`；原子替换 `/opt/mb-server/current` 的符号链接，重启 `mb-server.service`，检查本机和公网 `/healthz` 及 `/v1/catalog`。平时不复用旧修订号。更新二进制先保存上一个版本，失败恢复二进制及兼容目录后重启。
- 现有 Caddy 只增加 `deploy/mb-server.caddy` 站点，配置检查通过后重载，域名 A 记录指向 VPS。HTTPS 正常后再让客户端接入。Caddy 不开启请求体日志，诊断不打印绑定码、设备令牌或会话。

## 设备管理

```sh
sudo -u mb-server /opt/mb-server/mb-server invite -db /var/lib/mb-server/mb.sqlite -space personal
sudo -u mb-server /opt/mb-server/mb-server devices -db /var/lib/mb-server/mb.sqlite -space personal
sudo -u mb-server /opt/mb-server/mb-server revoke -db /var/lib/mb-server/mb.sqlite -space personal -device "$DEVICE_ID"
```

绑定码只交给目标用户，不发工单、日志或提交。撤销后该令牌的后续请求返回 `401`，不代表已撤销第三方会话。

## 备份和恢复

```sh
sudo -u mb-server /opt/mb-server/mb-server backup \
  -db /var/lib/mb-server/mb.sqlite \
  -out /var/lib/mb-server/backup-YYYYMMDD.sqlite
```

备份在线生成完整 SQLite 快照，拒绝覆盖已有文件。用受控的加密存储保留异地副本；同时独立保存发布目录、公钥以及离线私钥和客户端恢复密钥（不把后两者与数据库放一起）。恢复前先把快照放到隔离目录并验证可打开。正式恢复先停服务、备份现有主文件和 WAL/SHM，再以已验证快照替换并恢复文件权限。旧快照可能恢复之后才撤销的令牌或删除的会话；对外重新开放前，按故障后的可信记录补做撤销/删除，不确定时撤销旧设备重新绑定。
