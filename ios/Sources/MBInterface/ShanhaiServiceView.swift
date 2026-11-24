import MBFoundation
import SwiftUI

/// 接入节点采用列表式表单，标签与输入左右排列，并复用根视图的同步模型。
struct ShanhaiServiceView: View {
    @Bindable var model: PlatformDataModel
    let preferences: PreferencesModel
    let plugins: PluginModel
    let accounts: [String: String]
    let names: [String: String]
    let providers: [String: any MusicSource]
    let openAccount: (String) -> Void
    @State private var expanded: String?
    @Environment(\.dismiss) private var dismiss
    @State private var passwordVisible = false
    @State private var joinTask: Task<Void, Never>?
    @FocusState private var focused: Field?
    private enum Field { case address, password }

    var body: some View {
        NavigationStack {
            Form {
                if model.linked {
                    Section {
                        LabeledContent(model.authorized ? "已接入" : "授权已过期", value: model.address)
                        Button("解除此设备绑定", role: .destructive) { Task { await model.unlink(); expanded = nil } }.disabled(model.busy || model.sessionBusy)
                    }
                    if model.authorized {
                        NodeDeviceSettingsView(model: model, preferences: preferences, plugins: plugins, names: names, accounts: accounts, openAccount: openAccount).id(model.revision)
                        cloudOverview
                        sourceRows
                    }
                }
                if !model.authorized {
                    Section {
                        HStack(spacing: 12) {
                            Text("节点地址").frame(width: 80, alignment: .leading)
                            TextField("域名或 HTTPS 地址", text: $model.address)
                                .textContentType(.URL).autocorrectionDisabled()
                                .focused($focused, equals: .address).submitLabel(.next)
                                .onSubmit { focused = .password }
                                .accessibilityIdentifier("node-address")
                            if !model.address.isEmpty {
                                Button { model.address = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                                    .buttonStyle(.borderless).accessibilityLabel("清空节点地址")
                            }
                        }.disabled(model.busy)
                        HStack(spacing: 12) {
                            Text("节点密码").frame(width: 80, alignment: .leading)
                            Group {
                                if passwordVisible { TextField("请输入密码", text: $model.password) }
                                else { SecureField("请输入密码", text: $model.password) }
                            }.textContentType(.password).autocorrectionDisabled()
                                .focused($focused, equals: .password).submitLabel(.go)
                                .onSubmit(connect).accessibilityIdentifier("node-password")
                            Button { passwordVisible.toggle() } label: { Image(systemName: passwordVisible ? "eye.slash" : "eye") }
                                .buttonStyle(.borderless).accessibilityLabel(passwordVisible ? "隐藏密码" : "显示密码")
                        }.disabled(model.busy)
                    }
                    Section {
                        Button(action: connect) {
                            HStack {
                                Spacer()
                                if model.busy { ProgressView() }
                                Text(model.busy ? "正在接入…" : model.linked ? "重新接入" : "接入节点")
                                Spacer()
                            }.frame(minHeight: 28)
                        }.buttonStyle(.borderedProminent)
                            .listRowInsets(EdgeInsets())
                            .disabled(model.busy || model.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.password.isEmpty)
                            .accessibilityIdentifier("node-connect")
                    }
                }
                if model.syncing { Section { ProgressView("正在同步…") } }
                if let message = model.message { Section { Text(message).font(.callout).accessibilityIdentifier("node-result") } }
            }
            .modifier(MusicGroupedList())
            .modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .textInputAutocapitalization(.never)
            .scrollDismissesKeyboard(.interactively)
            #endif
        }
        .task(id: model.revision) { await model.refreshCloudStatus() }
        .onDisappear { joinTask?.cancel(); model.password = "" }
    }

    private var cloudOverview: some View {
        Section {
            LabeledContent("插件偏好", value: stored("app", "plugins"))
            LabeledContent("发现栏目偏好", value: stored("app", "discovery"))
            ForEach((model.cloudRecords ?? []).filter { $0.platform == "app" && $0.kind == "data" && $0.key != "plugins" && $0.key != "discovery" }) { record in
                LabeledContent(record.key ?? "", value: "已存云端")
            }
            if let status = model.cloudStatus { Text(status).font(.callout) }
        } header: {
            HStack {
                Text("应用偏好")
                Spacer()
                Button("刷新") { Task { await model.refreshCloudStatus() } }.disabled(model.busy)
            }
        }
    }

    private var sourceRows: some View {
        let cloudIDs = (model.cloudRecords ?? []).map(\.platform).filter { $0 != "app" }
        let ids = Set(names.keys).union(cloudIDs).sorted { (names[$0] ?? $0, $0) < (names[$1] ?? $1, $1) }
        return Section {
            ForEach(ids, id: \.self) { id in
                DisclosureGroup(isExpanded: Binding(get: { expanded == id }, set: { expanded = $0 ? id : nil })) {
                    if expanded == id {
                        let records = (model.cloudRecords ?? []).filter { $0.platform == id }
                        NodeProfileRow(model: model, platform: id, source: providers[id] as? any AccountSource,
                                       stored: stored(id, "profile"))
                        let sessions = records.first { $0.kind == "sessions" }?.count ?? 0
                        LabeledContent("登录会话", value: model.cloudRecords == nil ? "未查询" : sessions > 0 ? "\(sessions) 份" : "未存云端")
                        ForEach(records.filter { $0.kind == "data" && $0.key != "profile" }) { record in
                            LabeledContent(record.key ?? "", value: "已存云端")
                        }
                    }
                } label: {
                    HStack {
                        Text(names[id] ?? id)
                        Spacer()
                        if let records = model.cloudRecords {
                            let count = records.filter { $0.platform == id }.reduce(0) { $0 + $1.count }
                            Text("已存 \(count) 项").font(.callout).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func stored(_ id: String, _ key: String) -> String {
        guard let records = model.cloudRecords else { return "未查询" }
        return records.contains { $0.kind == "data" && $0.platform == id && $0.key == key } ? "已存云端" : "未存云端"
    }

    private func connect() {
        guard !model.busy, !model.password.isEmpty, !model.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        focused = nil
        joinTask = Task { await model.join() }
    }
}

private struct NodeProfileRow: View {
    let model: PlatformDataModel
    let platform: String
    let source: (any AccountSource)?
    let stored: String
    @State private var supported = false
    @State private var syncing = false
    @State private var result: String?

    var body: some View {
        HStack {
            Text("账号资料")
            Spacer()
            Text(stored).foregroundStyle(.secondary)
            if supported {
                Button(syncing ? "同步中" : "同步资料") { syncing = true }.buttonStyle(.borderless)
                    .disabled(syncing || model.busy)
            }
        }
        .task {
            if let plugin = source as? PluginSource { supported = (try? await plugin.supports("account")) == true }
            else { supported = source != nil }
        }
        .task(id: syncing) {
            guard syncing, let source else { return }
            defer { syncing = false }
            do {
                result = try await model.publishProfile(from: source, platform: platform) ? "资料已同步" : "请先在本机登录"
                await model.refreshCloudStatus()
            } catch is CancellationError { } catch { result = error.localizedDescription }
        }
        if let result { Text(result).font(.callout) }
    }
}
