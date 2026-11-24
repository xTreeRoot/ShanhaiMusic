import MBFoundation
import SwiftUI

/// 接入节点采用列表式表单，标签与输入左右排列，并复用根视图的同步模型。
struct ShanhaiServiceView: View {
    @Bindable var model: PlatformDataModel
    let account: AccountModel
    let profile: SourceAccount?
    let platform: String
    @Environment(\.dismiss) private var dismiss
    @State private var passwordVisible = false
    @State private var joinTask: Task<Void, Never>?
    @State private var confirmingUnlink = false
    @FocusState private var focused: Field?
    private enum Field { case address, password }

    var body: some View {
        NavigationStack {
            Form {
                if model.linked {
                    Section("当前节点") {
                        LabeledContent("状态", value: model.authorized ? "已接入" : "授权已过期，请重新接入")
                        Button("解除此设备绑定", role: .destructive) { confirmingUnlink = true }.disabled(model.busy || model.sessionBusy)
                    }
                    if model.authorized {
                        cloudOverview
                        if !platform.isEmpty, account.available { currentSource }
                    }
                }
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
                } header: { Text("接入节点") } footer: {
                    Text("只接入可信节点；节点管理员能够解密同步数据。")
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
                if model.syncing { Section { ProgressView("正在同步…") } }
                if let message = model.message { Section { Text(message).font(.callout).accessibilityIdentifier("node-result") } }
            }
            .modifier(MusicGroupedList())
            .modifier(SkinSurface(defaultColor: MBTheme.grouped, list: true))
            .navigationTitle("山海服务")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .textInputAutocapitalization(.never)
            .scrollDismissesKeyboard(.interactively)
            #endif
        }
        .task(id: model.revision) { await model.refreshCloudStatus() }
        .confirmationDialog("解除此设备绑定？", isPresented: $confirmingUnlink, titleVisibility: .visible) {
            Button("确认解绑", role: .destructive) { Task { await model.unlink() } }
        } message: { Text("不会删除本机登录或云端记录。") }
        .onDisappear { joinTask?.cancel(); model.password = "" }
    }

    private var cloudOverview: some View {
        Section("云端存储") {
            if let records = model.cloudRecords {
                LabeledContent("插件偏好", value: stored(records, platform: "app", key: "plugins"))
                LabeledContent("发现栏目偏好", value: stored(records, platform: "app", key: "discovery"))
                ForEach(records.filter { $0.kind == "sessions" }) { record in
                    LabeledContent(record.platform == platform ? account.displayName : record.platform, value: "登录会话 \(record.count) 份")
                }
                ForEach(records.filter { $0.key == "profile" && $0.platform != platform }) { record in
                    LabeledContent(record.platform, value: "已存账号资料")
                }
            }
            if let status = model.cloudStatus { Text(status).font(.callout).foregroundStyle(.secondary) }
            Button("刷新云端状态") { Task { await model.refreshCloudStatus() } }.disabled(model.busy)
        }
    }

    private var currentSource: some View {
        Section("当前音源：\(account.displayName)") {
            LabeledContent("本机登录", value: profile?.name ?? (account.checkedLocalAccount ? "未登录" : "尚未确认"))
            if let records = model.cloudRecords {
                LabeledContent("账号资料（仅 ID 和昵称）", value: stored(records, platform: platform, key: "profile"))
                let count = records.first { $0.kind == "sessions" && $0.platform == platform }?.count ?? 0
                LabeledContent("云端登录", value: count == 0 ? "未存云端" : "\(count) 份 · 有效性未验证")
            }
            if account.canUseCloud {
                let publish = profile != nil
                Button(publish ? "同步登录到云端" : "从云端恢复登录") {
                    Task {
                        await model.syncSignIn(account, platform: platform, publish: publish, manual: true)
                        await model.refreshCloudStatus()
                    }
                }.disabled(model.busy || model.sessionBusy || account.isSigningIn || account.isSigningOut || !account.checkedLocalAccount)
            } else { Text("此插件不支持跨设备登录。").font(.callout).foregroundStyle(.secondary) }
            if model.sessionPlatform == platform, let status = model.sessionStatus {
                Text(status).font(.callout).accessibilityIdentifier("node-session-result")
            }
            Button("只同步账号资料") { model.sync(profile, platform: platform, publish: true) }
                .disabled(model.busy || model.syncing || profile == nil)
        }
    }

    private func stored(_ records: [NodeCloudRecord], platform: String, key: String) -> String {
        records.contains { $0.kind == "data" && $0.platform == platform && $0.key == key } ? "已存云端" : "未存云端"
    }

    private func connect() {
        guard !model.busy, !model.password.isEmpty, !model.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        focused = nil
        joinTask = Task { await model.join() }
    }
}
