# 播放失败自动续播验收

桌面 0.1.39：当前曲目加载或解码失败后继续寻找可播后继；下首解析或 HTTP 预加载失败后提前准备后继。保留原始队列与各曲目错误，按实际随机顺序推进，单曲循环不能困住错误恢复，全部失败时停止。明确选曲或重新播放允许重试。

- `npm run test:plugins`、`CGO_ENABLED=0 go test ./...`、`go tool mygo vet .` 通过。
- 播放器新增失败、预加载、暂停与迟到响应回归通过；针对相关场景的 race 回归连续 5 次通过。
- `TestQueueClickSkipsFailedSongsAndKeepsFailureReasons` 通过可访问名称点选队列曲目，连续 5 次通过。
- [队列续播截图](playback-failure-continues.png)：前两首失败，后继曲目已选中；原队列保留。截图使用通用本地音频夹具。

本轮无需修改 Server 接口，合集沿用 0.1.4。未以第三方真实账号测试所有音源。
