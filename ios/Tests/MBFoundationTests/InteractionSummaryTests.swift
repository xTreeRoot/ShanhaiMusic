import Foundation
@testable import MBFoundation
import Testing

private actor SummarySource: InteractionSource {
    nonisolated let displayName = "互动测试"
    var liked = false
    var failing = false
    let summary: TrackInteractionSummary
    init(likes: Int? = 120, comments: Int? = 30) { summary = TrackInteractionSummary(likes: likes, comments: comments) }
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func interactionSummary(trackID: String) -> TrackInteractionSummary { summary }
    func isLiked(trackID: String) -> Bool { liked }
    func like(trackID: String, enabled: Bool) throws { if failing { throw URLError(.notConnectedToInternet) }; liked = enabled }
    func comments(trackID: String, page: Int) -> CommentPage { CommentPage(comments: [], total: 30, hasMore: false) }
    func postComment(trackID: String, text: String) throws -> TrackComment { throw URLError(.unsupportedURL) }
    func fail() { failing = true }
}
private let summaryTrack = Track(sourceID: "test", sourceTrackID: "song", title: "test", artist: "artist")

@Test @MainActor func platformLikeChangesConfirmedTotalAndFailureKeepsThePreviousState() async {
    let source = SummarySource(), model = InteractionModel(source: source, track: summaryTrack)
    await model.loadSummary()
    #expect(model.summary == TrackInteractionSummary(likes: 120, comments: 30))
    await model.toggleLike()
    #expect(model.liked); #expect(model.summary.likes == 121)
    await model.toggleLike()
    #expect(!model.liked); #expect(model.summary.likes == 120)
    await source.fail(); await model.toggleLike()
    #expect(!model.liked); #expect(model.summary.likes == 120); #expect(model.likeError != nil)
}
@Test @MainActor func unsupportedCountsRemainUnknownEvenAfterLikeSucceeds() async {
    let model = InteractionModel(source: SummarySource(likes: nil, comments: nil), track: summaryTrack)
    await model.loadSummary(); await model.toggleLike()
    #expect(model.liked); #expect(model.summary.likes == nil); #expect(model.summary.comments == nil)
    #expect(TrackInteractionSummary(likes: -1, comments: -10) == TrackInteractionSummary())
}
