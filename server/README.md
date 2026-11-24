# MB Server

独立 Go 模块。节点自行配置 HTTPS 地址和接入密码。SQLite 保存设备授权、加密会话和同步资料；插件目录不依赖 SQLite。边缘端接口见 [EDGE.md](EDGE.md)。

## 构建和发布插件

在本目录使用 Go 1.26 或更高版本：

```sh
go test -race ./...
CGO_ENABLED=0 go vet ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o mb-server .
```

在签名机使用本机二进制。首发用 `mb-server keygen -key <私钥路径> -public <公钥路径>` 生成，后续继续用同一私钥。私钥仅留签名机和独立安全备份。把生成的公钥部署为 `/opt/mb-server/catalog.pub`。每个节点有自己的信任公钥，边缘端在首次接入成功后保存。

从插件仓库的明确 Release 标签下载经过审核的 ZIP。首发目录包含 `xTreeRoot/mygo-music-netease` 的 `v0.1.15`、`xTreeRoot/mygo-ximalaya` 的 `v0.1.5`、`xTreeRoot/mygo-music-soda` 的 `v0.2.1` 和 `xTreeRoot/mygo-music-youtube-music` 的 `v0.1.2`。下面演示生成下一版目录；`-dir` 不能已存在。旧包要回滚时，用更大的目录修订号签回旧包，不直接发旧目录。

```sh
mb-server sign -key "$SIGNING_KEY" -dir release-2 -revision 2 releases/*.zip
mb-server verify -key catalog.pub -dir release-2
```

## 部署和回滚

- VPS 用独立 `mb-server` 系统用户运行 `deploy/mb-server.service`，可执行文件和发布目录都由 root 管理。状态目录 `/var/lib/mb-server` 不随发包替换，SQLite 在 `/var/lib/mb-server/mb.sqlite`。服务只监听 `127.0.0.1:19047`。
- 部署新包：上传已签名目录至 `/opt/mb-server/releases/<revision>`，用受信公钥运行 `verify`；原子替换 `/opt/mb-server/current` 的符号链接，重启 `mb-server.service`，检查本机和公网 `/healthz` 及 `/v1/catalog`。平时不复用旧修订号。更新二进制先保存上一个版本，失败恢复二进制及兼容目录后重启。
- 部署前将 `deploy/mb-server.caddy` 中的 `music.example.com` 替换成自己控制的域名，域名解析指向自己的服务器。配置检查通过后重载 Caddy。边缘端可填域名或可通过证书验证的 HTTPS 地址。访问日志不要记录请求体、设备令牌或会话。

## 固定密码接入

以 root 登录自己部署的节点，将仓库中的 `deploy/node-access.conf` 安装为 systemd drop-in。输入自己的密码，密码原样比较，无最低位数或字符组合要求。以下示例在 Bash 中运行：

```sh
install -d -m 0700 /etc/mb-server
install -d -m 0755 /etc/systemd/system/mb-server.service.d
IFS= read -rs -p '节点接入密码: ' mb_node_password
printf '\n'
printf '%s' "$mb_node_password" | /opt/mb-server/mb-server node-config -out /etc/mb-server/node.json
unset mb_node_password
install -m 0644 deploy/node-access.conf /etc/systemd/system/mb-server.service.d/node-access.conf
systemctl daemon-reload
systemctl restart mb-server
```

`node-config` 会生成独立于口令的随机会话密钥，并拒绝覆盖已有配置。密码和密钥文件的权限为 `0600`，两者由节点所有者保管。修改密码时只改 JSON 中的 `password` 并重启，保留 `recovery_key`，否则设备无法恢复既有会话。此模式的同步空间固定为 `node`，设备列表与撤销使用 `-space node`。改密码后如需使已签发的设备令牌失效，逐台撤销。未安装 drop-in 时只提供一次性绑定码模式。

升级前备份 SQLite 和节点配置。0.1.1 将 v1 和两种已发布的 v2 表结构升级到 v3，保留设备、会话、资料密文、版本标记及已有的更新时间；若回滚程序不支持该版本，不能只换二进制，需与升级前的快照配套恢复。对外校验不只看 `/healthz`：还应核对目录签名和已授权设备的 `/v1/sync/overview`。

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

备份在线生成完整 SQLite 快照，拒绝覆盖已有文件。用受控的加密存储保留异地副本，独立保存发布目录和公钥。节点配置 `/etc/mb-server/node.json`、签名私钥及旧模式的客户端恢复密钥都应独立于数据库保存。恢复前先把快照放到隔离目录并验证可打开。正式恢复先停服务、备份现有主文件和 WAL/SHM，再以已验证快照替换并恢复文件权限。旧快照可能恢复之后才撤销的令牌或删除的会话；对外重新开放前，按故障后的可信记录补做撤销/删除，不确定时撤销旧设备重新绑定。
