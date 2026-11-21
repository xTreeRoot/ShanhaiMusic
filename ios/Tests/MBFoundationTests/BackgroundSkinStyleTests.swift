import Foundation
@testable import MBFoundation
import Testing

@Test @MainActor func backgroundStylePersistsWithoutChangingOtherPreferences() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AppPersistence(directory: directory)
    var values = try JSONDecoder().decode(AppPreferences.self, from: Data(#"{"volume":0.27,"backgroundOpacity":0.65,"selectedSourceID":"saved"}"#.utf8))
    #expect(values.backgroundStyle == BackgroundSkinStyle())
    values.backgroundStyle.zoom = 2.3; values.backgroundStyle.horizontal = -0.4
    values.backgroundStyle.vertical = 0.7; values.backgroundStyle.blur = 12
    values.backgroundStyle.accent = .rose; values.backgroundStyle.appliesToPlayer = true
    try await store.savePreferences(values)
    let restored = try await AppPersistence(directory: directory).loadPreferences()
    #expect(restored == values)
    #expect(restored?.volume == 0.27 && restored?.selectedSourceID == "saved")
    #expect(restored?.backgroundOpacity == 0.65)
}

@Test func backgroundStyleRejectsOutOfBoundsTransforms() throws {
    let value = try JSONDecoder().decode(BackgroundSkinStyle.self, from: Data(#"{"zoom":99,"horizontal":-4,"vertical":3,"blur":-2}"#.utf8))
    #expect(value.zoom == 4 && value.horizontal == -1 && value.vertical == 1 && value.blur == 0)
    #expect(!value.appliesToPlayer)
}
