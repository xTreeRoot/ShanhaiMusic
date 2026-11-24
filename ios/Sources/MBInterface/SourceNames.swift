import SwiftUI

private struct SourceNamesKey: EnvironmentKey { static let defaultValue: [String: String] = [:] }
extension EnvironmentValues {
    var sourceNames: [String: String] {
        get { self[SourceNamesKey.self] }
        set { self[SourceNamesKey.self] = newValue }
    }
}
