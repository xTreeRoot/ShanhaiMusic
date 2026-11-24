import MBFoundation
import SwiftUI

struct SettingsView: View {
    @Bindable var preferences: PreferencesModel
    let network: NetworkSettingsModel
    let plugins: PluginModel
    let skin: BackgroundSkin
    var embedded = false
    @Environment(\.dismiss) private var dismiss
    @State private var clearingHistory = false
    @Environment(\.colorScheme) private var scheme
    @State private var appUpdates = AppUpdateModel()
    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        NavigationLink("检查版本") { AppUpdateView(model: appUpdates, automaticChecks: $preferences.values.automaticUpdateChecks) }.accessibilityIdentifier("settings-app-updates")
                    }
                    Section("显示") {
                        Picker("语言", selection: $preferences.values.language) {
                            ForEach(AppLanguage.allCases) { language in Text(LocalizedStringKey(language.title)).tag(language) }
                        }
                        Picker("外观", selection: $preferences.values.appearance) {
                            ForEach(AppAppearance.allCases) { mode in Text(LocalizedStringKey(mode.title)).tag(mode) }
                        }
                        NavigationLink("背景皮肤") { BackgroundSkinSettings(skin: skin, preferences: preferences, opacity: preferences.values.backgroundOpacity ?? (scheme == .dark ? 0.35 : 0.22)) }
                            .accessibilityIdentifier("background-skin-settings")
                    }
                    Section {
                        NavigationLink("插件") { PluginsView(model: plugins) }.accessibilityIdentifier("settings-plugins")
                    } header: { Text("音源") }
                    Section("播放") {
                        Toggle("切换平台时暂停其他平台播放", isOn: $preferences.values.pauseOtherSourcesOnSwitch)
                        Picker("音质", selection: $preferences.values.quality) {
                            ForEach(AudioQuality.allCases) { quality in Text(LocalizedStringKey(quality.title)).tag(quality) }
                        }
                        HStack { Text("视频画质"); Spacer(); Text(preferences.values.videoQuality == "auto" ? "自动（最高可用）" : preferences.values.videoQuality).foregroundStyle(.secondary) }
                        if preferences.values.videoQuality != "auto" { Button("重置视频画质为自动") { preferences.values.videoQuality = "auto" } }
                        Picker("响度标准化", selection: $preferences.values.normalization) {
                            ForEach(AudioNormalization.allCases) { mode in Text(LocalizedStringKey(mode.title)).tag(mode) }
                        }
                        Slider(value: $preferences.values.volume, in: 0...1) { Text("应用音量") }
                        HStack { Text("应用音量"); Spacer(); Text("\(Int(preferences.values.volume * 100))%").monospacedDigit().foregroundStyle(.secondary) }
                    }
                    Section {
                        Toggle("在主标题显示当前歌词", isOn: $preferences.values.nowPlayingLyrics)
                    } header: {
                        Text("CarPlay 与锁屏")
                    } footer: {
                        Text("主标题按播放时间逐句更新歌词，副标题显示歌名；无歌词时主标题显示歌名。")
                    }
                    Section("缓存") {
                        Picker("缓存上限", selection: $preferences.values.cacheMegabytes) {
                            Text("不缓存").tag(0)
                            ForEach([256, 512, 1_024, 2_048, 4_096, 8_192], id: \.self) { size in Text("\(size) MB").tag(size) }
                        }
                        Text("播放缓存按最近使用清理，已下载和导入的音频单独保留。").font(.caption).foregroundStyle(.secondary)
                    }
                    Section("视频小窗") {
                        Toggle("应用内视频小窗", isOn: $preferences.values.inAppVideoWindow)
                        Toggle("离开应用时画中画", isOn: $preferences.values.systemVideoWindow)
                    }
                    Section("播放界面") {
                        Toggle("默认打开歌词", isOn: $preferences.values.lyricsStage)
                        Toggle("间奏显示热评", isOn: $preferences.values.hotComments)
                        Toggle("进度条烟雾", isOn: $preferences.values.smoke)
                        Toggle("低音雾气背景", isOn: $preferences.values.fog)
                        Toggle("从封面展开", isOn: $preferences.values.growFromCover)
                        Toggle("歌词火花", isOn: $preferences.values.stageSparks)
                        Picker("播放器样式", selection: $preferences.values.playerStyle) {
                            ForEach(PlayerVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                        }
                        Picker("歌词舞台", selection: $preferences.values.lyricStyle) {
                            ForEach(LyricVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                        }
                    }
                    Section {
                        NavigationLink("网络与系统代理") { NetworkSettingsView(model: network) }
                        Button("清空搜索记录", role: .destructive) { clearingHistory = true }.disabled(preferences.values.searchHistory.isEmpty)
                    }
                    if let error = preferences.errorMessage { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }

                }.modifier(SkinRow())
            }
            .accessibilityIdentifier("app-settings-form")
            .modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
            .navigationTitle("")
            .toolbar { if !embedded { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() }.accessibilityIdentifier("dismiss-settings") } } }
            .confirmationDialog("清空搜索记录？", isPresented: $clearingHistory, titleVisibility: .visible) {
                Button("清空搜索记录", role: .destructive) { preferences.values.searchHistory = [] }
            }
        }.id(preferences.values.language).accessibilityIdentifier("settings-sheet")
            .modifier(PluginPreparationPresentation(model: plugins))
    }
}
