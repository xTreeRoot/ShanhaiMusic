import XCTest
@testable import MBFoundation

final class BackgroundImageCropTests: XCTestCase {
    func testAspectZoomAndDragStayInsideOriginalPixels() throws {
        let centered = try XCTUnwrap(BackgroundImageCrop.rectangle(width: 1200, height: 800, aspect: 1, zoom: 1, horizontal: 0, vertical: 0))
        XCTAssertEqual(centered, CGRect(x: 200, y: 0, width: 800, height: 800))
        let edge = try XCTUnwrap(BackgroundImageCrop.rectangle(width: 1200, height: 800, aspect: 1, zoom: 2, horizontal: 3, vertical: -2))
        XCTAssertEqual(edge, CGRect(x: 0, y: 400, width: 400, height: 400))
        XCTAssertNil(BackgroundImageCrop.rectangle(width: 0, height: 800, aspect: 1, zoom: 1, horizontal: 0, vertical: 0))
        XCTAssertNil(BackgroundImageCrop.rectangle(width: 100, height: 100, aspect: .nan, zoom: 1, horizontal: 0, vertical: 0))
    }
}
