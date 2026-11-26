import MBFoundation
import SwiftUI
import UniformTypeIdentifiers

struct PluginPreparationPresentation: ViewModifier {
    let model: PluginModel

    func body(content: Content) -> some View {
        content
            .disabled(model.preparation != nil)
            .accessibilityHidden(model.preparation != nil)
            .overlay {
                if let preparation = model.preparation {
                    ZStack {
                        Color.black.opacity(0.35).ignoresSafeArea().accessibilityHidden(true)
                        VStack(spacing: 16) {
                            switch preparation {
                            case .checking:
                                ProgressView().controlSize(.large).accessibilityHidden(true)
                                Text("正在检查插件").font(.headline)
                                    .accessibilityIdentifier("plugin-checking")
                            case .failed(let message):
                                Text("插件检查失败").font(.headline)
                                Text(message).font(.subheadline).foregroundStyle(.secondary)
                                Button("确定") { model.dismissPreparationError() }.buttonStyle(.borderedProminent)
                                    .accessibilityIdentifier("dismiss-plugin-check-error")
                            }
                        }
                        .multilineTextAlignment(.center)
                        .padding(24)
                        .frame(idealWidth: 255, maxWidth: 320)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
                        .padding(.horizontal, 24)
                        .accessibilityElement(children: .contain)
                        .accessibilityAddTraits(.isModal)
                    }
                }
            }
            .interactiveDismissDisabled(model.preparation != nil)
    }
}

