# MB 边缘端接入 v1

Android、iOS、桌面各自用 HTTPS 直连用户填写的自托管节点。公开仓库和边缘端使用同一套通用协议，由节点提供插件发布、加密会话与资料同步。已验证跨端协议和模拟换机，真实第三方账号跨设备仍需单独验收。

## 要接的模块

| 端 | 现有入口 | 要补的能力 |
| --- | --- | --- |
| 桌面 | `internal/plugin` 的安装器和 `Account` / `SignIn` / `SignOut` | 上游客户端、目录验签、账号同步协调器；继续通过通用音源接口给页面用 |
| iOS | `PluginInstaller`、`PluginModel`、`PluginSource` | 同上；先读 `Application Support/MBMobile`，覆盖升级保留原数据，全新安装绑定后恢复；内置 B 站原生音源适配同一会话合同 |
| Android | `PluginInstaller`、`PluginRuntime`；内置音源有登录入口 | 把外部插件运行时接入通用音源，再加上游客户端和同步协调器；内置 B 站走同一合同 |
| 插件 | 四个独立 Release 仓库；内置 B 站继续由播放器发布 | 一份插件包跑三端。新增可选的会话导出、暂存导入、在线校验和提交接口。格式版本由各插件定义；只传经过审核的可迁移字段，不导出整个 `host.storage` |

现有网易云、喜马拉雅、汽水、YouTube Music 包可从目录分发，但不代表这些包已经实现会话迁移。没有登录能力的插件跳过同步；有设备绑定或平台明确拒绝复用的会话，提示本机登录，不绕过平台要求。三端按插件实际声明的能力展示操作。

## 接入节点

1. `设置 → 接入节点` 只显示「服务器域名 / HTTPS 地址」「密码」。不预填私有节点。裸域名补 `https://`，使用系统证书校验，禁止将密码或令牌转发到重定向后的其他地址。密码按输入原样提交，设备名称和平台由宿主填入。
2. 客户端 `POST /v1/join`，JSON `{"password":"...","name":"我的手机","platform":"ios"}`，平台值仅 `ios`、`android`、`desktop`。`201` 返回 `device_id`、`space_id`、`token`、`expires_at`（Unix 秒）、`recovery_key`、`catalog_public_key`。同一节点上的设备共享同步空间和恢复密钥，每台设备有各自的令牌。`403` 表示密码错误，`404` 表示该节点未启用密码接入，`429` 按 `Retry-After` 重试。设备令牌有效 90 天，过期或撤销后重新接入。
3. 令牌、恢复密钥和验签公钥按规范化 HTTPS 地址隔离存储。只给协议请求附 `Authorization: Bearer <token>`；不要把密码或令牌交给 JS、日志或诊断上传。节点 `401` 是设备授权失败，本机第三方登录保持原状。节点管理员保管恢复密钥，有能力解密节点上对应空间的会话。边缘端不用再单独输入恢复码。

旧节点可继续使用 `POST /v1/pair`，请求用一次性 `code` 替换 `password`，响应只包含设备授权字段，恢复密钥及验签公钥由旧流程单独提供。

## 插件目录与安装

`GET /v1/catalog` 无需令牌，返回 `{"payload":"...","signature":"..."}`。全部 Base64 使用 RFC 4648 Base64URL、无填充。首次密码接入通过可信 HTTPS 时保存该节点返回的 `catalog_public_key`，其解码值为 32 字节 Ed25519 公钥。先解码，再对 **payload 原始字节** 验签，不重新序列化。同一节点后续返回不同公钥时停止自动安装，待用户明确确认更换节点公钥后重新接入。

解码后为 `{"protocol":1,"revision":1,"plugins":[...]}`。每项包含 `id`、`version`、`minAppVersion`、`permissions`、`sha256`（小写十六进制）、`size`、`path`。`minAppVersion` 是 MyGo Music 插件宿主兼容版本，不是 MB 产品版本。记录最后验签通过的最高目录修订号和内容；拒绝降级及相同修订号的不同内容。`path` 必须等于 `/v1/packages/<sha256>`，从同一 HTTPS 上游下载；下载后校验大小（最大 32 MiB）、摘要、插件清单身份、版本和权限，再交给现有安装器安全解包、进行权限确认并原子安装。遇到网络或校验错误保留本地版本；不要卸载有效插件、静默升级权限或恢复用户主动停用的插件。只有签名目录认可的代码才能导入同步会话；同 ID 的本地手工包不是同一信任身份。

## 会话 API

下列请求都需要设备令牌，Server 从令牌推导空间；不要把 `space_id` 放进 URL。`plugin` 是 `plugin.json` 的稳定 ID，`account` 是下文派生的 `account_ref`，不是昵称。

| 方法与路径 | 合同 |
| --- | --- |
| `GET /v1/sessions/{plugin}` | `200 {"sessions":[{"account_ref":"...","etag":"\"...\"","format":1,"deleted":false,"updated_at":0}]}`；`updated_at` 为 Unix 秒。墓碑也返回，`format` 为 `0`；没有会话返回空数组 |
| `GET /v1/sessions/{plugin}/{account}` | `200` 返回密文和 `ETag`；`404` 从未存在；`410` 已删除并附墓碑 `ETag` |
| `PUT /v1/sessions/{plugin}/{account}` | JSON 密文；首次用 `If-None-Match: *` 成功返回 `201`；替换用上次读到的完整 `If-Match: "..."` 成功返回 `204`。两者均返回新的 `ETag` |
| `DELETE /v1/sessions/{plugin}/{account}` | 带完整 `If-Match`；`204` 和新 `ETag`。删除密文但保留版本墓碑防止旧设备复活 |

