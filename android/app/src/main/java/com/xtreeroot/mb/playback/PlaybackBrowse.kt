package com.xtreeroot.mb.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.xtreeroot.mb.music.Track

internal const val playbackBrowseRootId = "mb.playback.root"
internal const val resumeMediaId = "mb.playback.resume"

internal val playbackBrowseRoot: MediaItem = MediaItem.Builder().setMediaId(playbackBrowseRootId)
    .setMediaMetadata(MediaMetadata.Builder().setTitle("山海").setIsBrowsable(true).setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

internal fun MediaItem.resumeBrowserItem(): MediaItem = MediaItem.Builder().setMediaId(resumeMediaId)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaMetadata.title).setArtist(mediaMetadata.artist)
        .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

internal fun Player.queueSearchIndex(input: String, available: (Track) -> Boolean): Int? {
    val indices = 0 until mediaItemCount
    val query = input.trim().take(256)
    if (query.isEmpty()) return currentMediaItemIndex.takeIf { it in indices && currentMediaItem?.track()?.let(available) == true }
    var partialMatch: Int? = null
    val words = query.split(Regex("\\s+"))
    for (index in indices) {
        val track = getMediaItemAt(index).track()?.takeIf(available) ?: continue
        if (track.title.equals(query, ignoreCase = true)) return index
        if (partialMatch == null && words.all { track.title.contains(it, true) || track.artist.contains(it, true) }) partialMatch = index
    }
    return partialMatch
}

@OptIn(UnstableApi::class)
internal fun MediaSession.ControllerInfo.canControlPlayback(context: Context, session: MediaSession): Boolean {
    if (uid == context.applicationInfo.uid || isTrusted) return true
    if (!session.isAutoCompanionController(this)) return false
    val host = try { context.packageManager.getApplicationInfo(packageName, 0) }
    catch (_: PackageManager.NameNotFoundException) { return false }
    return host.uid == uid && host.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
