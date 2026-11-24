import Testing
@testable import MBFoundation

@Test func videoAdjustmentReversesWithoutDriftAndRespectsSystemBounds() {
    let drag = VideoAdjustmentDrag(kind: .volume, initial: 0.4, height: 400)
    #expect(abs(drag.value(translationY: -90) - 0.7) < 0.0001)
    #expect(abs(drag.value(translationY: 90) - 0.1) < 0.0001)
    #expect(drag.value(translationY: 0) == 0.4)
    #expect(drag.value(translationY: -1000) == 1)
    #expect(drag.value(translationY: 1000) == 0)
}

@Test func videoBrightnessKeepsAVisibleMinimumAndRejectsInvalidDragGeometry() {
    let drag = VideoAdjustmentDrag(kind: .brightness, initial: 0.6, height: 300)
    #expect(drag.value(translationY: 1000) == 0.01)
    #expect(drag.value(translationY: .nan) == 0.6)
    #expect(VideoAdjustmentDrag(kind: .brightness, initial: 0.6, height: 0).value(translationY: -100) == 0.6)
}
