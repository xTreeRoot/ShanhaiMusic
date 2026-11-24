package com.xtreeroot.mb.playback

import com.xtreeroot.mb.music.CommentPage
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackLikesTest {
    @Test fun feedbackWritesOnlyToTheCurrentTrackSource() = runBlocking {
        val first = Source("first")
        val second = Source("second")
        val sources = mapOf(first.id to first, second.id to second)
        var track = Track(first.id, "same-id", "First", "")
        var liked: Boolean? = null
        val likes = SessionTrackLikes({ track }, sources::get) { liked = it }
        likes.refresh(track)
        assertTrue(likes.setLiked(true))
        assertEquals(true, liked)
        assertTrue(likes.setLiked(true))
        assertEquals(listOf("same-id" to true), first.writes)

        track = Track(second.id, "same-id", "Second", "")
        assertFalse(likes.setLiked(false))
        likes.refresh(track)
        assertTrue(likes.setLiked(true))
        assertTrue(likes.setLiked(false))
        assertEquals(false, liked)
        assertEquals(listOf("same-id" to true, "same-id" to false), second.writes)
        assertEquals(listOf("same-id" to true), first.writes)
    }

    private class Source(override val id: String) : InteractionSource {
        private val liked = mutableSetOf<String>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun isLiked(trackId: String) = trackId in liked
        override suspend fun like(trackId: String, enabled: Boolean) {
            writes += trackId to enabled
            if (enabled) liked.add(trackId) else liked.remove(trackId)
        }
        override suspend fun comments(trackId: String, cursor: Int?) = CommentPage(emptyList(), 0, null)
        override suspend fun postComment(trackId: String, text: String) = error("Not used")
    }
}
