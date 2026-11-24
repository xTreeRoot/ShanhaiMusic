package com.xtreeroot.mb.music

data class TrackComment(val id: String, val author: String, val avatarUrl: String?, val text: String, val likes: Int,
                        val timeMs: Long = 0, val liked: Boolean = false)

data class CommentPage(val comments: List<TrackComment>, val total: Int, val nextCursor: Int?)

data class InteractionCapabilities(val likes: Boolean = true, val comments: Boolean = true,
                                   val posting: Boolean = true, val commentLikes: Boolean = false)

/** null 表示平台未提供公开总数，不能用 0 替代。 */
data class TrackInteractionSummary(val likes: Long? = null, val comments: Long? = null)

interface InteractionSource : MusicSource {
    suspend fun interactionSummary(trackId: String): TrackInteractionSummary = TrackInteractionSummary()
    suspend fun interactionCapabilities(): InteractionCapabilities = InteractionCapabilities()
    suspend fun isLiked(trackId: String): Boolean
    suspend fun like(trackId: String, enabled: Boolean)
    suspend fun comments(trackId: String, cursor: Int? = null): CommentPage
    suspend fun postComment(trackId: String, text: String): TrackComment
    suspend fun likeComment(trackId: String, commentId: String, enabled: Boolean): Unit =
        throw UnsupportedOperationException("Comment likes are not available")
}
