import XCTest
@testable import MBFoundation

final class PlaylistTrackFocusTests: XCTestCase {
    @MainActor func testMatchesSourceAndTrackOnceIncludingPausedQueue() {
        let target = Track(sourceID: "a", sourceTrackID: "1", title: "Current", artist: "")
        let other = Track(sourceID: "b", sourceTrackID: "1", title: "Current", artist: "")
        let queue = PlaybackQueue()
        queue.replace(with: [target], selecting: 0)
        var focus = PlaylistTrackFocus(current: queue.current)
        XCTAssertNil(focus.locate(in: [other], hasMore: true))
        XCTAssertFalse(focus.finished)
        XCTAssertEqual(focus.locate(in: [other, target], hasMore: false), 1)
        XCTAssertNil(focus.locate(in: [target], hasMore: false))
    }

    func testMissingTrackAndManualScrollStopLooking() {
        let target = Track(sourceID: "a", sourceTrackID: "1", title: "Current", artist: "")
        var missing = PlaylistTrackFocus(current: target)
        XCTAssertNil(missing.locate(in: [], hasMore: false))
        XCTAssertTrue(missing.finished)
        var cancelled = PlaylistTrackFocus(current: target)
        cancelled.stop()
        XCTAssertNil(cancelled.locate(in: [target], hasMore: true))
        XCTAssertTrue(PlaylistTrackFocus().finished)
    }
}
