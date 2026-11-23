/** 配置山海桌面应用的品牌、兼容标识、打包和签名更新源。 */
export default ({ command }: { command: string }) => ({
  name: "山海",
  identifier: "com.xtreeroot.mb",
  slug: "mb",
  version: "0.1.18",
  out: "build",
  urlSchemes:
    command === "dev" ? ["mb-dev", "mygo-music-dev"] : ["mb", "mygo-music"],
  updates: {
    github: "xTreeRoot/ShanhaiMusic",
    tagPrefix: "mb-desktop-v",
    publicKey: "orNo8Ib7RntQ42IOKnu1gnEXVf5ARdO7EXZtopdF5r8=",
    deltas: 0,
  },
  macos: {
    minimumSystemVersion: "13.0",
  },
  linux: {
    comment: "山海 · 万端归一 — Music, video and information in one place",
    categories: ["AudioVideo", "Audio", "Player"],
    webview: false,
  },
});
