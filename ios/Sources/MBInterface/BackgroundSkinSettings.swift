import MBFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

private struct BackgroundDraft: Identifiable {
    let id = UUID()
    let image: CGImage
}

struct BackgroundSkinSettings: View {
    let skin: BackgroundSkin
    let opacity: Double
    @State private var choosing = false
    @State private var importing = false
    @State private var pickingPhoto = false
    @State private var photo: PhotosPickerItem?
    @State private var askingURL = false
    @State private var address = ""
    @State private var draft: BackgroundDraft?
    @State private var loadingPhoto = false
    @State private var importFailed = false
    @State private var importTask: Task<Void, Never>?
    @State private var demoSeeded = false

    var body: some View {
        Form {
            Section {
                Button("选择背景图片", systemImage: "photo") { choosing = true }
                    .accessibilityIdentifier("choose-background-skin")
                if let image = skin.image {
                    Button("预览并调整透明度") { skin.beginPreview(image, opacity: opacity) }
                        .accessibilityIdentifier("preview-current-background")
                }
                if skin.image != nil || skin.failed {
                    Button("恢复默认背景", role: .destructive) { Task { await skin.clear() } }
                        .accessibilityIdentifier("clear-background-skin")
                }
                if skin.busy || loadingPhoto { ProgressView() }
                if skin.failed || importFailed { Text("无法更新背景图片").foregroundStyle(.red) }
            }.disabled(skin.busy || loadingPhoto)
        }
        .navigationTitle("背景皮肤")
        .confirmationDialog("选择图片来源", isPresented: $choosing, titleVisibility: .visible) {
            Button("从文件选择") { importing = true }
            Button("从照片选择") { pickingPhoto = true }
            Button("网络图片地址") { askingURL = true }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image]) { result in
            if case let .success(url) = result { load(.file(url)) }
        }
        .photosPicker(isPresented: $pickingPhoto, selection: $photo, matching: .images)
        .onChange(of: photo) { _, item in
            guard let item else { return }
            importTask?.cancel()
            importTask = Task {
                loadingPhoto = true; importFailed = false
                defer { loadingPhoto = false }
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else { importFailed = true; return }
                    let image = await skin.prepare(.photo(data))
                    guard !Task.isCancelled else { return }
                    if let image { draft = BackgroundDraft(image: image) }
                } catch is CancellationError {} catch { importFailed = true }
                photo = nil
            }
        }
        .alert("网络图片地址", isPresented: $askingURL) {
            TextField("https://", text: $address)
                #if os(iOS)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                #endif
            Button("取消", role: .cancel) {}
            Button("下一步") {
                if let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)) { load(.remote(url)) }
                else { importFailed = true }
            }.disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .sheet(item: $draft) { value in
            BackgroundImageEditor(image: value.image) { candidate in
                draft = nil
                skin.beginPreview(candidate, opacity: opacity)
            }
        }
        .task {
            // 显式演示数据与用户背景目录隔离，供裁剪/取消/确认的真实输入回归使用。
            if !demoSeeded, ProcessInfo.processInfo.arguments.contains("--demo"),
               ProcessInfo.processInfo.arguments.contains("--demo-background-editor") {
                demoSeeded = true
                if let image = DemoCatalog.backgroundImage { draft = BackgroundDraft(image: image) }
            }
        }
        .onDisappear { importTask?.cancel() }
    }
    private func load(_ input: BackgroundSkin.Input) {
        importTask?.cancel(); importFailed = false
        importTask = Task {
            let image = await skin.prepare(input)
            guard !Task.isCancelled else { return }
            if let image { draft = BackgroundDraft(image: image) }
        }
    }
}
