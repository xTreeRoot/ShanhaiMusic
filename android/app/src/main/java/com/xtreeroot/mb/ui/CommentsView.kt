package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBBottomSheet as ModalBottomSheet
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.InteractionCapabilities
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun PlayerInteractionBar(track: Track, signedIn: Boolean, showCommentsButton: Boolean = true) {
    val application = LocalContext.current.applicationContext as MBApplication
    val source = application.playbackSource(track.sourceId) as? InteractionSource ?: return
    key(source, track.id, signedIn) {
        val scope = rememberCoroutineScope()
        var capabilities by remember { mutableStateOf<InteractionCapabilities?>(null) }
        var liked by remember { mutableStateOf<Boolean?>(null) }
        var likeError by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf(false) }
        var showComments by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try {
                val available = source.interactionCapabilities()
                capabilities = available
                if (signedIn && available.likes) liked = source.isLiked(track.sourceTrackId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { likeError = failure.message ?: "无法读取互动状态" }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (capabilities?.likes == true) IconButton(enabled = signedIn && liked != null && !pending, onClick = {
                val desired = !(liked ?: return@IconButton)
                pending = true
                likeError = null
                scope.launch {
                    try {
                        source.like(track.sourceTrackId, desired)
                        liked = desired
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        likeError = failure.message ?: "点赞操作失败"
                    } finally { pending = false }
                }
            }) {
                Icon(if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (liked == true) mb("取消点赞", "Unlike") else mb("点赞", "Like"))
            }
            if (showCommentsButton && capabilities?.comments == true) {
                TextButton(onClick = { showComments = true }) { Text(mb("评论", "Comments")) }
            }
            likeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
        if (showComments) CommentsSheet(source, track, signedIn, onClose = { showComments = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsSheet(source: InteractionSource, track: Track, signedIn: Boolean, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        TrackComments(source, track, signedIn, maxHeight = 360.dp)
    }
}

@Composable
internal fun TrackComments(source: InteractionSource, track: Track, signedIn: Boolean,
                           modifier: Modifier = Modifier, maxHeight: androidx.compose.ui.unit.Dp? = null,
                           sharedPages: CommentPages? = null, showHeader: Boolean = true) = key(source, track.id, signedIn, sharedPages) {
    val pages = sharedPages ?: remember { CommentPages(source, track.sourceTrackId) }
    val state by pages.state.collectAsStateWithLifecycle()
    val capabilities = state.capabilities
    val page = state.page
    var sendError by remember { mutableStateOf<String?>(null) }
    var likeError by remember { mutableStateOf<String?>(null) }
    var pendingLikes by remember { mutableStateOf(emptySet<String>()) }
    var sending by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val canLoad = !state.loading && !sending && pendingLikes.isEmpty()
    fun request(load: suspend () -> Unit) {
        if (!sending && pendingLikes.isEmpty()) scope.launch { if (!sending && pendingLikes.isEmpty()) load() }
    }
    if (sharedPages == null) LaunchedEffect(pages) { pages.refresh() }
    Column(modifier.fillMaxWidth()) {
        if (capabilities?.comments == false) {
            EmptyMessage(mb("此音源不支持评论", "Comments are not available for this source"))
            return@Column
        }
        if (sharedPages == null && showHeader) Text(mb("评论 · ${page.total}", "Comments · ${page.total}"),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        val likeActionFailed = mb("评论点赞失败", "Could not change comment like")
        val listModifier = if (maxHeight == null) Modifier.weight(1f) else Modifier.heightIn(max = maxHeight)
        val refreshing = state.loading && !state.appending && page.comments.isNotEmpty()
        RefreshableList(refreshing = refreshing, refresh = { request(pages::refresh) }, modifier = listModifier.fillMaxWidth()) {
            state.error?.takeUnless { state.appending }?.let { item { Retry(it) { request(pages::retry) } } }
            items(page.comments, key = { it.id }) { comment ->
                ListItem(headlineContent = { Text(comment.author, style = MaterialTheme.typography.labelLarge) },
                    supportingContent = { Text(comment.text) }, trailingContent = {
                        if (signedIn && capabilities?.commentLikes == true) {
                            TextButton(enabled = !state.loading && !sending && comment.id !in pendingLikes, onClick = {
                                if (pages.state.value.loading || sending || comment.id in pendingLikes) return@TextButton
                                val desired = !comment.liked
                                pendingLikes = pendingLikes + comment.id
                                likeError = null
                                scope.launch {
                                    try {
                                        source.likeComment(track.sourceTrackId, comment.id, desired)
                                        ensureActive()
                                        pages.updateLike(comment.id, desired)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { likeError = failure.message ?: likeActionFailed }
                                    finally { pendingLikes = pendingLikes - comment.id }
                                }
                            }) {
                                Icon(if (comment.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    if (comment.liked) mb("取消点赞评论", "Unlike comment") else mb("点赞评论", "Like comment"))
                                Text(comment.likes.toString(), Modifier.padding(start = 4.dp))
                            }
                        } else Text(mb("赞 ${comment.likes}", "${comment.likes} likes"))
                    })
            }
            when {
                state.loading -> if (!refreshing) item { Loading() }
                state.error != null -> if (state.appending) item { Retry(checkNotNull(state.error)) { request(pages::retry) } }
                page.nextCursor != null -> item {
                    PaginationTrigger(page.nextCursor, enabled = canLoad) { request(pages::loadMore) }
                }
                page.comments.isEmpty() && capabilities != null -> item { EmptyMessage(mb("暂无评论", "No comments")) }
            }
        }
        likeError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        if (signedIn && capabilities?.posting == true) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, onValueChange = { draft = it }, enabled = !sending,
                        modifier = Modifier.weight(1f), maxLines = 3, placeholder = { Text(mb("写评论", "Write a comment")) })
                    TextButton(enabled = draft.isNotBlank() && canLoad, onClick = {
                        if (draft.isBlank() || pages.state.value.loading || sending || pendingLikes.isNotEmpty()) return@TextButton
                        val text = draft.trim()
                        sending = true
                        sendError = null
                        scope.launch {
                            try {
                                val sent = source.postComment(track.sourceTrackId, text)
                                ensureActive()
                                pages.recordPosted(sent)
                                draft = ""
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                sendError = failure.message ?: "评论发送失败"
                            } finally { sending = false }
                        }
                    }) { Text(if (sending) mb("发送中", "Sending") else mb("发送", "Send")) }
                }
                sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } else if (capabilities?.posting == true) EmptyMessage(mb("登录后可以发表评论", "Sign in to comment"))
    }
}
