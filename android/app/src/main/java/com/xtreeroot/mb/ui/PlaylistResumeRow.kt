package com.xtreeroot.mb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackPages
import com.xtreeroot.mb.playback.PlaylistResume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalPlaylistPlayback = staticCompositionLocalOf<((String, List<Track>, Track, PlaybackPages?, Long, Boolean) -> Unit)?> { null }

/** 从列表及其续页找回原曲目，保留后续播放分页。 */
internal suspend fun resumeFromTrackList(
    tracks: List<Track>, pages: PlaybackPages?, saved: PlaylistResume,
    play: (List<Track>, Track, PlaybackPages?, Long) -> Unit,
): Boolean {
    val queue = tracks.toMutableList()
    val known = tracks.map(Track::id).toMutableSet()
    var next = pages
    var empty = 0
    while (queue.none { it.id == saved.trackID } && next != null && empty < 8) {
        val batch = next.load()
        val fresh = batch.tracks.filter { known.add(it.id) }
        empty = if (fresh.isEmpty()) empty + 1 else 0
        queue += fresh
        next = batch.next
    }
    val track = queue.firstOrNull { it.id == saved.trackID && !it.isUnavailable } ?: return false
    play(queue, track, next, saved.position)
    return true
}

@Composable
internal fun PlaylistResumeRow(key: String, resume: suspend (PlaylistResume) -> Boolean) {
    val app = LocalContext.current.applicationContext as MBApplication
    val records by app.playlistHistory.values.collectAsStateWithLifecycle()
    val item = records[key]
    var working by remember(key) { mutableStateOf(false) }
    var failed by remember(key, item?.trackID) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val subtitle = when {
        failed -> mb("上次播放的歌曲暂不可用，点按重试", "Last track unavailable. Tap to retry.")
        item == null -> mb("还没播放过", "No playback history")
        else -> {
            val seconds = item.position.coerceAtLeast(0) / 1_000
            item.title + " · " + (seconds / 60) + ":" + (seconds % 60).toString().padStart(2, '0')
        }
    }
    ListItem(
        headlineContent = { Text(mb("上次播放", "Last played")) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = if (working) ({ CircularProgressIndicator(Modifier.heightIn(max = 24.dp)) }) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("playlist-resume")
            .clickable(enabled = item != null && !working, role = Role.Button) {
                if (item == null) return@clickable
                working = true
                failed = false
                scope.launch {
                    try { failed = !resume(item) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { working = false }
                }
            },
    )
}