`412` 表示版本冲突；重读并决定，不盲目把旧队列换成最新 `ETag` 重发。仅用户完成新登录并确认再次同步时，才可用墓碑 `ETag` 重建。丢失 `PUT` 响应后，读取当前会话；如果密文、nonce 和格式等同于已发送内容，则视为已提交。`428` 缺前置条件，`400` 参数或密文外壳非法，`413` 超限。`POST` / `PUT` 请求体为 `application/json`。登录会话响应不可缓存。

`GET /v1/sync/overview` 同样只对当前设备空间开放，列出活跃项的种类、插件 ID、资料键、数量和可用的最近更新时间，不返回账号引用或密文。`/v1/data/{plugin}/{key}` 存储加密资料，使用同样的条件写入和删除版本规则；账号资料不等于会话，不能用于登录。

允许会话同步前，宿主须用已绑定的目录公钥验签，并核对插件 ID、版本和 `content_sha256`。此摘要对文件名按 ASCII 升序排序，每项拼接相对路径、单个 NUL 字节、文件的 32 字节 SHA-256，然后对串联结果做 SHA-256。宿主生成的根目录 `.origin.json`、`.mb-origin.json` 不参与；ZIP 中的 `__MACOSX/` 元数据不参与。缺少被签名的内容摘要时，只允许使用插件本地能力，不向其交付会话。升级既有目录需用同一公钥和更大的目录修订号重新签名。

## 跨端加密格式

- 恢复密钥是 HKDF-SHA256 的 IKM，盐为 UTF-8 的 `mb-sync-v1\n<space_id>`，分别使用 UTF-8 的 `session` 和 `account-ref` 作为 `info`，各派生 32 字节。这里的 `\n` 是一个 LF 字节，末尾无换行。
- `account_ref` 是 `HMAC-SHA256(account-ref key, UTF-8("<plugin_id>\n<平台稳定账号ID>"))` 的 Base64URL。账号 ID 使用插件返回的原始规范字符串；禁止转大小写或用展示名代替。
- 用 `session` 密钥和本次新生成的 12 字节 CSPRNG nonce 做 AES-256-GCM，16 字节标签追加在密文末尾。AAD 是 UTF-8 的 `mb-session-v1\n<space_id>\n<plugin_id>\n<account_ref>\n<format>`；`format` 用十进制整数，不带前导零。明文 JSON 形如 `{"account_id":"...","session":{...}}`，可选 `account_label` 用于本地账号选择。解密后必须重新计算账号引用并在线校验账号 ID，再提交到真实会话存储。
- HTTP JSON 外壳为 `{"format":1,"nonce":"...","ciphertext":"..."}`，最大 128 KiB；`ciphertext` 包含 GCM 标签。Server 检查外壳格式，账号验证和刷新都在客户端执行。

跨端实现测试向量，仅用于测试（固定 nonce 禁止用于真实加密）：`space_id=test-space`，`plugin_id=example`，`format=1`，精确明文 `{"account_id":"user-123","session":{"demo":"synthetic"}}`。

```json
{
  "recovery_key": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "account_ref": "rQgGydt1-xJIAjpM4GXEO4Q0GyBkb4Sro0a5saijxaY",
  "session_key": "pm2o2eFnLNImVtdISrzDqGKuRpo_NKJqy9KmR7A-X9I",
  "nonce": "AAECAwQFBgcICQoL",
  "ciphertext": "4pKzDTCYmD-RlfPCsaqP3vVTdyYeYH7RZNXWMsAplG6Y_w3pJB2uUQ-IuPszwsAhbCxTVArDavI3up94c68j9YUzB1KJ1Tnm"
}
```

## 恢复、同步与验收

1. 启动或选择平台时先查本地插件，只在缺失时从上游安装。有效本地登录优先；没有会话或平台明确确认失效才查云。多个候选账号时显示解密后的账号选择，不自动换号。断网或超时保留现状。
2. 恢复在隔离的暂存区进行，平台验证成功且当前插件、账号、绑定和登录操作都没变，再原子提交；取消后丢弃迟到结果。没有可用会话才提示扫码或网页登录。新登录成功后自动加密上传，失败异步重试，本地可以先用。
3. 本机退出写持久化的禁止自动恢复状态。上游删除是独立操作。解绑本机时清理设备令牌、待发队列和恢复密钥副本。远端设备授权由运维使用 `revoke` 命令撤销，第三方会话由对应平台撤销。
4. 三端各跑测试向量、错误 AAD、签名不符及坏包、离线退出、旧队列冲突、迁移重装。对每个真正支持迁移的插件跑桌面、iOS、Android 六个跨端方向；在新安装设备上实际读取私人歌单/订阅，不能用公开播放或账号昵称代替。
