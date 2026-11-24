import MBFoundation
import SwiftUI

struct NodeDeviceSettingsView: View {
    let model: PlatformDataModel
    let preferences: PreferencesModel
    let plugins: PluginModel
    let downloads: DownloadModel
    let library: LocalLibraryModel
    let playback: PlaybackModel
    let names: [String: String]
    let accounts: [String: String]
    let openAccount: (String) -> Void
    @State private var showingRestore = false
    @State private var devices: [SettingsDevice] = []
    @State private var selected: String?
    @State private var preview: NodeDeviceSettings?
    @State private var message: String?
    @State private var work: Action?
    private enum Action: Equatable { case initial, refresh, backup, preview(String), apply }

    private static let fieldNames = [
        "appearance": "外观",
        "language": "语言",
        "quality": "音质",
        "smoke": "进度条烟雾",
        "fog": "烟雾背景",
        "growFromCover": "从封面展开",
        "lyricsStage": "舞台歌词",
        "hotComments": "间奏热评",
        "stageSparks": "歌词火花",
        "nowPlayingLyrics": "系统播放器歌词",
        "pauseOtherSourcesOnSwitch": "切换音源时暂停",
        "inAppVideoWindow": "应用内视频窗口",
        "systemVideoWindow": "系统视频窗口",
        "automaticUpdateChecks": "自动检查更新",
    ]
    private static let valueNames = [
        "true": "开启",
        "false": "关闭",
        "system": "跟随系统",
        "dark": "深色",
        "light": "浅色",
        "high": "高",
        "lossless": "无损",
        "standard": "标准",
        "zh-CN": "简体中文",
        "en": "English",
    ]

    private func settingGroup(_ title: String, values: [String: String], plugin: String?) -> some View {
        DisclosureGroup {
            ForEach(values.keys.sorted(), id: \.self) { key in
                let value = values[key] ?? ""
                let field = plugins.installed.first { $0.id == plugin }?.manifest.settings?.first { $0.key == key }
                let label = field?.label ?? (plugin == nil ? Self.fieldNames[key] : nil) ?? key
                let option = field?.options?.first { $0.value == value }?.label
                let display = option ?? ((plugin == nil || field?.type == "boolean") ? Self.valueNames[value] : nil) ?? value
                LabeledContent(label, value: display)
            }
        } label: {
            LabeledContent(title, value: "\(values.count) 项")
        }
    }

    var body: some View {
        Section {
            Button("备份本机") { work = .backup }
            Button("从其他机器恢复") { showingRestore.toggle(); if showingRestore { work = .refresh } }
                .accessibilityIdentifier("restore-other-device")
            if showingRestore && !devices.contains(where: { !$0.current && $0.updated_at > 0 }) {
                Text("暂无其他机器的备份，请先在原机器点“备份本机”。").foregroundStyle(.secondary)
            }
            ForEach(devices.filter { showingRestore && !$0.current && $0.updated_at > 0 }) { item in
                Button(item.name + " · " + item.key.suffix(6) + " · " + Date(timeIntervalSince1970: TimeInterval(item.updated_at)).formatted(date: .numeric, time: .shortened)) {
                    if selected == item.id { selected = nil; preview = nil }
                    else { work = .preview(item.id) }
                }
                if selected == item.id, let preview {
                    if let backup = preview.backup { Text("完整备份 · \(backup.plugins) 个插件").foregroundStyle(.secondary) }
                    else { Text("旧备份 · 仅设置").foregroundStyle(.secondary) }
                    settingGroup("App 设置", values: preview.app, plugin: nil)
                    ForEach(preview.plugins.keys.sorted(), id: \.self) { id in
                        settingGroup((names[id] ?? id) + " · 插件设置", values: preview.plugins[id] ?? [:], plugin: id)
                    }
                    ForEach(preview.accounts.keys.sorted(), id: \.self) { id in
                        HStack {
                            LabeledContent(names[id] ?? id, value: preview.accounts[id] ?? "")
                            if plugins.sources[id] != nil { Button("打开账号") { openAccount(id) }.buttonStyle(.borderless) }
                        }
                    }
                    Button(preview.backup == nil ? "恢复这些设置" : "恢复到本机") { work = .apply }
                }
            }
            if work != nil { ProgressView() }
            if let message { Text(message).font(.callout) }
        } header: {
            HStack { Text("设备设置"); Spacer(); Button("刷新") { work = .refresh } }
        }
        .disabled(work != nil)
        .task { work = .initial }
        .task(id: work) {
            guard let action = work else { return }
            defer { work = nil }
            do {
                message = nil
                let operation = action
                if action == .initial {
                    devices = try await model.settingsDevices()
                    return
                }
                switch operation {
                case .initial: return
                case .refresh: devices = try await model.settingsDevices()
                case .preview(let id):
                    let doc = try await model.readDeviceSettings(key: id)
                    try Task.checkCancellation()
                    selected = id; preview = doc
                case .backup:
                    try await model.backupCurrentDevice(preferences: preferences, plugins: plugins, downloads: downloads, library: library, accounts: accounts)
                    selected = nil; preview = nil; message = "已备份本机，包含插件和插件信息"
                    do { devices = try await model.settingsDevices() }
                    catch { message = "本机已备份 · 刷新失败" }
                case .apply:
                    guard let doc = preview else { return }
                    if preview?.backup != nil, let selected {
                        try await model.restoreCurrentDevice(key: selected, settings: doc, preferences: preferences, plugins: plugins, downloads: downloads, library: library, playback: playback)
                        message = "已从其他机器恢复本机"
                        return
                    }
                    var applied = 0
                    for plugin in plugins.installed {
                        guard let incoming = doc.plugins[plugin.id] else { continue }
                        var local = try await plugins.settings(for: plugin)
                        var changed = 0
                        for field in plugin.manifest.settings ?? [] {
                            if let v = incoming[field.key], field.acceptsPortableValue(v) { local[field.key] = v; changed += 1 }
                        }
                        try Task.checkCancellation()
                        if changed > 0 {
                            await plugins.saveSettings(local, for: plugin)
                            if let error = plugins.errorMessage { message = error; return }
                            applied += changed
                        }
                    }
                    try Task.checkCancellation()
                    applied += preferences.values.restoreDeviceSettings(doc.app)
                    await preferences.flush()
                    if let error = preferences.errorMessage { message = error; return }
                    let total = doc.app.count + doc.plugins.values.reduce(0) { $0 + $1.count }
                    message = "已恢复 \(applied) 项 · 跳过 \(total - applied) 项"
                }
            } catch is CancellationError { } catch { message = error.localizedDescription }
        }
    }
}
