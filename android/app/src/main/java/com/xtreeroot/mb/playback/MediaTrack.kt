package com.xtreeroot.mb.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.publicArtworkHeader
import org.json.JSONObject

internal const val trackScheme = "mb-track"
internal val enqueueTrack = SessionCommand("com.xtreeroot.mb.ENQUEUE_TRACK", Bundle.EMPTY)
internal val nextStationTrack = SessionCommand("com.xtreeroot.mb.NEXT_STATION_TRACK", Bundle.EMPTY)
internal val leaveStation = SessionCommand("com.xtreeroot.mb.LEAVE_STATION", Bundle.EMPTY)
private const val stationKey = "mb.station"
private const val pagesKey = "mb.queuePages"

internal fun MediaItem.withPages(token: String): MediaItem = buildUpon().setRequestMetadata(
    MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply { putString(pagesKey, token) }).build(),
).build()

internal fun MediaItem.pagesToken(): String? = requestMetadata.extras?.getString(pagesKey)?.takeIf { it.length == 36 }

internal fun MediaItem.withStation(station: PlaybackStation): MediaItem = buildUpon().setRequestMetadata(
    MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply { putString(stationKey, station.encode().toString()) }).build(),
).build()

internal fun MediaItem.station(): PlaybackStation? = requestMetadata.extras?.getString(stationKey)?.let {
    runCatching { PlaybackStation.decode(JSONObject(it)) }.getOrNull()
}

internal fun Track.mediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(Uri.Builder().scheme(trackScheme).authority(sourceId).appendPath(sourceTrackId).build())
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setArtworkUri(artworkUrl?.let(Uri::parse))
        .setExtras(Bundle().apply {
            putString("mb.source", sourceId)
            putString("mb.track", sourceTrackId)
            putLong("mb.duration", durationMs)
            putString("mb.page", pageUrl)
            putString("mb.author", artistId)
            putInt("mb.videoWidth", videoWidth)
            putInt("mb.videoHeight", videoHeight)
            putBoolean("mb.shortVideo", shortVideo)
            putBundle("mb.artworkHeaders", Bundle().apply {
                artworkHeaders.filterKeys(::publicArtworkHeader).forEach { (key, value) -> putString(key, value) }
            })
        })
        .build())
    .build()

internal fun MediaItem.track(): Track? = mediaMetadata.track()

internal fun MediaMetadata.track(): Track? {
    val extras = extras ?: return null
    val source = extras.getString("mb.source") ?: return null
    val identifier = extras.getString("mb.track") ?: return null
    val headers = extras.getBundle("mb.artworkHeaders")
    return Track(source, identifier, title?.toString().orEmpty(), artist?.toString().orEmpty(),
        artworkUri?.toString(), extras.getLong("mb.duration"), extras.getString("mb.page"),
        extras.getString("mb.author"), headers?.keySet()?.associateWith { headers.getString(it).orEmpty() }.orEmpty(),
        videoWidth = extras.getInt("mb.videoWidth"), videoHeight = extras.getInt("mb.videoHeight"), shortVideo = extras.getBoolean("mb.shortVideo"))
}
