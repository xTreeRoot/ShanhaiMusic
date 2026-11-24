import MBFoundation
import SwiftUI

/// 首次启动和从后台返回时静默检查；用户关闭主动检查后不再发请求。
struct AutomaticAppUpdate: ViewModifier {
    @Bindable var preferences: PreferencesModel
    let enabled: Bool
    let canPresent: Bool
    @Environment(\.scenePhase) private var phase
    private let demoModel: Bool
    @State private var model: AppUpdateModel
    @State private var backgrounded = false
    @State private var pending = false
    @State private var showing = false
    @State private var details = false
    @State private var operation: Task<Void, Never>?

    init(preferences: PreferencesModel, enabled: Bool, canPresent: Bool = true, model: AppUpdateModel? = nil) {
        self.preferences = preferences; self.enabled = enabled; self.canPresent = canPresent; demoModel = model != nil
        _model = State(initialValue: model ?? AppUpdateModel())
    }

    func body(content: Content) -> some View {
        content
            .task(id: preferences.isRestored) {
                if enabled && (preferences.isRestored || demoModel) { await check() }
            }
            .onChange(of: phase) { _, next in
                if next == .background { backgrounded = true }
                if next == .active && enabled && preferences.values.automaticUpdateChecks {
                    let returned = backgrounded
                    backgrounded = false
                    if pending && canPresent { pending = false; showing = true }
                    else if returned && (preferences.isRestored || demoModel) {
                        operation = Task { await check() }
                    }
                }
            }
            .onChange(of: canPresent) { _, available in
                if available && pending && phase == .active { pending = false; showing = true }
            }
            .onChange(of: preferences.values.automaticUpdateChecks) { _, value in
                if !value { operation?.cancel(); model.cancel(); pending = false; showing = false }
            }
            .sheet(isPresented: $showing, onDismiss: { operation?.cancel(); model.cancel(); details = false }) {
                NavigationStack {
                    if details { AppUpdateView(model: model) }
                    else if let release = model.release {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 20) {
                                LabeledContent("当前版本", value: "\(model.currentVersion) (\(model.currentBuild))")
                                LabeledContent("最新版本", value: "\(release.version) (\(release.build))")
                                Text(release.notes).font(.subheadline).textSelection(.enabled)
                                HStack {
                                    Button("不再打扰") {
                                        preferences.values.automaticUpdateChecks = false
                                        Task { await preferences.flush() }
                                        showing = false
                                    }
                                    Spacer(minLength: 16)
                                    Button("更新") {
                                        details = true
                                        operation = Task { await model.download() }
                                    }.buttonStyle(.borderedProminent)
                                }
                            }.padding(24)
                        }.navigationTitle("发现新版本")
                            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showing = false } } }
                    }
                }
            }
    }
    private func check() async {
        guard !showing else { return }
        let available = await model.checkAutomatically(enabled: preferences.values.automaticUpdateChecks)
        if available && preferences.values.automaticUpdateChecks && !Task.isCancelled {
            if canPresent && phase == .active { showing = true } else { pending = true }
        }
    }
}
