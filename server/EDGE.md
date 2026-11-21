# MB 边缘端接入 v1

Android、iOS、桌面各自用 HTTPS 直连用户填写的自托管节点。公开仓库和边缘端应使用同一套通用协议，由节点提供插件发布及加密会话同步。以下是三端实现约定，尚未在播放器或真实第三方账号上验收。

## 实现范围

三端信息源均由可独立安装的外部插件提供。桌面已提供插件会话迁移协调能力；只有实际声明迁移能力的插件才可导出、验证及提交会话。移动端平台资料同步的是已验证账号的 ID 和显示名；通用插件偏好另存 `app/plugins`。两者均不包含登录 Cookie、资料库或完整应用设置，不应把资料同步成功描述为已恢复登录。

## 接入节点

1. `我的 → 山海服务 → 接入节点`（桌面为账号工具区的“山海服务”） 只显示「服务器域名 / HTTPS 地址」「密码」。不预填私有节点。裸域名补 `https://`，使用系统证书校验，禁止将密码或令牌转发到重定向后的其他地址。密码按输入原样提交，设备名称和平台由宿主填入。
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

## 平台资料 API

`GET/PUT/DELETE /v1/data/{platform}/{key}` 使用同一设备 Bearer 授权；平台和键均符合 `^[a-z0-9][a-z0-9_-]{0,63}$`。当前客户端键为 `profile`，明文是 `{"value":{"account_id":"...","display_name":"..."}}`。平台 ID 是插件稳定 ID，不是本地重命名后的名称。

- 密文外壳、大小限制、强 ETag、写入前置条件与会话 API 相同。首次 PUT 返回 201，替换和删除返回 204，并附新的 16 字节随机版本 ETag。
- 不存在的记录返回 **404 JSON `{"error":"not_found"}`**；没有实现接口的普通 404 不能当作空资料。删除返回 410 JSON `{"error":"deleted"}` 和墓碑 ETag；旧版本写入返回 412，缺少前置条件返回 428。
- HKDF-SHA256 的 IKM 为 recovery_key，盐为 `mb-sync-v1\n<space>`，info 为 `platform-data`；32 字节密钥用于 AES-256-GCM。12 字节随机 nonce，标签追加在密文末尾。AAD 为 `mb-data-v1\n<space>\n<platform>\n<key>\n<format>`；`\n` 表示单个 LF。
- 服务器仅保存密文，不能确认真实平台账号。客户端校验账号 ID、格式及认证标签；不同账号、坏密文、删除墓碑或版本冲突均不得覆盖本地资料。

## 插件偏好缓存

`/v1/data/app/plugins` 复用上述加密合同，format=1、platform=`app`、key=`plugins`。服务器无需了解插件内容。明文：

```json
{"value":{"version":1,"names":{"plugin-id":{"value":"本地名称","clock":1,"writer":"AAECAwQFBgcICQoLDA0ODw"}},"enabled":{},"order":{"value":["plugin-id"],"clock":2,"writer":"AAECAwQFBgcICQoLDA0ODw"}}}
```

- `names`、`enabled` 必须为对象；`order` 可缺失或为 null。空名称是恢复默认名称的墓碑，空排序数组是明确清空排序；不能用删字段代替重置。
- 每个名称、启用状态、整份排序分别使用寄存器。`clock` 为 1 到 2^53−1 的整数，写入时取已观察到的最大逻辑时钟加一；`writer` 是本地持久的随机 16 字节 Base64URL 标识，无填充。
- 合并按 `(clock, writer)` 取大值；相同版本戳却值不同视为无效资料。不同字段互不覆盖。writer 只包含 ASCII，按字典序比较。
- 最多 512 个不同插件 ID，ID 匹配 `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}` 且不含 `..`；排序不重复。名称最多 60 个 Unicode 码点、240 UTF-8 字节，不包含控制字符。
- 本地先原子落盘并保存独立恢复副本，再后台 GET、合并最新本地并保存，最后使用 If-Match / If-None-Match 写入节点。412 或写入回执丢失时重读合并，最多六轮；断网不删除本地缓存，后续触发继续尝试。
- 启动、接入节点、回到前台及本地偏好变化时主动同步。远端不存在且本地为空时不上传空占位；本地为空时恢复远端。未安装的插件 ID 继续保留，以便重装插件后恢复名称和顺序。
- 节点切换、解除绑定或任务取消后忽略旧响应；损坏密文和 410 墓碑不覆盖本地、不自动重新创建。
- 正常覆盖更新保留本地缓存。卸载后若系统删除所有应用数据，需重新接入同一节点恢复已经上传的偏好；不借助系统钥匙串保留节点凭据。
- 数据库版本 2 仅新增 platform_data 表。版本 1 的 spaces、devices、invites 和 sessions 不变；升级前使用旧服务的 backup 命令保存一致性快照，保留节点 recovery_key 和目录签名公钥。
