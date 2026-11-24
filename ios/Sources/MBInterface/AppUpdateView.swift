import MBFoundation
import SwiftUI

struct AppUpdateView: View {
    @Bindable var model: AppUpdateModel
    var automaticChecks: Binding<Bool>? = nil
    @State private var operation: Task<Void, Never>?

    var body: some View {
        Form {
            Section {
                if let automaticChecks { Toggle("启动时检查版本", isOn: automaticChecks) }
                LabeledContent("当前版本", value: "\(model.currentVersion) (\(model.currentBuild))")
                Button("检查版本") { operation = Task { await model.check() } }
                    .disabled(model.busy).accessibilityIdentifier("check-app-version")
                if model.checking { ProgressView("正在检查远程版本…") }
                if model.checked && model.release == nil { Text("已是最新版本").foregroundStyle(.secondary) }
            }
            if let release = model.release {
                Section {
                    LabeledContent("最新版本", value: "\(release.version) (\(release.build))")
                    if !release.notes.isEmpty { Text(release.notes).font(.subheadline).textSelection(.enabled) }
                    Link("查看发布页面", destination: release.page)
                    if model.downloading {
                        ProgressView(value: model.progress)
                        Text(model.progress, format: .percent.precision(.fractionLength(0))).monospacedDigit()
                    }
                    if let file = model.file {
                        ShareLink(item: file) { Label("交给签名工具安装", systemImage: "square.and.arrow.up") }
                            .accessibilityIdentifier("share-app-update")
                    } else {
                        Button("下载更新") { operation = Task { await model.download() } }
                            .disabled(model.busy).accessibilityIdentifier("download-app-update")
                    }
                }
            }
            if let error = model.error {
                Section { Text(error).foregroundStyle(.red).accessibilityIdentifier("app-update-error") }
            }
            Section {
                Text("下载的 IPA 需要交给签名工具签署后覆盖安装，iOS 不允许应用自行安装更新。请使用现有签名与包名，保留原应用和数据。")
                    .font(.footnote).foregroundStyle(.secondary).accessibilityIdentifier("ios-update-signing-note")
            }
            if model.busy {
                Button("取消", role: .cancel) { operation?.cancel(); model.cancel() }.accessibilityIdentifier("cancel-app-update")
            }
        }
        .navigationTitle("检查版本")
        .onDisappear { operation?.cancel(); model.cancel() }
    }
}