struct PluginsView: View {
    @Environment(\.skinAccent) private var skinAccent
    let model: PluginModel
    @State private var community = false
    @State private var communityModel = PluginCommunityModel()
    @State private var importing = false
    @State private var folder = false
    @State private var github = false
    @State private var stars = false
    @State private var starsAddress = ""
    @State private var repo = ""
    @State private var removing: InstalledPlugin?
    @State private var confirmingRemoval = false
    @State private var displaySettings: InstalledPlugin?
    @State private var renaming: InstalledPlugin?
    @State private var localName = ""
    var body: some View {
        VStack(spacing: 0) {
            Picker("插件", selection: $community) { Text("已安装").tag(false); Text("社区").tag(true) }
                .pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 8).accessibilityIdentifier("plugin-manager-tabs")
            if community {
                PluginCommunityView(installed: model.installed, busy: model.busy, install: { repo in Task { await model.prepareGitHub(repo) } }, model: communityModel)
            } else { installedList }
        }
        .modifier(MusicGroupedList())
        .navigationTitle("插件")
        .onChange(of: model.lastInstalledID) { _, id in if id != nil { community = false } }
        .onChange(of: community) { _, selected in if selected { model.clearInstalledMark() } }
        #if os(iOS)
        .toolbar { ToolbarItem(placement: .primaryAction) { EditButton().disabled(model.busy || community) } }
        #endif
        .alert("本地名称", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("留空恢复默认名称", text: $localName)
            Button("取消", role: .cancel) { renaming = nil }
            Button("保存") { if let plugin = renaming { Task { await model.rename(plugin, to: localName) } }; renaming = nil }
        }
        .sheet(item: $displaySettings) { plugin in DiscoverySectionsEditor(manifest: plugin.manifest, model: model) }
        .sheet(isPresented: $stars, onDismiss: { Task { await model.discardStars() } }) {
            PluginStarsView(model: model, address: $starsAddress)
        }
        .confirmationDialog("删除插件？", isPresented: $confirmingRemoval, titleVisibility: .visible) {
            Button("删除插件", role: .destructive) { if let removing { Task { await model.remove(removing) } } }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            switch result { case .success(let url): Task { await model.prepare(url) }; case .failure(let error): model.reportError(error) }
        }
        .fileImporter(isPresented: $folder, allowedContentTypes: [.folder]) { result in
            switch result { case .success(let url): Task { await model.prepare(url) }; case .failure(let error): model.reportError(error) }
        }
        .alert("从 GitHub 安装", isPresented: $github) {
            TextField("owner/repository", text: $repo).autocorrectionDisabled()
            Button("取消", role: .cancel) {}
            Button("检查") { Task { await model.prepareGitHub(repo) } }.disabled(repo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .sheet(item: Binding(get: { model.pending }, set: { value in if value == nil { Task { await model.discard() } } })) { pending in
            NavigationStack {
                Form {
                    Group {
                        Section {
                            LabeledContent("名称", value: pending.manifest.displayName)
                            if let current = model.installed.first(where: { $0.id == pending.manifest.id }) {
                                LabeledContent("当前版本", value: current.manifest.version ?? "")
                            }
                            LabeledContent("版本", value: pending.manifest.version ?? "")
                            LabeledContent("宿主兼容版本", value: pending.manifest.requiredHostVersion ?? "0.1.0")
                        }
                        Section("新版说明") { Text(pending.releaseNotes ?? "此版本未提供更新说明").font(.callout).textSelection(.enabled) }
                        Section {
                            DisclosureGroup("网络权限") {
                                ForEach(pending.manifest.permissions?.network ?? [], id: \.self) { Text($0).font(.body.monospaced()) }
                            }.accessibilityIdentifier("plugin-review-network-permissions")
                        }
                        if let error = model.errorMessage { Text(error).foregroundStyle(.red) }

                    }.modifier(SkinRow())
                }
                .navigationTitle("安装插件")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { Task { await model.discard() } } }
                    ToolbarItem(placement: .confirmationAction) { Button("安装") { Task { await model.install() } }.disabled(model.busy) }
                }
            }
        }
    }
    private var installedList: some View {
        ScrollViewReader { proxy in
            List {
            Group {
                Section {
                    HStack {
                        Button("一键导入/覆盖", systemImage: "square.and.arrow.down.on.square") { stars = true }
                            .disabled(model.busy || model.pending != nil)
                            .accessibilityIdentifier("plugin-import-stars")
                        Spacer(minLength: 12)
                        Button("全部更新", systemImage: "arrow.down.circle") { Task { await model.updateAll() } }
                            .accessibilityIdentifier("plugin-update-all")
                            .disabled(model.busy || !model.installed.contains(where: { $0.repository != nil }))
                    }.buttonStyle(.borderless)
                }
                if model.updatingAll || model.updateStatus != nil {
                    Section {
                        if model.updatingAll { ProgressView("正在更新插件") }
                        if let status = model.updateStatus { Text(status).font(.caption).foregroundStyle(.secondary) }
                        ForEach(model.installed.filter { model.updateNotes[$0.id] != nil }) { plugin in
                            DisclosureGroup(model.displayName(plugin)) { Text(model.updateNotes[plugin.id] ?? "").font(.callout) }
                        }
                    }
                }
                Section {
                  ForEach(model.installed) { plugin in
                    HStack(spacing: 8) {
                    NavigationLink {
                        PluginSettingsView(model: model, plugin: plugin)
                    } label: {
                        HStack(spacing: 14) {
                            InstalledPluginArtwork(plugin: plugin)
                            VStack(alignment: .leading, spacing: 5) {
                            Text(model.displayName(plugin)).font(.headline)
                            Text(plugin.manifest.version ?? "").font(.caption).foregroundStyle(.secondary)
                            if let version = model.updates[plugin.id] { Text("可更新至 \(version)").font(.caption).foregroundStyle(.tint) }
                            if let issue = model.updateIssues[plugin.id] { Text(issue).font(.caption).foregroundStyle(.secondary) }
                            Text(LocalizedStringKey(model.disabled.contains(plugin.id) ? "已停用" : "已启用")).font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 5)
                    }
                    Button("设置显示") { displaySettings = plugin }
                        .buttonStyle(.borderless).font(.subheadline).fixedSize()
                        .accessibilityIdentifier("plugin-display-\(plugin.id)")
                    }
                    .id(plugin.id)
                    .listRowBackground(model.lastInstalledID == plugin.id ? skinAccent.opacity(0.12) : nil)
                    .swipeActions(allowsFullSwipe: false) { Button("删除", role: .destructive) { removing = plugin; confirmingRemoval = true }.disabled(model.busy) }
                    .contextMenu {
                        Button("本地名称", systemImage: "pencil") { localName = model.presentation.names[plugin.id] ?? ""; renaming = plugin }
                    }
                  }.onMove { offsets, destination in Task { await model.move(from: offsets, to: destination) } }
                }
                Section {
                    Button("从 ZIP 安装", systemImage: "doc.zipper") { importing = true }
                    Button("从文件夹安装", systemImage: "folder") { folder = true }
                    Button("从 GitHub 安装", systemImage: "arrow.down.circle") { github = true }
                        .accessibilityIdentifier("plugin-install-github")
                }.disabled(model.busy)
                if let error = model.errorMessage { Text(error).font(.caption).foregroundStyle(.red) }

            }.modifier(SkinRow())
        }
            .task(id: model.lastInstalledID) {
                guard let id = model.lastInstalledID else { return }
                await Task.yield()
                withAnimation { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }

}

private struct PluginStarsView: View {
    let model: PluginModel
    @Binding var address: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if let entries = model.starsEntries {
                        ForEach(entries, content: repositoryRow)
                    } else {
                        TextField("插件仓库或列表网页地址", text: $address)
                            .autocorrectionDisabled().disabled(model.starsBusy)
                            .accessibilityIdentifier("plugin-stars-address")
                    }
                    if model.starsBusy { ProgressView(model.starsEntries == nil ? "正在解析插件列表" : "正在导入插件") }
                    if let status = model.starsStatus { Text(status).font(.caption).foregroundStyle(.secondary) }

                }.modifier(SkinRow())
            }
            .navigationTitle("一键导入/覆盖")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(model.starsBusy) }
                ToolbarItem(placement: .confirmationAction) { action }
            }
        }
        .interactiveDismissDisabled(model.starsBusy)
    }

    @ViewBuilder private var action: some View {
        if model.starsEntries == nil {
            Button("解析列表") { Task { await model.reviewStars(address) } }
                .disabled(model.starsBusy || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("plugin-stars-read")
        } else if !model.starsFinished {
            Button("导入/覆盖") { Task { await model.applyStars() } }
                .disabled(model.starsBusy || model.starsEntries?.contains(where: { $0.manifest != nil }) != true)
                .accessibilityIdentifier("plugin-stars-confirm")
        }
    }

    private func repositoryRow(_ entry: PluginStarsEntry) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(entry.manifest?.displayName ?? entry.repository).font(.headline)
            if let manifest = entry.manifest {
                Text(entry.repository).font(.caption).foregroundStyle(.secondary)
                let network = manifest.permissions?.network ?? []
                if network.isEmpty { Text("不需要网络权限").font(.caption) }
                else { DisclosureGroup("网络权限") { Text(network.joined(separator: ", ")).font(.caption.monospaced()) } }
            }
            if let issue = entry.issue { Text(issue).font(.caption).foregroundStyle(.secondary) }
        }
    }
}

