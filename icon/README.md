# 山海应用图标

`shanhai.png` 是桌面、iOS、Android 和网站共用的 1024 × 1024、不透明方形图标母版。

当前角色由用户确认选用：白发、紫瞳、龙角的成年女性，搭配白衬衫、灰色开衫、蓝灰格裙及深色长袜。使用 `imagegen-codex-bridge` 调用 `gpt-image-2`，根据用户提供的两张参考图重绘融合。

- `shanhai-character.png`：用户选定的完整角色原图，1024 × 1536。
- `shanhai-character.prompt.txt`：生成该角色的完整提示词。
- `shanhai.png`：从角色原图的 `(92, 0, 932, 840)` 区域等比例取景，放大至 1024 像素方形画布。按用户要求让角色占满图标，突出完整龙角、脸、头发和上半身，并保留裙装及部分露腿；完整全身原图单独保留。

生成参考照片和身份配置不随图标提交。

在安装 Xcode 26 或更高版本的 macOS 上，从仓库根目录执行：

```sh
go run ./icon
```

该命令从 `shanhai.png` 同步生成桌面正式版与开发版、iOS、Android、网站图标和 favicon。macOS 使用 Icon Composer 文档处理系统图标圆角；iOS 母版保持不透明且不预裁圆角。
