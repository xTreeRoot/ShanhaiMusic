import MBFoundation
import SwiftUI

/// 接入节点采用列表式表单，标签与输入左右排列，并复用根视图的同步模型。
struct ShanhaiServiceView: View {
    @Bindable var model: PlatformDataModel
    let account: SourceAccount?
    let platform: String
    @Environment(\.dismiss) private var dismiss
    @State private var passwordVisible = false
    @State private var joinTask: Task<Void, Never>?
    @FocusState private var focused: Field?
    private enum Field { case address, password }

    var body: some View {
        NavigationStack {
            Form {
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
                if model.linked {
                    Section("当前节点") {
                        LabeledContent("状态", value: model.authorized ? "已接入" : "授权已过期")
                        Button("同步当前平台资料") { model.sync(account, platform: platform, publish: true) }
                            .disabled(model.busy || model.syncing || !model.authorized || account == nil)
                        Button("解除此设备绑定", role: .destructive) { Task { await model.unlink() } }.disabled(model.busy)
                    }
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
        }.onDisappear { joinTask?.cancel(); model.password = "" }
    }

    private func connect() {
        guard !model.busy, !model.password.isEmpty, !model.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        focused = nil
        joinTask = Task { await model.join() }
    }
}
