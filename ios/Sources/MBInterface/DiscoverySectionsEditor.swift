import MBFoundation
import SwiftUI

private struct DiscoveryPreferencesModelKey: EnvironmentKey { static let defaultValue: PluginModel? = nil }
extension EnvironmentValues {
    var discoveryPreferencesModel: PluginModel? {
        get { self[DiscoveryPreferencesModelKey.self] }
        set { self[DiscoveryPreferencesModelKey.self] = newValue }
    }
}

/// 草稿只在确认时落盘；拖动排序与开关共用同一份栏目选择。
struct DiscoverySectionsEditor: View {
    let source: PluginSource
    let model: PluginModel
    @Environment(\.dismiss) private var dismiss
    @State private var before: DiscoveryPreferences.Selection
    @State private var draft: DiscoveryPreferences.Selection
    @State private var saving = false
    @State private var error: String?
    init(source: PluginSource, model: PluginModel) {
        self.source = source; self.model = model
        let selection = model.discoveryPreferences.resolve(plugin: source.manifest.id, sections: source.manifest.discoverySections.map(\.preferenceSection))
        _before = State(initialValue: selection); _draft = State(initialValue: selection)
    }
    var body: some View {
        NavigationStack {
            List {
                ForEach(draft.order, id: \.self) { id in
                    if let section = source.manifest.discoverySections.first(where: { $0.id == id }) {
                        HStack {
                            Text(LocalizedStringKey(section.title))
                            Spacer()
                            Toggle(LocalizedStringKey(section.title), isOn: Binding(get: { draft.enabled[id] == true }, set: { draft.enabled[id] = $0 }))
                                .labelsHidden().toggleStyle(.switch)
                                .accessibilityIdentifier("discovery-section-\(id)")
                        }
                    }
                }.onMove { offsets, target in draft.order.move(fromOffsets: offsets, toOffset: target) }
                Button("恢复默认") {
                    draft = DiscoveryPreferences().resolve(plugin: source.manifest.id, sections: source.manifest.discoverySections.map(\.preferenceSection))
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            #if os(iOS)
            .environment(\.editMode, .constant(.active))
            #endif
            .disabled(saving)
            .interactiveDismissDisabled(saving)
            .navigationTitle("发现栏目")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) { Button("保存") {
                    saving = true
                    Task { do { try await model.saveDiscovery(source.manifest.id, before: before, draft: draft); dismiss() }
                        catch { self.error = error.localizedDescription; saving = false } }
                }.disabled(saving) }
            }
        }
    }
}