private struct PluginSettingsView: View {
    let model: PluginModel
    let plugin: InstalledPlugin
    @FocusState private var editing: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingRemoval = false
    @State private var values: [String: String] = [:]
    @State private var loaded = false
    @State private var error: String?
    @State private var saving = false
    @State private var saved = false
    @State private var editingName = false
    @State private var localName = ""
    var body: some View {
        Form {
            Group {
                Section {
                    Button { localName = model.presentation.names[plugin.id] ?? ""; editingName = true } label: {
                        LabeledContent("本地名称", value: model.displayName(plugin))
                    }.accessibilityIdentifier("plugin-local-name")
                }
                Section("来源") {
                    if let repository = plugin.repository?.trimmingCharacters(in: .whitespacesAndNewlines),
                       let url = URL(string: "https://github.com/" + repository) {
                        Link(destination: url) {
                            Label(url.absoluteString, systemImage: "arrow.up.right.square")
                                .font(.callout).textSelection(.enabled)
                        }.accessibilityIdentifier("plugin-repository")
                    } else { LabeledContent("安装来源", value: "本地文件") }
                    LabeledContent("安装位置") {
                        Text(plugin.directory.path).font(.caption.monospaced())
                            .textSelection(.enabled).accessibilityIdentifier("plugin-install-location")
                    }
                }
                ForEach(plugin.manifest.settings ?? []) { setting in
                    Section {
                        switch setting.type {
                        case "password": SecureField(setting.label, text: binding(setting.key))
                        case "boolean": Toggle(setting.label, isOn: Binding(get: { values[setting.key] == "true" }, set: { values[setting.key] = $0 ? "true" : "false" }))
                        case "select":
                            Picker(setting.label, selection: binding(setting.key)) {
                                Text("未设置").tag("")
                                ForEach(setting.options ?? [], id: \.value) { Text($0.label).tag($0.value) }
                            }
                        default: TextField(setting.label, text: binding(setting.key)).autocorrectionDisabled().focused($editing)
                        }
                        if let link = setting.link, let url = URL(string: link), ["http", "https"].contains(url.scheme ?? "") { Link("查看说明", destination: url) }
                    } footer: { if let help = setting.help { Text(help) } }
                }
                Toggle("启用音源", isOn: Binding(get: { !model.disabled.contains(plugin.id) }, set: { enabled in Task { await model.setEnabled(plugin, enabled: enabled) } }))
                    .disabled(model.busy)
                if plugin.repository != nil {
                    Button("更新插件") { Task { await model.update(plugin) } }.disabled(model.busy)
                    if let issue = model.updateIssues[plugin.id] { Text(issue).font(.caption).foregroundStyle(.secondary) }
                    if let notes = model.updateNotes[plugin.id] { Section("新版说明") { Text(notes).font(.callout) } }
                }
                Section { DisclosureGroup("网络权限") { ForEach(plugin.manifest.permissions?.network ?? [], id: \.self) { Text($0).font(.caption.monospaced()) } }.accessibilityIdentifier("plugin-network-permissions") }
                Button("保存") {
                    editing = false; saving = true; saved = false
                    Task { await model.saveSettings(values, for: plugin); saving = false; saved = model.errorMessage == nil }
                }.disabled(!loaded || saving)
                if saving { ProgressView() }
                if saved { Label("已保存", systemImage: "checkmark.circle").foregroundStyle(.secondary) }
                if let error = error ?? model.errorMessage { Text(error).foregroundStyle(.red) }
                Section {
                    Button("删除插件", systemImage: "trash", role: .destructive) { confirmingRemoval = true }
                        .accessibilityIdentifier("delete-installed-plugin")
                        .disabled(model.busy)
                }

            }.modifier(SkinRow())
        }
        .navigationTitle(model.displayName(plugin))
        .alert("本地名称", isPresented: $editingName) {
            TextField("留空恢复默认名称", text: $localName)
            Button("取消", role: .cancel) {}
            Button("保存") { Task { await model.rename(plugin, to: localName) } }
        }
        .confirmationDialog("删除插件？", isPresented: $confirmingRemoval, titleVisibility: .visible) {
            Button("删除插件", role: .destructive) { Task { if await model.remove(plugin) { dismiss() } } }
        } message: { Text("只移除插件，设置和登录数据会保留。") }
        .task {
            do { values = try await model.settings(for: plugin); loaded = true }
            catch { self.error = error.localizedDescription }
        }
    }
    private func binding(_ key: String) -> Binding<String> { Binding(get: { values[key] ?? "" }, set: { values[key] = $0 }) }
}

struct PluginHomeView: View {
    let source: PluginSource
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View {
        PluginBrowseView(source: source, page: nil, select: select, menu: menu)
    }
}

struct PluginBrowseView: View {
    let source: PluginSource
    let page: String?
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View { PluginCatalogPage(source: source, request: page.map(PluginPageRequest.browse) ?? .home, select: select, menu: menu) }
}
