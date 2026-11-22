# iOS 发现页文字标签验收

iOS 0.1.0 Build 37。去掉平台栏、分类栏的独立底色，以及 iOS 26 工具栏共享玻璃形状；保留文字选中态、跟手细下划线与横滑切换。模式切换图标使用透明文字按钮样式。

- [iPhone 浅色全部分类](screenshots/iphone-all-light.png)
- [iPhone 浅色子分类](screenshots/iphone-category-light.png)
- [iPhone 深色](screenshots/iphone-all-dark.png)
- [iPad 浅色](screenshots/ipad-all-light.png)

截图采用固定示例插件，不代表线上封面。已目视检查两层标签在浅色与深色下均无独立色块。

验证：iPhone 平台切换和分类切换两个既有行为用例分别连续五轮，共十项通过；iPad 分类切换连续五轮通过；iPhone 深色分类切换一轮通过。模拟器测试构建与真机 Release 构建成功。`source-sha256.json` 记录 Build 37 构建对应的生产源码摘要，不含后续栏目定制工作。
