# 山海的 MyGo 视频后端修补

此目录基于 `github.com/egoist/mygo/ui/video` v0.9.0，保留 MIT 许可证和原始跨平台后端。模块路径位于 MyGo 命名空间下，以继续使用其原有 `internal/media` 和 `internal/scene` 契约；山海通过根 `go.mod` 的本地 replace 引用。

macOS 原版在 `Interval()` 中同步读取 `AVAsset.tracksWithMediaType:`。渲染首帧时，远程服务器未返回媒体数据会阻塞 UI；本地模拟慢网已抓到该调用栈，3 秒网络延迟导致约 6 秒首帧阻塞。

修补把帧率元数据读取放在独立后台任务，用原子值提供渲染间隔。加载前使用 60Hz 间隔，不推迟首帧、不延迟已就绪的数据。后台任务单独 retain 资产，关闭播放器时取消资源加载，无需等待元数据任务，避免把阻塞移到关闭操作。播放器仍使用原有 AVFoundation 硬件解码与 MyGo GPU 渲染。

后续更新 MyGo 时，对照原版合并本目录；确认上游解决相同行为后删除本地模块并恢复 `ui/video` 导入。回归位于 `internal/app/video_loading_test.go` 和 `internal/app/video_native_window_test.go`。

普通测试不会弹出窗口。原生 AppKit 回归须经用户明确授权后，设置 `MB_NATIVE_WINDOW_TESTS=1` 单独运行；默认只运行离屏绘制和解码回归。
