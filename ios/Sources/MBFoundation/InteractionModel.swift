import Foundation
import Observation

@MainActor @Observable
public final class InteractionModel {
    public private(set) var commentLikes = false
    public private(set) var changingComments: Set<String> = []
    public private(set) var capabilities = InteractionCapabilities(likes: false, comments: false, posting: false)
    public private(set) var comments: [TrackComment] = []
    public private(set) var total = 0
    public private(set) var hasMore = false
    public private(set) var isLoading = false
    public private(set) var isPosting = false
    public private(set) var isChangingLike = false
    public private(set) var liked = false
    public private(set) var summary = TrackInteractionSummary()

    public func loadSummary() async {
        let current = generation
        let available = await source.capabilities()
        guard generation == current, !Task.isCancelled else { return }
        capabilities = available
        // 插件运行时按调用串行执行，先查红心，避免慢计数占住执行通道。
        await loadLike()
        do {
            let value = try await source.interactionSummary(trackID: track.sourceTrackID)
            try Task.checkCancellation()
            if generation == current { summary = value }
        } catch { /* 计数失败不阻塞播放或用户操作。 */ }
    }
    public private(set) var errorMessage: String?
    public private(set) var likeError: String?
    @ObservationIgnored private let source: any InteractionSource
    @ObservationIgnored private let track: Track
    public private(set) var nextPage = 1
    private var generation = 0
    public var paginationGeneration: Int { generation }
    public init(source: any InteractionSource, track: Track) { self.source = source; self.track = track }

    public func loadLike() async {
        guard (await source.capabilities()).likesReadable else { return }
        let current = generation
        do {
            let enabled = try await source.isLiked(trackID: track.sourceTrackID)
            if current == generation && !isChangingLike && !Task.isCancelled { liked = enabled; likeError = nil }
        } catch { /* 游客可以阅读评论；点赞登录提示只在用户操作时显示。 */ }
    }
    public func toggleLike() async {
        guard !isChangingLike else { return }
        generation += 1
        let current = generation, enabled = !liked
        isChangingLike = true; likeError = nil
        defer { if current == generation { isChangingLike = false } }
        do {
            try await source.like(trackID: track.sourceTrackID, enabled: enabled)
            if current == generation && !Task.isCancelled {
                liked = enabled
                summary = TrackInteractionSummary(likes: summary.likes.map { max(0, $0 + (enabled ? 1 : -1)) }, comments: summary.comments)
            }
        } catch { if current == generation && !Task.isCancelled { likeError = error.localizedDescription } }
    }
    public func refresh() async { capabilities = await source.capabilities(); commentLikes = await source.supportsCommentLikes(); guard capabilities.comments, !isPosting else { return }; await loadComments(reset: true) }
    public func loadMore() async { guard hasMore, !isLoading, !isPosting else { return }; await loadComments(reset: false) }
    private func loadComments(reset: Bool) async {
        guard !isLoading else { return }
        isLoading = true; errorMessage = nil
        defer { isLoading = false }
        do {
            let page = try await source.comments(trackID: track.sourceTrackID, page: reset ? 1 : nextPage)
            try Task.checkCancellation()
            if reset { comments = []; nextPage = 1 }
            var seen = Set(comments.map(\.id))
            comments += page.comments.filter { seen.insert($0.id).inserted }
            total = page.total; hasMore = page.hasMore; nextPage += 1
        } catch { if !Task.isCancelled { errorMessage = error.localizedDescription } }
    }
    public func toggleCommentLike(_ comment: TrackComment) async {
        guard commentLikes, changingComments.insert(comment.id).inserted else { return }
        defer { changingComments.remove(comment.id) }
        do {
            try await source.likeComment(trackID: track.sourceTrackID, commentID: comment.id, enabled: !comment.liked)
            guard !Task.isCancelled, let index = comments.firstIndex(where: { $0.id == comment.id }) else { return }
            var updated = TrackComment(id: comment.id, author: comment.author, avatarURL: comment.avatarURL, text: comment.text, date: comment.date, likes: max(0, comment.likes + (comment.liked ? -1 : 1)))
            updated.liked = !comment.liked; comments[index] = updated
        } catch { if !Task.isCancelled { errorMessage = error.localizedDescription } }
    }
    public func post(_ text: String) async -> Bool {
        guard !isPosting, !isLoading, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        isPosting = true; errorMessage = nil
        defer { isPosting = false }
        do {
            let comment = try await source.postComment(trackID: track.sourceTrackID, text: text)
            try Task.checkCancellation()
            if !comments.contains(where: { $0.id == comment.id }) { comments.insert(comment, at: 0); total += 1 }
            return true
        } catch { if !Task.isCancelled { errorMessage = error.localizedDescription }; return false }
    }
}
